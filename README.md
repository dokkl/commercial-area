# 상권정보 지도 (PoC)

소상공인시장진흥공단이 공개한 상가(상권)정보 CSV를 MySQL에 적재하고, 지도 위에서
지역·업종으로 필터링해 탐색하는 웹 서비스. 로컬 전용 PoC이며 클라우드 배포 설정은 없다.

`docker compose up` 한 번으로 CSV 적재부터 지도 화면까지 전부 동작한다. 자세한
기능은 아래 [기능](#기능), API 목록은 [API](#api) 참조.

## 요구 사항

- **Docker Desktop** (Compose v2 이상) — 실행에 필요한 전부
- 상가(상권)정보 CSV — [공공데이터포털](https://www.data.go.kr)에서 내려받아 압축 해제

로컬에 Java나 MySQL을 설치할 필요는 없다. 빌드는 컨테이너 안에서 이뤄진다.
(테스트를 직접 돌리려면 JDK 21이 필요하다 — [개발](#개발) 참조.)

## 로컬 실행

```bash
cp .env.example .env
```

`.env`를 열어 `CSV_DIR`을 CSV 파일이 있는 디렉토리로 바꾼다. 이 경로는 컨테이너에
**읽기 전용**으로 마운트되므로 원본이 수정될 위험은 없다.

```bash
docker compose up -d --build
```

첫 실행은 Gradle 빌드와 MySQL 이미지 내려받기 때문에 몇 분 걸린다.
기동 후 CSV 적재가 자동으로 시작된다.

```bash
docker compose logs -f app
```

적재 진행이 10만 행마다 로그로 찍힌다. 완료되면 이런 줄이 보인다.

```
전체 적재 완료: 성공 1226772 / 실패 0 (101초)
룩업 테이블 재생성 완료 (4243ms)
```

**서울 + 경기 1,226,772행 기준 약 1분 46초** 걸린다 (Apple Silicon 기준 실측).

적재는 **최초 1회만** 일어난다. MySQL 데이터가 named volume에 보존되므로 두 번째
`docker compose up`부터는 즉시 기동한다.

### 정지

```bash
docker compose down          # 컨테이너만 정지, 적재된 데이터는 보존
docker compose down -v       # 볼륨까지 삭제 — 다음 기동에서 전체 재적재
```

## 설정

`.env`에서 바꾼다.

| 변수 | 설명 | 기본값 |
|---|---|---|
| `CSV_DIR` | CSV가 있는 호스트 디렉토리 (읽기 전용 마운트) | — |
| `IMPORT_INCLUDE` | 적재할 파일명 필터(쉼표 구분). 비우면 디렉토리 내 전체 | `서울,경기` |
| `MYSQL_DATABASE` | 데이터베이스 이름 | `commercial_area` |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 애플리케이션 계정 | `app` / `app` |
| `MYSQL_ROOT_PASSWORD` | root 비밀번호 | `root` |

### 다른 지역 적재하기

`IMPORT_INCLUDE`는 파일명에 포함된 문자열로 대상을 고른다. 예를 들어 부산과 대구를
추가하려면:

```
IMPORT_INCLUDE=서울,경기,부산,대구
```

전국 17개 시도(약 250만 행)를 모두 적재하려면 값을 비운다:

```
IMPORT_INCLUDE=
```

적재 대상이 바뀌면 볼륨을 지우고 다시 올려야 한다:

```bash
docker compose down -v && docker compose up -d --build
```

> 전국 적재는 시간과 집계 응답이 행 수에 비례해 늘어난다. 이 PoC는 서울·경기 규모에서
> 검증됐다.

### 포트

| 서비스 | 호스트 포트 |
|---|---|
| 애플리케이션 | 8080 |
| MySQL | **3307** |

MySQL을 3306이 아니라 3307로 노출하는 이유는 로컬에 이미 MySQL이 떠 있어도
충돌하지 않게 하기 위해서다.

## 적재 결과 확인

```bash
docker compose exec mysql mysql --default-character-set=utf8mb4 \
  -uapp -papp commercial_area -e "
    SELECT sido_name, COUNT(*) FROM store GROUP BY sido_name;
    SELECT COUNT(*) AS region FROM region;
    SELECT COUNT(*) AS industry FROM industry;"
```

서울·경기 기준 기대값:

```
서울특별시  554092
경기도      672680
region      1029
industry     247
```

`--default-character-set=utf8mb4`를 빼면 한글이 `?????`로 보인다. 데이터가 깨진 것이
아니라 MySQL 클라이언트의 출력 문자셋 문제다.

## 개발

테스트는 Testcontainers로 MySQL을 띄우므로 **Docker가 실행 중이어야** 한다.

```bash
./gradlew test     # 테스트만
./gradlew build    # 컴파일 + 테스트 + jar
```

로컬에 JDK 21이 필요하다. Gradle은 wrapper로 포함되어 있다.

> 팁: `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true`를 추가하면
> 테스트 실행 간에 MySQL 컨테이너가 재사용되어 반복 실행이 빨라진다.

## 기능

- 지도에 상가 표시 — 줌아웃 시 격자 집계 원(건수를 천/만 단위로 표기), 결과가
  2,000건 이하면 개별 마커로 전환
- 지역 필터 — 시도 → 시군구 → 행정동 (선택 시 해당 구역으로 지도가 이동)
- 업종 필터 — 대분류 → 중분류 → 소분류
- 상호명 앞부분 일치 검색
- 현재 지도 영역 기준 결과 목록과 페이징
- 마커·목록 클릭 시 상호명·업종·주소 상세 팝업

`http://localhost:8080`을 열면 지도가 바로 뜬다.

## API

| 엔드포인트 | 설명 |
|---|---|
| `GET /api/map` | bbox + 필터 → 격자 집계(`mode=cluster`) 또는 개별 점(`mode=point`) |
| `GET /api/stores` | 목록 (페이징) |
| `GET /api/stores/{id}` | 상세 |
| `GET /api/regions/sido\|sgg\|dong` | 지역 드롭다운 |
| `GET /api/industries/large\|medium\|small` | 업종 드롭다운 |

요청/응답 형식과 오류 코드는 [설계 문서](docs/superpowers/specs/2026-09-16-commercial-area-poc-design.md)
§6을 참조한다.

## 문제 해결

**`CSV 디렉토리가 없다: /data/csv`**
`.env`의 `CSV_DIR`이 존재하지 않는 경로를 가리킨다. 경로를 고치고 다시 올린다.

**`적재 대상 파일 0개`**
디렉토리는 있지만 `IMPORT_INCLUDE`와 일치하는 파일이 없다. 컨테이너 안에서 실제로 보이는
파일 목록을 확인한다.

```bash
docker compose exec app ls /data/csv
```

**`store 테이블에 데이터가 이미 있어 적재를 건너뛴다`**
정상이다. 적재는 1회만 일어난다. 강제로 다시 적재하려면 `docker compose down -v`.

**`이전 적재가 끝까지 완료되지 않은 것으로 보인다(룩업 테이블이 비어 있음). 적재를 이어서 진행한다`**
직전 적재가 중간에 끊긴 상태다. 애플리케이션이 자동으로 이어서 적재하므로 별도 조치는
필요 없다. 중복 행은 무시되므로 데이터가 어긋나지 않는다.

**포트 8080 또는 3307이 이미 사용 중**
`docker-compose.yml`의 `ports` 항목에서 호스트 쪽 포트를 바꾼다.

**MySQL 컨테이너가 `healthy`가 되지 않는다**
`docker compose logs mysql`로 원인을 확인한다. 이전에 다른 비밀번호로 만든 볼륨이
남아 있으면 `docker compose down -v`로 지우고 다시 올린다.

## 설계 문서

구조와 결정 근거는 별도 문서에 있다.

- [설계 문서](docs/superpowers/specs/2026-09-16-commercial-area-poc-design.md) — 스키마,
  API 계약, 지도 API 선택 근거, 격자 집계 방식
- [구현 계획](docs/superpowers/plans/2026-09-16-commercial-area-poc.md) — 태스크별 단계와 테스트

## 알려진 제약

- 상호명 검색은 **앞부분 일치**만 지원한다. 한글 부분검색에는 ngram FULLTEXT 인덱스가 필요하다.
- 인증·권한이 없다. 로컬 전용이다.
- 클라우드 배포 설정이 없다.
- 적재가 중단되면 룩업 테이블이 비어 있는 상태로 남고 다음 기동에서 이어서 적재하지만,
  `store` 테이블이 부분 적재 상태인지 자체를 감지하지는 못한다. 확실히 하려면
  `docker compose down -v` 후 재적재한다.
