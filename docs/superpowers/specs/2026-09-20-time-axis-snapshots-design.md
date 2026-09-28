# 설계: 시간축 스냅샷 (개·폐업 추적 + 점포수 추이)

- 작성일: 2026-09-20
- 상태: 승인됨
- 관련: [상권정보 PoC 설계](2026-09-16-commercial-area-poc-design.md),
  [밀도·업종구성 분석 설계](2026-09-19-density-composition-analysis-design.md)

## 1. 배경과 목표

현재 서비스는 공단 상가정보의 **단일 스냅샷**(한 분기)만 다룬다. "지금 어디에 무엇이
있는가"는 답하지만 "늘고 있는가, 줄고 있는가"는 답하지 못한다. 이 기능은 분기별
스냅샷을 **누적**해 시간축 인사이트를 더한다.

두 가지를 보여준다.

- **점포수 추이** — 지역·업종별 점포수가 분기에 따라 어떻게 변하는가
- **개·폐업** — 분기 간 신규 진입(open)과 이탈(close) 건수, 그리고 그로부터 읽히는 흐름

핵심 결정(브레인스토밍 확정):

1. **추적 대상**: 개·폐업 + 집계 추이(둘 다). 개별 이력을 저장하면 집계 추이는 도출된다.
2. **점포 식별자**: `store_id`(상가업소번호) 그대로 사용.
3. **필터 기준**: 행정구역(시도/시군구/행정동) + 업종(대/중/소). 좌표를 저장하지 않는
   슬림 이력이므로 bbox(지도 화면) 기준 추이는 지원하지 않는다.
4. **스냅샷 라벨**: 명시적 설정값 `app.import.snapshot`(YYYYMM).

## 2. 접근 방식

**슬림 이력 테이블 + `store`는 최신 스냅샷 유지(A안).**

- 신규 `store_snapshot` 테이블에 분기별 슬림 행(`store_id` + 지역코드 + 업종코드)을
  누적한다. 기존 `store`/`region`/`industry`는 **최신 스냅샷** 기준으로 그대로 두어
  지도·분석 기능을 건드리지 않는다.
- 개·폐업은 인접 분기 간 `store_id` 집합 차이로, 추이는 `snapshot_ym`별 카운트로 구한다.

검토했으나 채택하지 않은 안:

- **B안: `store` PK에 `snapshot_ym` 추가.** 전 분기 전체 행을 한 테이블에 담아
  bbox 추이까지 가능하지만, 지도·분석의 모든 쿼리·인덱스에 `snapshot_ym=최신` 조건을
  추가해야 해 동작하는 기존 기능을 광범위하게 수정하게 되고 저장이 N배로 는다. 슬림/지역
  기준 결정과도 맞지 않는다.
- **C안: 집계 전용 추이 테이블**(`snapshot_ym, 지역, 업종, count`). 매우 가볍지만
  `store_id`가 없어 **개·폐업 판정이 불가**하다. 요구사항 위배.

### store_id 식별의 한계 (알려진 제약)

공단 상가정보는 분기마다 독립 조사라 `store_id`가 분기 간 재발급될 수 있다. 재발급이
잦으면 같은 물리적 점포가 "폐업 + 신규"로 이중 집계되어 개·폐업 수가 과대평가될 수 있다.
이 PoC는 `store_id` 안정성을 가정하며, 한계를 README에 명시한다. 주소+상호 복합 식별은
후속 과제(§8).

## 3. 스키마 — 신규 2개

`store`/`region`/`industry`는 변경 없음(최신 스냅샷 기준 유지).

```sql
CREATE TABLE IF NOT EXISTS store_snapshot (
  snapshot_ym VARCHAR(6)  NOT NULL,          -- 'YYYYMM'
  store_id    VARCHAR(24) NOT NULL,
  sido_code   VARCHAR(10) NOT NULL,
  sgg_code    VARCHAR(10) NOT NULL,
  dong_code   VARCHAR(20),
  large_code  VARCHAR(10) NOT NULL,
  medium_code VARCHAR(10) NOT NULL,
  small_code  VARCHAR(10) NOT NULL,
  PRIMARY KEY (snapshot_ym, store_id),
  KEY idx_snap_region   (snapshot_ym, sgg_code, dong_code),
  KEY idx_snap_industry (snapshot_ym, large_code, medium_code, small_code),
  KEY idx_store_snap    (store_id, snapshot_ym)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS import_log (
  snapshot_ym  VARCHAR(6) NOT NULL,
  row_count    BIGINT     NOT NULL,
  completed_at DATETIME   NOT NULL,
  PRIMARY KEY (snapshot_ym)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

지역·업종 모두 **코드만** 담는다. 추이 응답은 분기별 카운트·개폐업 수만 반환하므로
지역명/업종명이 필요 없다(슬림 유지). 필요해지면 `region`/`industry` 룩업과 조인한다.

`import_log`는 **스냅샷 단위 적재 완료 기록**이며 멱등성의 기준이다.

## 4. 적재 (ingestion)

### 설정

`ImportProperties`에 `snapshot`(String, YYYYMM) 추가.

- `enabled == true`이고 적재가 필요할 때 `snapshot`이 없거나 6자리 숫자(YYYYMM)가
  아니면 **기동 시 명확한 예외**로 즉시 실패한다.

### `StoreImportRunner.run()` 로직

1. `enabled == false` → 스킵(기존과 동일).
2. `snapshot = settings.snapshot()` 해석·검증.
3. `import_log`에 `snapshot`이 이미 있으면 → "이미 적재된 스냅샷" 로그 남기고 스킵.
4. **부분 적재 정리**: `DELETE FROM store_snapshot WHERE snapshot_ym=:snapshot`.
   `isNewest = (snapshot >= COALESCE(MAX(import_log.snapshot_ym), snapshot))`가 참이면
   `TRUNCATE store`(새 최신 스냅샷으로 교체하기 위해).
5. `importFrom(dir)`: CSV 스트리밍 → 배치마다
   - `repository.insertSnapshotBatch(snapshot, batch)` (슬림, 항상)
   - `isNewest`이면 `repository.insertBatch(batch)` (전체, `store`)
6. 모든 파일 처리 후 `isNewest`이면 `lookupBuilder.rebuild()`.
7. `import_log`에 `(snapshot, row_count, now)` 기록.

동작 요약:

- **최신 스냅샷 로드**(일반, 시간순) = 기존 동작(store 교체 + 룩업 재생성) + 슬림 이력 기록.
- **과거 분기 백필**(이미 더 최신이 있는 상태에서 옛 분기 적재) = 슬림 이력만 기록,
  `store`/룩업 무변.
- **재시작**: 중단된 스냅샷은 `import_log`에 없으므로 4단계에서 잔여를 지우고 처음부터
  다시 적재한다(멱등). 스냅샷 단위 재적재이므로 분기당 ~2분 재수행을 감수한다. 기존의
  행 단위 INSERT IGNORE 이어적재는 스냅샷 교체 의미와 상충하므로 대체한다.

### 신규 리포지토리 메서드

- `StoreRepository.insertSnapshotBatch(String snapshotYm, List<Store> batch)` —
  `INSERT IGNORE INTO store_snapshot (...)`. 배치 크기 분할은 호출부(러너)가 담당(기존
  `insertBatch`와 동일 계약).

## 5. 조회 — TrendService / `GET /api/trend`

### 요청

```
GET /api/trend
```

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `sido`, `sgg`, `dong` | 아니오 | 지역 필터(코드) |
| `large`, `medium`, `small` | 아니오 | 업종 필터(코드) |

bbox·상호명 없음. 모든 필터 생략 시 전체 데이터 추이.

### 응답 `TrendResponse`

```json
{
  "snapshots": [
    { "ym": "202503", "count": 1180, "opened": null, "closed": null },
    { "ym": "202506", "count": 1240, "opened": 95,  "closed": 35 }
  ]
}
```

- `count` — 해당 분기·필터의 점포수:
  `SELECT snapshot_ym, COUNT(*) FROM store_snapshot WHERE <필터> GROUP BY snapshot_ym ORDER BY snapshot_ym`
- `opened(curr)` — 직전 분기에 없고 curr에 새로 나타난 store_id 수.
- `closed(curr)` — 직전 분기에 있었고 curr에 없는 store_id 수.
- 시계열의 **첫 분기**는 `opened=null, closed=null`.

개·폐업 SQL(인접 쌍 prev→curr):

```sql
-- opened: curr에 있고 prev에 없음. 필터는 curr(c) 기준.
SELECT COUNT(*) FROM store_snapshot c
WHERE c.snapshot_ym = :curr <c 필터>
  AND NOT EXISTS (SELECT 1 FROM store_snapshot p
                  WHERE p.snapshot_ym = :prev AND p.store_id = c.store_id);
-- closed: prev에 있고 curr에 없음. 필터는 prev(p) 기준.
SELECT COUNT(*) FROM store_snapshot p
WHERE p.snapshot_ym = :prev <p 필터>
  AND NOT EXISTS (SELECT 1 FROM store_snapshot c
                  WHERE c.snapshot_ym = :curr AND c.store_id = p.store_id);
```

서비스는 먼저 카운트 시계열로 정렬된 `snapshot_ym` 목록을 얻고, 인접 쌍마다 위 두
쿼리를 실행해 opened/closed를 채운다. 분기 수 N은 작으므로 2(N-1)+1 쿼리로 충분하다.

필터는 지역/업종 동등비교만 쓴다. 기존 `StoreFilter`는 bbox를 요구하므로 재사용하지
않고, 신규 `TrendQuery(sido, sgg, dong, large, medium, small)` record와 전용 WHERE
조립기를 둔다.

## 6. UI — "상권 추이" 섹션

`index.html` / `app.js` / `app.css`.

- 좌측 패널에 **"상권 추이 보기"** 버튼 추가. 클릭 시 현재 **지역/업종 드롭다운 선택값**을
  필터로 `/api/trend`를 호출한다(지도 뷰포트가 아니라 필터 기준임에 유의).
- 결과 렌더:
  - **점포수 추이** — 분기별 점포수를 잇는 **인라인 SVG 꺾은선**(차트 라이브러리 없이).
  - **개·폐업** — 분기별 표: `분기 · 총 · 신규 ▲(초록) · 폐업 ▼(빨강)`.
- 스냅샷이 1개뿐이면 "추이를 보려면 2개 이상 분기 스냅샷이 필요합니다" 안내를 표시.
- 기존 fetch·오류 표시 패턴 재사용.

## 7. 오류 처리·엣지

- `snapshot` 미설정/형식오류 → 기동 시 예외(적재 실패, 명확한 메시지).
- 필터 결과 0건 → 빈 `snapshots` 배열, HTTP 200.
- 스냅샷 1개 → 모든 `opened/closed`가 null.
- API 오류는 기존 `GlobalExceptionHandler`가 처리.

## 8. 부트스트랩 현실

추이는 **2개 이상 분기 스냅샷**이 적재돼야 의미가 생긴다. 현재 로컬에는 1개 분기만
있으므로, 사용자가 data.go.kr에서 과거 분기 CSV를 받아 `SNAPSHOT` 값을 바꿔 추가
적재해야 한다. 자동 테스트는 합성 다분기 데이터를 직접 심어 검증한다. README에 2번째
스냅샷 적재 절차를 문서화한다.

## 9. 테스트

기존 스타일(Testcontainers MySQL)을 따른다.

- 스키마·`insertSnapshotBatch` — 슬림 행 삽입/멱등.
- 임포터 — 라벨 적재, `import_log` 멱등 스킵, 과거 백필 시 `store` 무변, 최신 로드 시
  `store`+룩업 재생성.
- `TrendService` — 카운트 시계열, 개·폐업 집합차, 지역/업종 필터, 단일 스냅샷 null,
  빈 결과.
- `TrendApiTest` — JSON 계약, 필터 반영, 스냅샷<2 동작.

## 10. 범위 밖 (YAGNI)

- 주소+상호 복합 식별(store_id 재발급 대응) — 후속.
- bbox 기준 추이(좌표 이력 저장) — 후속.
- 폐업률/생존율 지표, 예측, 고급 차트 — 후속.
