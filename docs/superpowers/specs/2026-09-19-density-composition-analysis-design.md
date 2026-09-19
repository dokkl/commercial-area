# 설계: 화면(bbox) 기반 밀도·업종구성 분석

- 작성일: 2026-09-19
- 상태: 승인됨
- 관련: [상권정보 PoC 설계](2026-09-16-commercial-area-poc-design.md)

## 1. 배경과 목표

현재 서비스는 "상가가 어디에 있는지 지도에서 필터링해 보는 뷰어"다. 사용자가 얻는 것은
조회에 머문다. 이 기능은 서비스를 **조회 도구 → 의사결정 도구**로 한 걸음 옮긴다.

현재 지도에 보이는 영역(bbox)에 대해 다음을 계산해 보여준다.

- **밀도** — 단위면적당 점포수(건/km²)
- **업종 구성** — 화면 내 업종 점유 비중
- **업종 순위 + 특화도** — 소분류 TOP N과 특화지수(LQ)

분석 단위는 **현재 지도 화면(bbox)**이다. 행정구역이나 반경이 아니라, 사용자가 지금
보고 있는 사각 영역을 그대로 분석한다. 기존 `aggregate`/`countFiltered`가 쓰는
bbox + 필터 조건을 그대로 재사용한다.

## 2. 접근 방식

별도 엔드포인트 `GET /api/analysis`와 온디맨드 "분석" 버튼(A안).

- 지도의 bbox·필터와 동일한 파라미터를 받아
  `{총건수, 면적, 밀도, 업종구성, 업종순위+특화도}`를 한 번에 반환한다.
- `StoreFilterSql.appendWhere`를 재사용해 지도와 완전히 같은 조건을 적용한다.
- 매 pan/zoom마다 자동 계산하지 않고, 버튼을 누를 때만 계산해 GROUP BY 부하를
  통제한다. 나중에 "지도 이동 시 자동 갱신"으로 승격하기 쉽다.

대안으로 검토했으나 채택하지 않은 안:

- **`/api/map` 응답에 인라인 포함** — 매 이동마다 GROUP BY 2개가 추가로 돌아 부하가
  크고, 지도 표시와 분석 관심사가 한 응답에 섞인다.
- **행정동별 사전집계 테이블** — 분석 단위(bbox)와 맞지 않고(행정동 경계에 갇힘),
  갱신 관리가 필요하다. PoC엔 과하다.

## 3. API 계약

### 요청

```
GET /api/analysis
```

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `minLat`, `maxLat`, `minLon`, `maxLon` | double | 예 | 분석 대상 bbox |
| `sido`, `sgg`, `dong` | string | 아니오 | 지역 필터(코드) |
| `large`, `medium`, `small` | string | 아니오 | 업종 필터(코드) |
| `q` | string | 아니오 | 상호명 앞부분 일치 |

`/api/map`과 동일한 필터 집합이며 **`zoom`만 제외**한다. 분석은 지도에 보이는 것과
정확히 동일한 조건을 반영한다(필터 존중 → 예측 가능).

### 응답 `AnalysisResponse`

```json
{
  "total": 1234,
  "areaKm2": 2.15,
  "densityPerKm2": 574.0,
  "composition": [
    { "level": "large", "code": "Q", "name": "음식", "count": 540, "share": 0.4376 }
  ],
  "ranking": [
    { "smallCode": "Q12A01", "smallName": "커피전문점", "count": 128, "lq": 2.31 }
  ]
}
```

- `composition[].level` — 집계 레벨(`large` | `medium` | `small`). §4.2 참조.
- `composition[].share` — `count / total` (0~1).
- `ranking[].lq` — 특화지수. §4.3 참조. `globalCount`가 0이면 `null`.

`total = 0`(빈 화면)이면 `densityPerKm2 = 0`, `composition = []`, `ranking = []`로
**HTTP 200**을 반환한다(오류 아님).

## 4. 지표 정의

### 4.1 밀도 (건/km²)

`densityPerKm2 = total / areaKm2`

bbox 면적은 등장방형(equirectangular) 근사로 구한다.

```
midLat = (minLat + maxLat) / 2
latKm  = (maxLat - minLat) * 110.574
lonKm  = (maxLon - minLon) * 111.320 * cos(radians(midLat))
areaKm2 = latKm * lonKm
```

`areaKm2 <= 0`(degenerate bbox)이면 `densityPerKm2 = 0`.

> 캐비엇: bbox 사각형 **전체** 면적 기준이라 비상업 공간(강·산·도로)을 포함한다.
> 절대값이 아니라 **화면 간 상대 비교용 근사치**다. README '알려진 제약'에 명시한다.

### 4.2 업종 구성 (composition) — 적응형 그래뉼래러티

가장 깊은 활성 업종 필터의 **한 단계 아래**로 GROUP BY 한다. 이렇게 하면 필터를 걸어도
구성이 항상 의미를 갖는다(단일 막대 100%가 되지 않는다).

| 활성 업종 필터 | 집계 레벨 | 컬럼 |
|---|---|---|
| 없음 | `large` | `large_code`, `large_name` |
| `large`만 | `medium` | `medium_code`, `medium_name` |
| `large` + `medium` | `small` | `small_code`, `small_name` |
| `small`까지 | `small` | 단일 항목(그 소분류), `share = 1.0` |

- `share = count / total`, 건수 내림차순 정렬.
- `total`은 별도 COUNT 쿼리 없이 `composition`의 count 합으로 구한다(대/중/소 어느
  레벨로 그룹핑해도 화면 내 전체 행을 덮으므로 합이 곧 총건수다).

### 4.3 업종 순위 + 특화도 (ranking)

소분류 TOP N(기본 `N = 10`). 화면 내 소분류별 건수 내림차순.

```
lq = (localCount / localTotal) / (globalCount / globalTotal)
```

- `localCount` — 화면 내 해당 소분류 건수
- `localTotal` — 화면 내 전체 건수(= `total`)
- `globalCount` — `industry.store_count` (해당 `large_code, medium_code, small_code` triple)
- `globalTotal` — `SELECT SUM(store_count) FROM industry`
  (247행 집계라 저렴하고, `store` 전체 카운트가 불필요하며, `globalCount`와 출처가 같아
  정합적이다)

의미: `lq > 1` = 이 화면에 전체 평균보다 상대적으로 밀집(특화). UI는 `lq >= 1.5`를
강조한다. `globalCount = 0`(있을 수 없는 triple)이면 `lq = null`.

ranking은 `industry` 테이블 PK와 정확히 매칭하기 위해 `(large_code, medium_code,
small_code, small_name)`로 그룹핑한다.

## 5. 컴포넌트

기존 패턴을 따른다: Controller → Service → Repository, `store` 패키지 내.

### 신규

| 파일 | 역할 |
|---|---|
| `store/StoreFilter.java` | bbox + 필터 접근자 인터페이스 |
| `store/AnalysisQuery.java` | record, `StoreFilter` 구현, bbox 검증 |
| `store/AnalysisResponse.java` | 응답 DTO |
| `store/CompositionItem.java` | 구성 항목 DTO(`level, code, name, count, share`) |
| `store/RankingItem.java` | 순위 항목 DTO(`smallCode, smallName, count, lq`) |
| `store/AnalysisController.java` | `GET /api/analysis` |
| `store/AnalysisService.java` | total/면적/밀도/ranking+LQ 조립 |
| `store/GeoArea.java` | bbox → km² 유틸 |

### 변경

| 파일 | 변경 |
|---|---|
| `store/StoreFilterSql.java` | `appendWhere(StringBuilder, Map, StoreFilter)`로 시그니처 일반화 |
| `store/MapQuery.java` | `implements StoreFilter` (접근자는 record가 이미 제공) |
| `store/StoreRepository.java` | `groupCount`, `rankingSmall`, `industryCountsForSmall` 추가 |
| `README.md` | 기능·API·알려진 제약(밀도 근사) 갱신 |

`StoreFilter` 인터페이스 도입으로 `StoreFilterSql`은 `MapQuery`와 `AnalysisQuery`를
모두 받는다. 기존 4개 쿼리(aggregate/findPoints/countFiltered/findPage) 호출부는
`MapQuery`가 인터페이스를 구현하므로 그대로 동작한다.

## 6. 데이터 흐름

```
GET /api/analysis
  → AnalysisController: AnalysisQuery 생성 + validate()
  → AnalysisService.analyze(query):
      1. composition = repository.groupCount(query, 레벨컬럼)   // §4.2로 레벨 결정
         total = Σ composition.count
      2. areaKm2 = GeoArea.of(query.bbox());  density = total / areaKm2
      3. rows = repository.rankingSmall(query, N)
         globals = repository.industryCountsForSmall(rows의 triple)
         globalTotal = repository.industryTotal()
         ranking = rows에 LQ 계산 결합
      4. AnalysisResponse 반환
```

`total = 0`이면 2~3을 건너뛰고 density 0, 빈 리스트로 반환한다.

## 7. 오류 처리

- bbox 역전(`minLat > maxLat` 등) → 기존 `ApiException.badRequest("INVALID_BBOX", ...)`
  재사용.
- 빈/degenerate bbox → 0값으로 **200** 응답.
- 그 외는 기존 `GlobalExceptionHandler`가 처리.

## 8. UI

`index.html` / `app.js` / `app.css`.

- 필터 패널에 **"이 화면 분석"** 버튼 추가. 클릭 시 현재 지도 bbox와 활성 필터로
  `/api/analysis`를 호출한다.
- 결과 패널 구성:
  - **요약** — 총 N개 · 면적 X km² · 밀도 Y건/km²
  - **업종 구성** — 가로 막대 리스트(이름, 비중%, 건수). 집계 레벨 라벨 표시.
  - **업종 순위 TOP** — 이름, 건수, LQ 배지(LQ ≥ 1.5 강조 색).
- 기존 fetch·오류 표시 패턴을 재사용한다.

## 9. 테스트

기존 스타일(Testcontainers MySQL)을 따른다.

- `AnalysisApiTest` (웹 계층)
  - 구성 비중 합 ≈ 1, `ranking` 길이 ≤ N, LQ 계산값 검증, 밀도 > 0
  - **필터 존중** — `large` 필터를 걸면 composition이 중분류 레벨로 내려간다
  - 빈 bbox → 0값/빈 배열, 200
  - bbox 역전 → `INVALID_BBOX`
- `AnalysisQueryTest` (단위) — bbox 검증, 적응형 레벨 선택 로직
- `GeoAreaTest` (단위) — 알려진 bbox → 기대 km²(허용오차 내), degenerate bbox → 0

## 10. 성능 고려

- 쿼리는 bbox 필터가 걸린 GROUP BY 2개(composition, ranking)로, 기존 `aggregate`와
  같은 성능 envelope 안에 있다. `idx_geo`, `idx_large_geo`, `idx_small_geo`가 돕는다.
- `globalTotal`/`globalCount`는 247행 `industry` 테이블에서 나오므로 `store` 전체
  카운트가 없다.
- 온디맨드 호출이라 pan/zoom마다 돌지 않는다.

## 11. 범위 밖 (YAGNI)

- 시계열(개·폐업 추이) — 별도 기능(분기 스냅샷 누적)으로 분리.
- 반경 기반 분석, 지역 간 비교 모드 — 후속.
- 인증·저장(즐겨찾기) — 후속.
