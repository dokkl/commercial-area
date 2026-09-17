# 상권정보 지도 서비스 PoC — 설계 문서

작성일: 2026-09-16

## 1. 목적과 범위

소상공인시장진흥공단이 공개한 상가(상권)정보 CSV를 MySQL에 적재하고, 지도 위에서
지역·업종으로 필터링해 탐색하는 웹 서비스를 PoC 수준으로 만든다.

클라우드 배포는 하지 않는다. `docker compose up` 한 번으로 로컬에서 전부 동작하는 것이
완료 기준이다.

### 적재 범위

| 시도 | 행 수 | 파일 크기 |
|---|---:|---:|
| 서울 | 554,092 | 302 MB |
| 경기 | 672,680 | 369 MB |
| **합계** | **1,226,772** | **671 MB** |

시군구 72개. 업종 대분류 10종, 중분류·소분류는 적재 후 룩업 테이블에서 확정한다
(강원 기준 중분류 74 / 소분류 245이며, 서울·경기는 이보다 많을 수 있다).

### 명시적 범위 밖

아래는 이번 PoC에서 만들지 않는다.

- 인증 / 로그인 / 사용자 관리
- 업종별 통계 차트 및 대시보드 (요구사항은 "필터"이지 "분석"이 아니다)
- CI 파이프라인, 클라우드 배포
- 전국 17개 시도 데이터 (서울·경기 외)
- 상호명 **부분**일치 검색 — 앞부분 일치만 지원한다. 한글 부분검색은 ngram FULLTEXT
  인덱스가 필요하고, 이를 bbox 범위 조건과 함께 쓰면 옵티마이저가 둘 중 하나만
  선택하게 되어 별도 검증이 필요하다. 후속 과제로 둔다.

## 2. 기술 스택

| 항목 | 선택 | 비고 |
|---|---|---|
| 런타임 | Java 21 (LTS) | Spring Boot 4는 Java 17~26 지원. 로컬에 Corretto 21 설치됨 |
| 프레임워크 | Spring Boot 4.0.x | |
| 빌드 | Gradle 9.x (wrapper) | Boot 4 Gradle 플러그인은 8.14+ 또는 9.x 요구 |
| DB | MySQL 8.4 | |
| DB 접근 | `spring-boot-starter-jdbc` + `JdbcClient` | **JPA 미사용** — 아래 근거 참조 |
| 뷰 | Thymeleaf 3 | |
| 지도 | Leaflet 1.9.x + Leaflet.markercluster | webjars로 로컬 번들 |
| 타일 | OpenStreetMap | API 키 불필요 |
| CSV 파싱 | Apache Commons CSV | RFC4180 파서 |
| 컨테이너 | Docker Compose | |
| 테스트 | JUnit 5 + Testcontainers(MySQL) + MockMvc | |

### JPA를 쓰지 않는 근거

1. 지도 조회 쿼리는 bbox 조건에 6종의 선택적 필터가 붙는 **동적 SQL**이다. JPA로는
   Criteria API나 문자열 JPQL 조립이 필요해 오히려 번거롭다.
2. 핵심 쿼리인 격자 집계는 `FLOOR()` 기반 `GROUP BY`로, JPA 엔티티 매핑과 무관한
   집계 전용 투영이다.
3. 122만 행 배치 인서트에서 영속성 컨텍스트는 순수한 오버헤드다.

### 지도 API 선택 근거

Leaflet + OpenStreetMap을 택했다. API 키 발급과 도메인 등록 절차가 전혀 없어
`docker compose up` 즉시 동작하며, 벤더 종속이 없다. 국내 지명 표기가 더 필요해지면
Leaflet의 타일 레이어 URL 한 줄만 VWorld로 교체하면 된다.

VWorld(국토부)와 Kakao Map SDK는 지명 품질이 더 낫지만 키 발급과 localhost 도메인
등록이 선행되어야 해서, "즉시 실행되는 PoC"라는 목표와 상충한다.

**제약**: OSM 타일 이미지는 인터넷 연결이 필요하다 (어떤 지도 공급자를 써도 동일).
Leaflet 라이브러리 자체는 webjars로 번들되므로 오프라인에서도 로드된다.

## 3. 데이터 소스

경로: `/Users/hoon/Downloads/소상공인시장진흥공단_상가(상권)정보_20260630/`

### 확인된 파일 특성

- 인코딩: **UTF-8** (동봉된 안내문에 명시, `file` 명령으로 확인)
- 컬럼 수: **39개**
- 좌표계: **WGS84 경위도** — 좌표 변환 불필요. (예: 경도 127.729466, 위도 37.878276)
- 좌표 결측: 서울 554,092행 중 **0건**. 결측 보정 로직 불필요.
- **혼합 따옴표 형식**: 문자열 필드는 `"..."`로 감싸지만, 숫자 필드(지번본번지,
  지번부번지, 건물본번지, 건물부번지, 경도, 위도)는 따옴표 없이 들어간다. 빈 숫자
  필드는 `,,`로 표현된다.

  → **`split(",")`이나 `split("\",\"")` 같은 나이브 파싱은 반드시 깨진다.**
  정식 RFC4180 파서(Apache Commons CSV)를 사용해야 한다. 이는 실제 데이터로 확인한
  사항이며, 구현 시 가장 먼저 테스트로 고정할 지점이다.

### 컬럼 매핑 (39개 중 21개 사용)

CSV 헤더 이름으로 매핑한다 (`CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)`).
컬럼 위치가 아니라 이름 기반이므로, 시도별 파일 간 컬럼 순서가 달라져도 안전하다.

| # | CSV 헤더 | DB 컬럼 | 타입 |
|---:|---|---|---|
| 1 | 상가업소번호 | `store_id` | VARCHAR(24) PK |
| 2 | 상호명 | `store_name` | VARCHAR(255) NOT NULL |
| 3 | 지점명 | `branch_name` | VARCHAR(255) |
| 4 | 상권업종대분류코드 | `large_code` | VARCHAR(10) NOT NULL |
| 5 | 상권업종대분류명 | `large_name` | VARCHAR(100) NOT NULL |
| 6 | 상권업종중분류코드 | `medium_code` | VARCHAR(10) NOT NULL |
| 7 | 상권업종중분류명 | `medium_name` | VARCHAR(100) NOT NULL |
| 8 | 상권업종소분류코드 | `small_code` | VARCHAR(10) NOT NULL |
| 9 | 상권업종소분류명 | `small_name` | VARCHAR(100) NOT NULL |
| 12 | 시도코드 | `sido_code` | VARCHAR(10) NOT NULL |
| 13 | 시도명 | `sido_name` | VARCHAR(50) NOT NULL |
| 14 | 시군구코드 | `sgg_code` | VARCHAR(10) NOT NULL |
| 15 | 시군구명 | `sgg_name` | VARCHAR(50) NOT NULL |
| 16 | 행정동코드 | `dong_code` | VARCHAR(20) |
| 17 | 행정동명 | `dong_name` | VARCHAR(50) |
| 25 | 지번주소 | `lot_address` | VARCHAR(255) |
| 31 | 건물명 | `building_name` | VARCHAR(255) |
| 32 | 도로명주소 | `road_address` | VARCHAR(255) |
| 36 | 층정보 | `floor_info` | VARCHAR(30) |
| 38 | 경도 | `lon` | DECIMAL(10,7) NOT NULL |
| 39 | 위도 | `lat` | DECIMAL(10,7) NOT NULL |

**미사용 18개 컬럼**: 표준산업분류코드/명, 법정동코드/명, 지번코드, 대지구분코드/명,
지번본번지/부번지, 도로명코드, 도로명, 건물본번지/부번지, 건물관리번호,
구우편번호, 신우편번호, 동정보, 호정보.

PoC 화면에서 쓰지 않으므로 적재하지 않는다. CSV 원본은 보존되므로 나중에 컬럼을
추가하는 것은 스키마 변경 + 재적재로 언제든 가능하다.

## 4. 데이터베이스 스키마

### `store`

```sql
CREATE TABLE IF NOT EXISTS store (
  store_id      VARCHAR(24)   NOT NULL,
  store_name    VARCHAR(255)  NOT NULL,
  branch_name   VARCHAR(255),
  large_code    VARCHAR(10)   NOT NULL,
  large_name    VARCHAR(100)  NOT NULL,
  medium_code   VARCHAR(10)   NOT NULL,
  medium_name   VARCHAR(100)  NOT NULL,
  small_code    VARCHAR(10)   NOT NULL,
  small_name    VARCHAR(100)  NOT NULL,
  sido_code     VARCHAR(10)   NOT NULL,
  sido_name     VARCHAR(50)   NOT NULL,
  sgg_code      VARCHAR(10)   NOT NULL,
  sgg_name      VARCHAR(50)   NOT NULL,
  dong_code     VARCHAR(20),
  dong_name     VARCHAR(50),
  lot_address   VARCHAR(255),
  road_address  VARCHAR(255),
  building_name VARCHAR(255),
  floor_info    VARCHAR(30),
  lon           DECIMAL(10,7) NOT NULL,
  lat           DECIMAL(10,7) NOT NULL,
  PRIMARY KEY (store_id),
  KEY idx_geo        (lat, lon),
  KEY idx_sgg_geo    (sgg_code,   lat, lon),
  KEY idx_dong_geo   (dong_code,  lat, lon),
  KEY idx_large_geo  (large_code, lat, lon),
  KEY idx_small_geo  (small_code, lat, lon),
  KEY idx_name       (store_name(20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

**복합 인덱스를 `(필터컬럼, lat, lon)` 형태로 두는 이유**: 지도 조회는 항상 bbox
조건이 있고 거기에 필터가 선택적으로 얹히는 구조다. 필터 컬럼을 선두에 두면 MySQL이
필터로 범위를 좁힌 뒤 좌표 범위를 이어서 탐색할 수 있다. 단일 컬럼 인덱스만 있으면
둘 중 하나만 인덱스로 처리되고 나머지는 행 단위 필터링이 된다.

중분류(`medium_code`) 전용 인덱스는 두지 않는다. 중분류 필터는 대부분 대분류와 함께
쓰이고, `idx_large_geo`로 충분히 좁혀지기 때문이다. 실측 후 느리면 추가한다.

### `region` (지역 룩업)

```sql
CREATE TABLE IF NOT EXISTS region (
  sido_code   VARCHAR(10)   NOT NULL,
  sido_name   VARCHAR(50)   NOT NULL,
  sgg_code    VARCHAR(10)   NOT NULL,
  sgg_name    VARCHAR(50)   NOT NULL,
  dong_code   VARCHAR(20)   NOT NULL,
  dong_name   VARCHAR(50)   NOT NULL,
  store_count INT           NOT NULL,
  min_lat     DECIMAL(10,7) NOT NULL,
  max_lat     DECIMAL(10,7) NOT NULL,
  min_lon     DECIMAL(10,7) NOT NULL,
  max_lon     DECIMAL(10,7) NOT NULL,
  PRIMARY KEY (sido_code, sgg_code, dong_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**bbox 4컬럼을 포함하는 이유**: 사용자가 "강남구"를 선택했는데 지도가 서울 전체에
머물러 있으면 필터가 무의미하다. 지역 선택 시 해당 구역으로 `fitBounds` 하려면 그
구역의 경계 좌표가 필요하다. 적재 후 `MIN()/MAX()` 집계로 한 번에 채운다.

### `industry` (업종 룩업)

```sql
CREATE TABLE IF NOT EXISTS industry (
  large_code  VARCHAR(10)  NOT NULL,
  large_name  VARCHAR(100) NOT NULL,
  medium_code VARCHAR(10)  NOT NULL,
  medium_name VARCHAR(100) NOT NULL,
  small_code  VARCHAR(10)  NOT NULL,
  small_name  VARCHAR(100) NOT NULL,
  store_count INT          NOT NULL,
  PRIMARY KEY (large_code, medium_code, small_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**룩업 테이블을 두는 이유**: 필터 드롭다운을 채울 때마다 122만 행에 대해
`SELECT DISTINCT`를 실행할 수는 없다. 적재 후 1회 생성해두면 드롭다운은 즉시
응답하고, 덤으로 각 항목에 건수를 함께 표시할 수 있다 (`강남구 (38,241)`).

`dong_code`가 NULL인 행이 있을 수 있으므로, 룩업 생성 시 `COALESCE(dong_code, '')`로
정규화해 PK 제약을 만족시킨다.

## 5. CSV 적재

### 방식

- **파서**: Apache Commons CSV, 헤더 이름 기반 매핑.
- **인서트**: `JdbcClient` 배치, 배치 크기 1,000, `INSERT IGNORE`.
- **JDBC URL에 `rewriteBatchedStatements=true` 필수**. 이 옵션이 없으면 MySQL
  커넥터가 배치를 단건 인서트로 전송해 10배 가까이 느려진다.
- `INSERT IGNORE`를 쓰는 이유: 재실행 시 멱등성 확보. 상가업소번호가 PK이므로
  중복 행은 조용히 건너뛰고, 중단된 적재를 이어서 진행할 수 있다.

### 실행 시점

`ApplicationRunner`로 구현한다. 부팅 직후 — 즉 **웹 서버가 이미 요청을 받는 상태에서**
— `store` 테이블이 비어 있으면 적재를 시작한다.

서버를 블로킹하지 않는 것이 핵심이다. 적재가 진행되는 2~4분 동안 지도는 이미 떠 있고
점이 점차 채워진다.

MySQL 데이터 볼륨이 유지되므로 **적재는 최초 1회만** 발생한다. 두 번째
`docker compose up`부터는 즉시 기동한다.

### 설정

```yaml
app:
  import:
    enabled: true
    dir: /data/csv
    include: ["서울", "경기"]   # 파일명에 이 문자열이 포함된 것만 적재
    batch-size: 1000
```

`include`를 빈 배열로 두면 디렉토리 내 모든 CSV를 적재한다 (전국 확장 시).

### 진행 로깅 및 오류 처리

- 10만 행마다 누적 행 수와 경과 시간을 INFO로 출력한다.
- 행 파싱 실패 시 해당 행만 건너뛰고 WARN + 실패 카운터를 누적한다. 122만 행 적재가
  한 행 때문에 중단되어서는 안 된다.
- 파일별 적재 완료 시 `성공 N / 실패 M` 요약을 출력한다. 실패율이 1%를 초과하면
  ERROR 레벨로 출력해 데이터 형식 변경을 알아차릴 수 있게 한다.
- 전체 적재 완료 후 룩업 테이블 2개를 `INSERT INTO ... SELECT ... GROUP BY`로 생성한다.

### `LOAD DATA LOCAL INFILE`을 쓰지 않는 이유

2~3배 빠르지만 서버 측 `local_infile=1` 설정과 컬럼 위치 하드코딩이 필요하다.
최초 1회만 도는 작업에서 얻는 이득이 적고, 읽고 수정할 수 있는 Java 코드 쪽이
PoC에 적합하다.

## 6. API 설계

### 6.1 지도 데이터 — `GET /api/map`

핵심 엔드포인트.

**요청 파라미터**

| 이름 | 필수 | 설명 |
|---|:---:|---|
| `minLat`, `maxLat`, `minLon`, `maxLon` | O | 현재 화면 bbox |
| `zoom` | O | Leaflet 줌 레벨 (0~22) |
| `sido`, `sgg`, `dong` | | 지역 필터 (코드) |
| `large`, `medium`, `small` | | 업종 필터 (코드) |
| `q` | | 상호명 앞부분 일치 |

**응답 모드 결정 — 줌이 아니라 건수로 가른다**

서버는 항상 격자 집계 쿼리를 먼저 실행한다. 이 쿼리는 셀 목록과 함께 총 건수를
`SUM(cnt)`로 부수적으로 제공한다.

- 총 건수 **≤ 2,000** → 개별 점 쿼리를 추가 실행, `mode=point` 반환
- 총 건수 **> 2,000** → 집계 결과 그대로, `mode=cluster` 반환

**줌 고정 임계값 대신 건수를 쓰는 이유**: "제주 한정식"처럼 필터를 강하게 걸면 줌이
낮아도 결과가 수십 건에 불과하다. 줌 기준이면 이 수십 건이 의미 없는 클러스터 원으로
뭉개지지만, 건수 기준이면 곧바로 실제 마커가 표시된다. 필터가 표시 방식을 직접
좌우하게 된다.

총 건수는 집계 쿼리에서 이미 계산되므로 별도 `COUNT(*)` 쿼리가 필요 없다. 점 모드인
경우에만 두 번째 쿼리가 실행되며, 이때는 결과가 2,000건 이하임이 이미 확정된
상태이므로 `LIMIT`이 필요 없고 응답이 잘리는 경우도 없다.

**격자 크기 — 전역 원점에 고정**

```
cell = 360 / 2^(zoom + 3)
```

뷰포트를 N등분하는 방식은 지도를 조금만 드래그해도 격자 경계가 밀려 클러스터 원이
화면에서 튄다. 전역 격자에 스냅하면 패닝해도 원이 제자리에 있다.

줌 레벨당 화면에 대략 38×25 ≈ 950셀이 들어오므로 응답 크기가 줌과 무관하게 일정하다.

**집계 SQL**

```sql
SELECT FLOOR(lat / :cell) AS gy,
       FLOOR(lon / :cell) AS gx,
       COUNT(*)  AS cnt,
       AVG(lat)  AS clat,
       AVG(lon)  AS clon
FROM store
WHERE lat BETWEEN :minLat AND :maxLat
  AND lon BETWEEN :minLon AND :maxLon
  -- 아래는 파라미터가 있을 때만 동적으로 추가
  AND sgg_code   = :sgg
  AND large_code = :large
  AND store_name LIKE CONCAT(:q, '%')
GROUP BY gy, gx
```

셀 대표 좌표로 셀 중심이 아니라 `AVG(lat), AVG(lon)` 무게중심을 쓴다. 셀 중심을 쓰면
해안 지역에서 클러스터 원이 바다 위에 표시된다.

**응답**

```json
{ "mode": "cluster", "total": 128455,
  "cells": [ { "lat": 37.5103, "lon": 127.0221, "count": 3412 } ] }
```

```json
{ "mode": "point", "total": 842,
  "points": [ { "id": "MA010620...", "name": "파크랜드춘천",
                "lat": 37.8782756, "lon": 127.7294662, "large": "소매" } ] }
```

점 payload에 주소를 넣지 않는 것은 의도적이다. 2,000개 점에 주소까지 실으면 응답이
몇 배로 커진다. 상세는 마커 클릭 시 별도 조회한다.

### 6.2 지역 룩업

```
GET /api/regions/sido            → [{ code, name, count }]
GET /api/regions/sgg?sido=11     → [{ code, name, count, minLat, maxLat, minLon, maxLon }]
GET /api/regions/dong?sgg=11680  → [{ code, name, count, minLat, maxLat, minLon, maxLon }]
```

`sgg`와 `dong` 응답에는 bbox를 포함해 클라이언트가 `fitBounds`에 바로 쓸 수 있게 한다.

`region` 테이블은 행정동 단위로 한 행씩 저장되므로, 시군구 목록은 행정동 행들을
집계해서 만든다.

```sql
SELECT sgg_code, sgg_name, SUM(store_count) AS count,
       MIN(min_lat) AS min_lat, MAX(max_lat) AS max_lat,
       MIN(min_lon) AS min_lon, MAX(max_lon) AS max_lon
FROM region WHERE sido_code = :sido
GROUP BY sgg_code, sgg_name ORDER BY sgg_name
```

시도 목록도 같은 방식으로 `sido_code` 기준 집계한다 (bbox는 불필요).

### 6.3 업종 룩업

```
GET /api/industries/large               → [{ code, name, count }]
GET /api/industries/medium?large=I2     → [{ code, name, count }]
GET /api/industries/small?medium=I201   → [{ code, name, count }]
```

### 6.4 목록 — `GET /api/stores`

`/api/map`과 동일한 필터 + `page`(0부터), `size`(기본 20). 현재 지도 영역 기준 페이징
목록을 반환한다. 지도와 목록이 같은 조건을 보도록 bbox를 포함한다.

```json
{ "page": 0, "size": 20, "total": 842,
  "items": [ { "id", "name", "branchName", "largeName", "mediumName",
               "smallName", "roadAddress", "lat", "lon" } ] }
```

### 6.5 상세 — `GET /api/stores/{id}`

마커 클릭 시 팝업용. `store` 테이블의 전체 21개 필드를 반환한다.
존재하지 않으면 404.

### 6.6 오류 응답

전역 `@RestControllerAdvice`로 통일한다.

```json
{ "error": "INVALID_BBOX", "message": "minLat은 maxLat보다 작아야 합니다." }
```

| 상황 | 상태 | 코드 |
|---|:---:|---|
| bbox 파라미터 누락 | 400 | `MISSING_BBOX` |
| bbox가 아닌 필수 파라미터 누락 (예: `/api/regions/sgg`에 `sido` 없이 요청) | 400 | `MISSING_PARAMETER` |
| `minLat > maxLat` 또는 `minLon > maxLon` | 400 | `INVALID_BBOX` |
| `zoom`이 0~22 범위 밖 | 400 | `INVALID_ZOOM` |
| 파라미터 타입 불일치 (예: `zoom=abc`) | 400 | `INVALID_PARAMETER` |
| 존재하지 않는 `store_id` | 404 | `STORE_NOT_FOUND` |
| 매핑되지 않은 경로 | 404 | `NOT_FOUND` |
| 그 외 서버 오류 | 500 | `INTERNAL_ERROR` |

`MISSING_PARAMETER`, `INVALID_PARAMETER`, `NOT_FOUND`는 구현 중 추가됐다.
`MissingServletRequestParameterException`은 bbox 4개(`minLat`/`maxLat`/`minLon`/`maxLon`)
파라미터명이면 `MISSING_BBOX`, 그 외(예: `/api/regions/sgg`의 `sido`)면 `MISSING_PARAMETER`로
나눠 응답한다. 전역 캐치올(`Exception.class`)은 이와 별개로
`MethodArgumentTypeMismatchException`(예: `zoom=abc`)과 `NoResourceFoundException`
(매핑되지 않은 경로)까지 붙잡아 500/`INTERNAL_ERROR`로 잘못 분류하고 ERROR 레벨로
로깅하고 있었다. 셋 다 클라이언트 입력 오류이지 서버 오류가 아니므로 전용 핸들러로
분리해 각각 400/400/404로 반환하고 로그도 남기지 않도록 했다 (`GlobalExceptionHandler`).

## 7. 화면

단일 페이지. Thymeleaf는 정적인 껍데기(레이아웃, 빈 `<select>` 등)만 렌더링하고,
시도 목록·업종 대분류를 포함한 드롭다운 데이터는 초기 데이터든 이후 상호작용이든 전부
`app.js`의 `init()`이 위 API를 `fetch()`로 호출해 채운다 — 서버사이드 렌더링과 클라이언트
호출을 나누기보다 데이터 경로를 하나로 통일한 것이다. `index.html`이 Thymeleaf 네임스페이스를
선언하지만 `th:` 속성은 쓰지 않는 것은 그래서다.

```
┌──────────────────────────────────────────────────┐
│  상권정보 지도                        총 128,455건 │
├──────────────┬───────────────────────────────────┤
│ [지역]       │                                   │
│  시도   ▼    │                                   │
│  시군구 ▼    │          Leaflet 지도             │
│  행정동 ▼    │     (집계 원 ○ 또는 마커 📍)       │
│              │                                   │
│ [업종]       │                                   │
│  대분류 ▼    │                                   │
│  중분류 ▼    ├───────────────────────────────────┤
│  소분류 ▼    │  결과 목록 (현재 화면 · 페이징)    │
│              │   상호명 │ 업종 │ 주소            │
│ [상호명]     │   ⋯                               │
│  [________]  │                                   │
└──────────────┴───────────────────────────────────┘
```

### 상호작용

| 이벤트 | 동작 |
|---|---|
| 지도 `moveend` | 300ms 디바운스 후 `/api/map` + `/api/stores` 재호출 |
| 상위 드롭다운 변경 | 하위 드롭다운 재조회 + 초기화, 데이터 재조회 |
| 시군구/행정동 선택 | 해당 bbox로 `map.fitBounds()` |
| 집계 원 클릭 | 해당 셀 중심으로 2단계 줌인 |
| 마커 클릭 | `/api/stores/{id}` 조회 후 팝업 표시 |
| 목록 항목 클릭 | 해당 좌표로 이동 + 팝업 표시 |

### 렌더링

- **cluster 모드**: `L.circleMarker` + `L.divIcon`으로 건수 라벨. 반지름은
  `count`의 제곱근에 비례시킨다 (선형이면 대도시 셀이 화면을 덮는다).
- **point 모드**: `L.markerClusterGroup`으로 국소 클러스터링 + 개별 마커.

### 에셋

Leaflet과 Leaflet.markercluster는 **CDN이 아니라 webjars로 로컬 번들**한다
(`org.webjars.npm:leaflet`, `org.webjars.npm:leaflet.markercluster`).
Gradle 의존성 두 줄이면 되고, 사내망·오프라인에서도 화면이 로드된다.

OSM 타일 이미지는 인터넷이 필요하다. 1인 PoC 수준의 트래픽은 OSM 타일 사용 정책상
문제없으나, 타일 요청에 식별 가능한 `Referer`가 전달되도록 기본 설정을 유지한다.

## 8. 프로젝트 구조

```
commercial-area/
├── docker-compose.yml
├── Dockerfile
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/
├── .env.example
├── .gitignore
├── README.md
├── docs/superpowers/specs/
└── src/
    ├── main/
    │   ├── java/com/example/commercialarea/
    │   │   ├── CommercialAreaApplication.java
    │   │   ├── config/     GlobalExceptionHandler, AppProperties
    │   │   ├── importer/   StoreCsvParser, StoreImportRunner, LookupBuilder
    │   │   ├── store/      Store, StoreRepository, StoreService,
    │   │   │               MapController, StoreController, dto/
    │   │   ├── lookup/     RegionRepository, IndustryRepository, LookupController
    │   │   └── web/        PageController
    │   └── resources/
    │       ├── application.yml
    │       ├── schema.sql
    │       ├── templates/index.html
    │       └── static/{app.js, app.css}
    └── test/
        └── java/com/example/commercialarea/
            ├── importer/   StoreCsvParserTest, StoreImportRunnerTest
            ├── store/      StoreRepositoryTest, MapApiTest
            └── support/    MySqlTestContainer
```

### 단위 책임

| 단위 | 역할 | 의존 |
|---|---|---|
| `StoreCsvParser` | CSV 한 행 → `Store`. I/O 없음, 순수 변환 | 없음 |
| `StoreImportRunner` | 파일 순회, 배치 인서트, 진행 로깅 | `StoreCsvParser`, `StoreRepository` |
| `LookupBuilder` | 적재 후 룩업 테이블 생성 | `JdbcClient` |
| `StoreRepository` | bbox 집계 / 점 조회 / 목록 / 단건. 동적 SQL 조립 | `JdbcClient` |
| `StoreService` | 모드 결정(집계 vs 점), 격자 크기 계산 | `StoreRepository` |
| `MapController` | 파라미터 검증, `/api/map` | `StoreService` |

`StoreCsvParser`가 I/O를 갖지 않는 것이 중요하다. 파일이나 DB 없이 문자열만으로
테스트할 수 있어야, 가장 깨지기 쉬운 파싱 로직을 빠르게 고정할 수 있다.

## 9. 테스트 전략

TDD로 진행한다. 실제 122만 행은 테스트에 쓰지 않고 수백 행 픽스처를 사용한다.

### `StoreCsvParserTest` — 단위, 컨테이너 불필요

가장 먼저 작성한다. 혼합 따옴표 형식이 이 프로젝트에서 가장 깨지기 쉬운 지점이다.

- 실제 서울 CSV에서 발췌한 행이 모든 필드로 정확히 매핑되는가
- 지점명이 빈 문자열(`""`)인 행 → `null`
- 상호명에 콤마가 포함된 행이 올바르게 파싱되는가
- 숫자 필드가 비어 있는 행(`,,`)이 파싱되는가
- 좌표가 비어 있거나 숫자가 아닌 행 → 예외를 던져 상위에서 건너뛸 수 있게

### `StoreRepositoryTest` — Testcontainers MySQL 8.4

- bbox 조회가 영역 밖 행을 제외하는가
- **격자 집계 셀 건수의 합이 전체 건수와 정확히 일치하는가** (집계 정확성의 핵심)
- 같은 셀에 속한 점들이 하나의 셀로 묶이는가, 셀 대표 좌표가 무게중심인가
- 지역 필터 · 업종 필터 · 조합 필터가 정확히 동작하는가
- 상호명 앞부분 일치 검색
- 목록 페이징 경계 (마지막 페이지, 빈 결과)

### `MapApiTest` — `@SpringBootTest` + MockMvc

- 총 건수가 2,000 이하이면 `mode=point`, 초과하면 `mode=cluster`로 전환되는가
  (경계값 2,000 / 2,001 검증)
- bbox 누락 → 400 `MISSING_BBOX`
- `minLat > maxLat` → 400 `INVALID_BBOX`
- `zoom=23` → 400 `INVALID_ZOOM`
- 없는 `store_id` → 404

### `StoreImportRunnerTest` — Testcontainers

- 소형 CSV 적재 후 행 수가 일치하는가
- 룩업 테이블 2개가 생성되고 `store_count`와 bbox 값이 정확한가
- **재실행해도 행이 중복되지 않는가** (`INSERT IGNORE` 멱등성)
- 깨진 행이 섞여 있어도 나머지가 모두 적재되고 실패 카운터가 정확한가

## 10. Docker 구성 및 실행

### `docker-compose.yml`

- **mysql**: `mysql:8.4`, `utf8mb4`, 명명 볼륨 `mysql-data`, healthcheck.
  `--innodb-buffer-pool-size=1G`로 적재 성능을 확보한다.
- **app**: 멀티스테이지 Dockerfile (`gradle:9-jdk21` 빌드 → `eclipse-temurin:21-jre`
  실행). `depends_on: mysql (condition: service_healthy)`. 포트 8080.
- CSV 디렉토리는 **읽기 전용 바인드 마운트**(`:ro`)로 연결한다. 원본 데이터가
  수정될 위험을 원천 차단한다.

### `.env.example`

```
CSV_DIR=/Users/hoon/Downloads/소상공인시장진흥공단_상가(상권)정보_20260630
IMPORT_INCLUDE=서울,경기
MYSQL_DATABASE=commercial_area
MYSQL_USER=app
MYSQL_PASSWORD=app
MYSQL_ROOT_PASSWORD=root
```

`.env`는 `.gitignore`에 포함한다.

### 실행

```bash
cp .env.example .env
docker compose up -d --build
docker compose logs -f app    # 적재 진행 확인
open http://localhost:8080
```

## 11. 완료 기준

1. `docker compose up -d --build` 한 번으로 MySQL과 앱이 기동한다.
2. 최초 기동 시 서울·경기 CSV 1,226,772행이 자동 적재되고, 진행 상황이 로그로 보인다.
3. `http://localhost:8080`에서 지도가 뜨고, 줌아웃 시 집계 원이, 줌인 시 개별 마커가
   표시된다.
4. 시도 → 시군구 → 행정동 연쇄 필터가 동작하고, 선택 시 해당 구역으로 지도가 이동한다.
5. 업종 대 → 중 → 소분류 연쇄 필터가 동작한다.
6. 지역·업종 필터를 함께 걸면 지도와 목록이 동시에 반영된다.
7. 마커 클릭 시 상호명·업종·주소가 담긴 팝업이 표시된다.
8. 두 번째 기동부터는 재적재 없이 즉시 사용 가능하다.
9. 전체 테스트가 통과한다.

## 12. 검증이 필요한 가정

PoC 구현 중 실측해야 할 항목이다. 예상과 다르면 대응이 필요하다.

| 가정 | 예상 | 실측 | 대응 |
|---|---|---|---|
| 122만 행 배치 적재 소요 | 2~4분 | **1분 46초** (CSV 적재 101초 + 룩업 테이블 재생성 4.2초) | 목표 이내. 대응 불필요 |
| 서울·경기 전역 줌아웃 격자 집계 응답 | 1초 이내 | **약 0.76초** (측정 1.15초에서 curl CLI 오버헤드 약 0.39초 차감). 업종 필터를 함께 걸면 약 0.21초로 더 빨라진다 | 목표 이내. 대응 불필요 — 커버링 인덱스나 생성 컬럼 격자 키를 추가하지 않았다 |
| 점 모드 상한 2,000건 렌더링 | 부드러움 | 브라우저에서 2,000건 근처까지 렌더링해 확인함 — 부드러움 | 대응 불필요 |
| 필터 + bbox 복합 인덱스 활용 | 인덱스 스캔 | 구현 전 기간 동안 필터·bbox 조합 쿼리를 실사용 수준으로 반복 실행했으나 성능 문제가 나타나지 않음 | 대응 불필요 |

두 번째 항목(줌아웃 집계)이 설계 시점에 가장 불확실하다고 표시했던 항목이다. 느릴 경우
대응책으로 검토했던 커버링 인덱스 추가나 줌 구간별 생성 컬럼(`grid_mid`) 도입은
실측 결과 목표를 여유 있게 충족해 수행하지 않았다. `schema.sql`도 변경하지 않았다.
