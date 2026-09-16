# 상권정보 지도 서비스 PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 소상공인시장진흥공단 상가(상권)정보 CSV 122만 행을 MySQL에 적재하고, Leaflet 지도 위에서 지역·업종으로 필터링해 탐색하는 웹 서비스를 로컬 Docker 환경에서 동작시킨다.

**Architecture:** Spring Boot 4 단일 애플리케이션. 부팅 직후 `ApplicationRunner`가 CSV를 배치 적재하고 룩업 테이블을 만든다. 지도 조회는 bbox + 선택적 필터로 격자 집계 쿼리를 돌리고, 총 건수가 2,000 이하면 개별 점을, 초과하면 격자 집계를 반환한다. Thymeleaf가 껍데기를 렌더링하고 이후는 `fetch()`로 REST API를 호출한다.

**Tech Stack:** Java 21, Spring Boot 4.0.x, Gradle 9, MySQL 8.4, `JdbcClient` (JPA 미사용), Thymeleaf 3, Leaflet 1.9 + Leaflet.markercluster (webjars), Apache Commons CSV, Docker Compose, JUnit 5 + Testcontainers + MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-16-commercial-area-poc-design.md`

## Global Constraints

모든 태스크의 요구사항에 아래가 암묵적으로 포함된다.

- Java **21**, Spring Boot **4.0.x**, Gradle **9.x** (Boot 4는 Gradle 8.14+ 요구)
- 패키지 루트: `com.example.commercialarea`
- DB 접근은 **`JdbcClient`만** 사용한다. JPA/Hibernate 의존성을 추가하지 않는다.
- JDBC URL에 **`rewriteBatchedStatements=true`** 가 반드시 포함되어야 한다. 없으면 배치 인서트가 10배 느려진다.
- CSV 파싱은 **Apache Commons CSV**로만 한다. `split(",")` 계열 문자열 분리는 금지 — CSV가 혼합 따옴표 형식(문자열은 `"..."`, 숫자는 무따옴표)이라 반드시 깨진다.
- CSV 인코딩은 **UTF-8**, 컬럼 수는 **39개**, 좌표계는 **WGS84**(변환 불필요).
- CSV 컬럼 매핑은 **헤더 이름 기반**으로 한다. 컬럼 위치(인덱스) 하드코딩 금지.
- 점 모드 임계값은 **2,000건**. 격자 크기는 **`360 / 2^(zoom+3)`** (전역 원점 고정).
- CSV 원본 디렉토리는 **읽기 전용(`:ro`)** 으로만 마운트한다.
- 테스트에 실제 122만 행 파일을 사용하지 않는다. 수백 행 이하 픽스처만 쓴다.
- 커밋 메시지는 한글 본문 + Conventional Commits 접두사(`feat:`, `test:`, `docs:`, `chore:`).

---

## File Structure

| 파일 | 책임 |
|---|---|
| `build.gradle.kts`, `settings.gradle.kts` | 빌드 정의 |
| `Dockerfile`, `docker-compose.yml`, `.env.example` | 로컬 실행 환경 |
| `config/AppProperties.java` | `app.import.*` 설정 바인딩 |
| `config/GlobalExceptionHandler.java`, `config/ApiException.java` | 오류 응답 통일 |
| `store/Store.java` | 적재/조회 공용 도메인 레코드 |
| `importer/StoreCsvParser.java` | CSV 한 행 → `Store`. **I/O 없음** |
| `importer/StoreImportRunner.java` | 파일 순회·배치 인서트·진행 로깅 |
| `importer/LookupBuilder.java` | 적재 후 `region`/`industry` 생성 |
| `store/StoreFilterSql.java` | bbox + 선택적 필터 SQL 조립 (4개 쿼리가 공유) |
| `store/StoreRepository.java` | 집계·점·목록·단건 조회, 배치 인서트 |
| `store/StoreService.java` | 모드 결정, 격자 크기 계산 |
| `store/MapController.java` | `/api/map` |
| `store/StoreController.java` | `/api/stores`, `/api/stores/{id}` |
| `lookup/RegionRepository.java`, `lookup/IndustryRepository.java` | 룩업 조회 |
| `lookup/LookupController.java` | `/api/regions/*`, `/api/industries/*` |
| `web/PageController.java` | `GET /` |
| `resources/templates/index.html` | 화면 껍데기 |
| `resources/static/app.js`, `app.css` | 지도·필터·목록 동작 |
| `resources/schema.sql` | 테이블 3개 DDL |

`StoreCsvParser`가 I/O를 갖지 않는 것이 핵심이다. 파일·DB 없이 문자열만으로 테스트할 수 있어야 가장 깨지기 쉬운 파싱 로직을 먼저 고정할 수 있다.

`StoreFilterSql`을 분리하는 이유는 집계·점·목록·카운트 4개 쿼리가 **동일한 필터 조건**을 써야 하기 때문이다. 각자 조립하면 필터 하나를 추가할 때 네 군데를 고쳐야 하고 반드시 어긋난다.

---

### Task 1: 프로젝트 스캐폴딩과 Docker 실행 환경

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `.gitignore`, `.env.example`, `Dockerfile`, `docker-compose.yml`
- Create: `src/main/java/com/example/commercialarea/CommercialAreaApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/com/example/commercialarea/CommercialAreaApplicationTests.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: `com.example.commercialarea.CommercialAreaApplication` — 이후 모든 `@SpringBootTest`의 설정 클래스. Gradle 좌표와 `app.import.*` 설정 키.

- [ ] **Step 1: Gradle wrapper 생성**

```bash
cd /Users/hoon/dev/commercial-area
gradle wrapper --gradle-version 9.0.0
```

Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` 생성.

- [ ] **Step 2: `settings.gradle.kts` 작성**

```kotlin
rootProject.name = "commercial-area"
```

- [ ] **Step 3: `build.gradle.kts` 작성**

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    implementation("org.apache.commons:commons-csv:1.11.0")

    implementation("org.webjars:webjars-locator-lite")
    implementation("org.webjars.npm:leaflet:1.9.4")
    implementation("org.webjars.npm:leaflet.markercluster:1.5.3")

    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.withType<Test> { useJUnitPlatform() }
```

commons-csv는 **1.11.0으로 고정**한다. 1.13부터 `CSVFormat.Builder.build()`가 `get()`으로 바뀌어 Task 2의 코드가 컴파일되지 않는다.

- [ ] **Step 4: 애플리케이션 클래스 작성**

`src/main/java/com/example/commercialarea/CommercialAreaApplication.java`:

```java
package com.example.commercialarea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CommercialAreaApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommercialAreaApplication.class, args);
    }
}
```

- [ ] **Step 5: `application.yml` 작성**

```yaml
spring:
  application:
    name: commercial-area
  datasource:
    url: jdbc:mysql://localhost:3307/commercial_area?rewriteBatchedStatements=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul
    username: app
    password: app
    hikari:
      maximum-pool-size: 10
  sql:
    init:
      mode: always
  thymeleaf:
    cache: false

app:
  import:
    enabled: true
    dir: /data/csv
    include: ["서울", "경기"]
    batch-size: 1000

logging:
  level:
    com.example.commercialarea: INFO
```

- [ ] **Step 6: `.gitignore` 작성**

```
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.env
*.log
.idea/
.DS_Store
```

- [ ] **Step 7: `.env.example` 작성**

```
CSV_DIR=/Users/hoon/Downloads/소상공인시장진흥공단_상가(상권)정보_20260630
IMPORT_INCLUDE=서울,경기
MYSQL_DATABASE=commercial_area
MYSQL_USER=app
MYSQL_PASSWORD=app
MYSQL_ROOT_PASSWORD=root
```

- [ ] **Step 8: `Dockerfile` 작성**

```dockerfile
FROM gradle:9.0-jdk21 AS build
WORKDIR /build
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 9: `docker-compose.yml` 작성**

```yaml
services:
  mysql:
    image: mysql:8.4
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_general_ci
      - --innodb-buffer-pool-size=1G
    environment:
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
    ports:
      - "3307:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 5s
      timeout: 5s
      retries: 30

  app:
    build: .
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?rewriteBatchedStatements=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul
      SPRING_DATASOURCE_USERNAME: ${MYSQL_USER}
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_PASSWORD}
      APP_IMPORT_DIR: /data/csv
      APP_IMPORT_INCLUDE: ${IMPORT_INCLUDE}
    volumes:
      - ${CSV_DIR}:/data/csv:ro
    ports:
      - "8080:8080"

volumes:
  mysql-data:
```

호스트 포트를 **3307**로 여는 이유는 로컬에 이미 MySQL이 떠 있어도 충돌하지 않게 하기 위해서다.

- [ ] **Step 10: 컨텍스트 로딩 테스트 작성**

`src/test/java/com/example/commercialarea/CommercialAreaApplicationTests.java`:

```java
package com.example.commercialarea;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.sql.init.mode=never",
        "app.import.enabled=false"
})
class CommercialAreaApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

DB 없이 컨텍스트만 뜨는지 확인하는 테스트다. Task 3에서 Testcontainers가 들어오면 실제 DB 테스트가 생긴다.

- [ ] **Step 11: 빌드와 테스트 실행**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`, `contextLoads` 통과.

만약 `spring.autoconfigure.exclude`의 `DataSourceAutoConfiguration` 경로가 틀려 실패하면, 실제 경로를 확인한다:

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -i spring-boot-starter-jdbc
unzip -l ~/.gradle/caches/modules-2/files-2.1/org.springframework.boot/spring-boot-jdbc/*/*/spring-boot-jdbc-*.jar | grep DataSourceAutoConfiguration
```

- [ ] **Step 12: Docker 기동 확인**

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Expected: `mysql`이 `healthy`, `app`이 `running`.

앱은 아직 테이블이 없어 `/`가 404를 반환하지만, **기동 자체가 성공하면 이 태스크는 통과**다. 확인:

```bash
docker compose logs app | grep -i "Started CommercialAreaApplication"
```

Expected: `Started CommercialAreaApplication in N seconds` 로그가 보인다.

- [ ] **Step 13: 커밋**

```bash
docker compose down
git add -A
git commit -m "chore: 프로젝트 스캐폴딩과 Docker 실행 환경 구성

Spring Boot 4 + Gradle 9 + MySQL 8.4 구성.
CSV 디렉토리는 읽기 전용으로 마운트한다.
MySQL 호스트 포트는 로컬 MySQL과 충돌하지 않도록 3307을 쓴다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: 스키마와 CSV 파서

**Files:**
- Create: `src/main/resources/schema.sql`
- Create: `src/main/java/com/example/commercialarea/store/Store.java`
- Create: `src/main/java/com/example/commercialarea/importer/StoreCsvParser.java`
- Create: `src/main/java/com/example/commercialarea/importer/MalformedRowException.java`
- Test: `src/test/java/com/example/commercialarea/importer/StoreCsvParserTest.java`
- Test fixture: `src/test/resources/fixtures/sample-stores.csv`

**Interfaces:**
- Consumes: Task 1의 Gradle 설정 (`commons-csv`).
- Produces:
  - `record Store(String storeId, String storeName, String branchName, String largeCode, String largeName, String mediumCode, String mediumName, String smallCode, String smallName, String sidoCode, String sidoName, String sggCode, String sggName, String dongCode, String dongName, String lotAddress, String buildingName, String roadAddress, String floorInfo, double lon, double lat)`
  - `StoreCsvParser.FORMAT` → `CSVFormat` (헤더 기반)
  - `StoreCsvParser.parse(CSVRecord) → Store`, 실패 시 `MalformedRowException`
  - `schema.sql`의 테이블 3개: `store`, `region`, `industry`

- [ ] **Step 1: 테스트 픽스처 생성**

실제 서울 CSV에서 헤더와 몇 행을 발췌하고, 경계 케이스 3행을 손으로 추가한다.

```bash
mkdir -p src/test/resources/fixtures
SRC="/Users/hoon/Downloads/소상공인시장진흥공단_상가(상권)정보_20260630/소상공인시장진흥공단_상가(상권)정보_서울_202606.csv"
head -4 "$SRC" > src/test/resources/fixtures/sample-stores.csv
```

그다음 아래 3행을 파일 끝에 **그대로** 덧붙인다. 각각 상호명에 콤마 포함 / 좌표 결측 / 지점명·행정동 결측 케이스다.

```
"TEST0000000000000000001","가나다, 라마바","","I2","음식","I201","한식","I20101","백반·가정식","I56111","한식 일반 음식점업","11","서울특별시","11680","강남구","11680510","역삼1동","1168010100","역삼동","1168010100100010001","1","대지",1,1,"서울특별시 강남구 역삼동 1-1","116804100001","서울특별시 강남구 테헤란로",1,,"1168010100100010001000001","테스트빌딩","서울특별시 강남구 테헤란로 1","135080","06232","","2","201",127.0276,37.4979
"TEST0000000000000000002","좌표없는가게","","I2","음식","I201","한식","I20101","백반·가정식","I56111","한식 일반 음식점업","11","서울특별시","11680","강남구","11680510","역삼1동","1168010100","역삼동","1168010100100010002","1","대지",1,2,"서울특별시 강남구 역삼동 1-2","116804100001","서울특별시 강남구 테헤란로",2,,"1168010100100010002000002","","서울특별시 강남구 테헤란로 2","135080","06232","","","",,
"TEST0000000000000000003","무지점무행정동","","G2","소매","G205","식료품 소매","G20501","슈퍼마켓","G47122","기타 음·식료품 위주 종합 소매업","11","서울특별시","11680","강남구","","","1168010100","역삼동","1168010100100010003","1","대지",1,3,"서울특별시 강남구 역삼동 1-3","116804100001","서울특별시 강남구 테헤란로",3,,"1168010100100010003000003","","서울특별시 강남구 테헤란로 3","135080","06232","","","",127.0300,37.5000
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/importer/StoreCsvParserTest.java`:

```java
package com.example.commercialarea.importer;

import com.example.commercialarea.store.Store;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreCsvParserTest {

    private final StoreCsvParser parser = new StoreCsvParser();

    private List<CSVRecord> records() throws Exception {
        try (Reader reader = new InputStreamReader(
                getClass().getResourceAsStream("/fixtures/sample-stores.csv"), StandardCharsets.UTF_8);
             CSVParser csv = new CSVParser(reader, StoreCsvParser.FORMAT)) {
            return new ArrayList<>(csv.getRecords());
        }
    }

    @Test
    void 헤더_다음_첫_행의_모든_필드를_매핑한다() throws Exception {
        Store s = parser.parse(records().get(0));

        assertThat(s.storeId()).isNotBlank();
        assertThat(s.storeName()).isNotBlank();
        assertThat(s.largeCode()).isNotBlank();
        assertThat(s.largeName()).isNotBlank();
        assertThat(s.sidoName()).isEqualTo("서울특별시");
        assertThat(s.sggName()).isNotBlank();
        assertThat(s.lat()).isBetween(37.0, 38.0);
        assertThat(s.lon()).isBetween(126.0, 128.0);
    }

    @Test
    void 상호명에_콤마가_있어도_한_필드로_파싱한다() throws Exception {
        Store s = findById(records(), "TEST0000000000000000001");

        assertThat(s.storeName()).isEqualTo("가나다, 라마바");
        assertThat(s.lat()).isEqualTo(37.4979);
        assertThat(s.lon()).isEqualTo(127.0276);
        assertThat(s.floorInfo()).isEqualTo("2");
        assertThat(s.buildingName()).isEqualTo("테스트빌딩");
    }

    @Test
    void 빈_문자열_필드는_null로_변환한다() throws Exception {
        Store s = findById(records(), "TEST0000000000000000003");

        assertThat(s.branchName()).isNull();
        assertThat(s.dongCode()).isNull();
        assertThat(s.dongName()).isNull();
        assertThat(s.buildingName()).isNull();
        assertThat(s.floorInfo()).isNull();
    }

    @Test
    void 좌표가_비어_있으면_MalformedRowException을_던진다() throws Exception {
        CSVRecord broken = records().stream()
                .filter(r -> r.get("상가업소번호").equals("TEST0000000000000000002"))
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> parser.parse(broken))
                .isInstanceOf(MalformedRowException.class)
                .hasMessageContaining("좌표");
    }

    @Test
    void 헤더_이름으로_매핑하므로_컬럼이_39개다() throws Exception {
        assertThat(records().get(0).getParser().getHeaderNames()).hasSize(39);
    }

    private Store findById(List<CSVRecord> records, String id) {
        return records.stream()
                .filter(r -> r.get("상가업소번호").equals(id))
                .map(parser::parse)
                .findFirst().orElseThrow();
    }
}
```

- [ ] **Step 3: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'StoreCsvParserTest'
```

Expected: FAIL — `cannot find symbol: class Store` / `class StoreCsvParser`.

- [ ] **Step 4: `Store` 레코드 작성**

`src/main/java/com/example/commercialarea/store/Store.java`:

```java
package com.example.commercialarea.store;

public record Store(
        String storeId,
        String storeName,
        String branchName,
        String largeCode,
        String largeName,
        String mediumCode,
        String mediumName,
        String smallCode,
        String smallName,
        String sidoCode,
        String sidoName,
        String sggCode,
        String sggName,
        String dongCode,
        String dongName,
        String lotAddress,
        String buildingName,
        String roadAddress,
        String floorInfo,
        double lon,
        double lat
) {
}
```

좌표를 `BigDecimal`이 아니라 `double`로 두는 이유: `DECIMAL(10,7)`은 유효숫자 10자리인데 `double`은 15~17자리를 표현하므로 정밀도 손실이 없고, 격자 계산(`FLOOR(lat/cell)`)과 JSON 직렬화가 훨씬 간단하다.

- [ ] **Step 5: `MalformedRowException` 작성**

`src/main/java/com/example/commercialarea/importer/MalformedRowException.java`:

```java
package com.example.commercialarea.importer;

public class MalformedRowException extends RuntimeException {
    public MalformedRowException(String message) {
        super(message);
    }
}
```

- [ ] **Step 6: `StoreCsvParser` 작성**

`src/main/java/com/example/commercialarea/importer/StoreCsvParser.java`:

```java
package com.example.commercialarea.importer;

import com.example.commercialarea.store.Store;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

public class StoreCsvParser {

    /**
     * 이 CSV는 문자열 필드만 따옴표로 감싸고 숫자 필드는 감싸지 않는 혼합 형식이다.
     * 따라서 문자열 분리가 아니라 RFC4180 파서를 써야 한다.
     * 헤더 이름으로 매핑하므로 시도별 파일 간 컬럼 순서가 달라도 안전하다.
     */
    public static final CSVFormat FORMAT = CSVFormat.Builder.create(CSVFormat.DEFAULT)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .build();

    public Store parse(CSVRecord r) {
        return new Store(
                required(r, "상가업소번호"),
                required(r, "상호명"),
                nullable(r, "지점명"),
                required(r, "상권업종대분류코드"),
                required(r, "상권업종대분류명"),
                required(r, "상권업종중분류코드"),
                required(r, "상권업종중분류명"),
                required(r, "상권업종소분류코드"),
                required(r, "상권업종소분류명"),
                required(r, "시도코드"),
                required(r, "시도명"),
                required(r, "시군구코드"),
                required(r, "시군구명"),
                nullable(r, "행정동코드"),
                nullable(r, "행정동명"),
                nullable(r, "지번주소"),
                nullable(r, "건물명"),
                nullable(r, "도로명주소"),
                nullable(r, "층정보"),
                coordinate(r, "경도"),
                coordinate(r, "위도")
        );
    }

    private static String required(CSVRecord r, String column) {
        String v = value(r, column);
        if (v == null) {
            throw new MalformedRowException("필수 항목 누락: " + column);
        }
        return v;
    }

    private static String nullable(CSVRecord r, String column) {
        return value(r, column);
    }

    private static double coordinate(CSVRecord r, String column) {
        String v = value(r, column);
        if (v == null) {
            throw new MalformedRowException("좌표 누락: " + column);
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new MalformedRowException("좌표 형식 오류: " + column + "=" + v);
        }
    }

    private static String value(CSVRecord r, String column) {
        if (!r.isMapped(column)) {
            return null;
        }
        String v = r.get(column);
        return (v == null || v.isBlank()) ? null : v;
    }
}
```

- [ ] **Step 7: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'StoreCsvParserTest'
```

Expected: PASS (5개 테스트).

- [ ] **Step 8: `schema.sql` 작성**

`src/main/resources/schema.sql`:

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
  building_name VARCHAR(255),
  road_address  VARCHAR(255),
  floor_info    VARCHAR(30),
  lon           DECIMAL(10,7) NOT NULL,
  lat           DECIMAL(10,7) NOT NULL,
  PRIMARY KEY (store_id),
  KEY idx_geo       (lat, lon),
  KEY idx_sgg_geo   (sgg_code,   lat, lon),
  KEY idx_dong_geo  (dong_code,  lat, lon),
  KEY idx_large_geo (large_code, lat, lon),
  KEY idx_small_geo (small_code, lat, lon),
  KEY idx_name      (store_name(20))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS industry (
  large_code  VARCHAR(10)  NOT NULL,
  large_name  VARCHAR(100) NOT NULL,
  medium_code VARCHAR(10)  NOT NULL,
  medium_name VARCHAR(100) NOT NULL,
  small_code  VARCHAR(10)  NOT NULL,
  small_name  VARCHAR(100) NOT NULL,
  store_count INT          NOT NULL,
  PRIMARY KEY (large_code, medium_code, small_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

- [ ] **Step 9: 전체 빌드**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: 커밋**

```bash
git add -A
git commit -m "feat: 스키마와 CSV 파서 추가

CSV가 혼합 따옴표 형식(문자열은 따옴표, 숫자는 무따옴표)이라
RFC4180 파서를 쓰고 헤더 이름으로 매핑한다.
StoreCsvParser는 I/O를 갖지 않아 문자열만으로 테스트할 수 있다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Testcontainers 환경과 배치 인서트

**Files:**
- Create: `src/test/java/com/example/commercialarea/support/MySqlTestContainer.java`
- Create: `src/main/java/com/example/commercialarea/store/StoreRepository.java`
- Test: `src/test/java/com/example/commercialarea/store/StoreRepositoryInsertTest.java`

**Interfaces:**
- Consumes: `Store` (Task 2), `schema.sql` (Task 2).
- Produces:
  - `MySqlTestContainer` — 이후 모든 DB 테스트가 `@Import(MySqlTestContainer.class)`로 재사용하는 테스트 설정.
  - `StoreRepository.insertBatch(List<Store>) → int` (실제 적재 시도 건수 반환)
  - `StoreRepository.countAll() → long`

- [ ] **Step 1: Testcontainers 설정 클래스 작성**

`src/test/java/com/example/commercialarea/support/MySqlTestContainer.java`:

```java
package com.example.commercialarea.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestContainer {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>("mysql:8.4")
                .withUrlParam("rewriteBatchedStatements", "true")
                .withUrlParam("characterEncoding", "UTF-8")
                .withReuse(true);
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/store/StoreRepositoryInsertTest.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreRepositoryInsertTest {

    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
    }

    static Store store(String id, double lat, double lon) {
        return new Store(id, "가게" + id, null,
                "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                "서울특별시 강남구 역삼동 1-1", null, "서울특별시 강남구 테헤란로 1", null,
                lon, lat);
    }

    @Test
    void 배치로_적재하면_전부_저장된다() {
        List<Store> batch = List.of(
                store("A1", 37.50, 127.02),
                store("A2", 37.51, 127.03),
                store("A3", 37.52, 127.04));

        repository.insertBatch(batch);

        assertThat(repository.countAll()).isEqualTo(3);
    }

    @Test
    void 같은_배치를_다시_적재해도_중복되지_않는다() {
        List<Store> batch = List.of(store("B1", 37.50, 127.02), store("B2", 37.51, 127.03));

        repository.insertBatch(batch);
        repository.insertBatch(batch);

        assertThat(repository.countAll()).isEqualTo(2);
    }

    @Test
    void 한글과_좌표가_정확히_저장된다() {
        repository.insertBatch(List.of(store("C1", 37.4979, 127.0276)));

        var row = jdbc.sql("SELECT store_name, lat, lon FROM store WHERE store_id = 'C1'")
                .query((rs, n) -> new double[]{rs.getDouble("lat"), rs.getDouble("lon")})
                .single();

        assertThat(row[0]).isEqualTo(37.4979);
        assertThat(row[1]).isEqualTo(127.0276);
        assertThat(jdbc.sql("SELECT store_name FROM store WHERE store_id = 'C1'")
                .query(String.class).single()).isEqualTo("가게C1");
    }
}
```

- [ ] **Step 3: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'StoreRepositoryInsertTest'
```

Expected: FAIL — `cannot find symbol: class StoreRepository`.

- [ ] **Step 4: `StoreRepository` 작성 (인서트 부분만)**

`src/main/java/com/example/commercialarea/store/StoreRepository.java`:

```java
package com.example.commercialarea.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
public class StoreRepository {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO store (
              store_id, store_name, branch_name,
              large_code, large_name, medium_code, medium_name, small_code, small_name,
              sido_code, sido_name, sgg_code, sgg_name, dong_code, dong_name,
              lot_address, building_name, road_address, floor_info, lon, lat
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public StoreRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** INSERT IGNORE 이므로 재실행해도 중복이 생기지 않는다. */
    public int insertBatch(List<Store> stores) {
        jdbcTemplate.batchUpdate(INSERT_SQL, stores, stores.size(), StoreRepository::bind);
        return stores.size();
    }

    private static void bind(PreparedStatement ps, Store s) throws java.sql.SQLException {
        ps.setString(1, s.storeId());
        ps.setString(2, s.storeName());
        ps.setString(3, s.branchName());
        ps.setString(4, s.largeCode());
        ps.setString(5, s.largeName());
        ps.setString(6, s.mediumCode());
        ps.setString(7, s.mediumName());
        ps.setString(8, s.smallCode());
        ps.setString(9, s.smallName());
        ps.setString(10, s.sidoCode());
        ps.setString(11, s.sidoName());
        ps.setString(12, s.sggCode());
        ps.setString(13, s.sggName());
        ps.setString(14, s.dongCode());
        ps.setString(15, s.dongName());
        ps.setString(16, s.lotAddress());
        ps.setString(17, s.buildingName());
        ps.setString(18, s.roadAddress());
        ps.setString(19, s.floorInfo());
        ps.setDouble(20, s.lon());
        ps.setDouble(21, s.lat());
    }

    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM store").query(Long.class).single();
    }
}
```

- [ ] **Step 5: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'StoreRepositoryInsertTest'
```

Expected: PASS (3개). 첫 실행은 MySQL 이미지 pull 때문에 1~2분 걸릴 수 있다.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat: Testcontainers 환경과 배치 인서트 추가

INSERT IGNORE로 재실행 멱등성을 확보한다.
rewriteBatchedStatements=true가 없으면 배치가 단건 인서트로
전송되므로 테스트 컨테이너 URL에도 동일하게 설정한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: 적재 러너와 룩업 테이블 생성

**Files:**
- Create: `src/main/java/com/example/commercialarea/config/AppProperties.java`
- Create: `src/main/java/com/example/commercialarea/importer/LookupBuilder.java`
- Create: `src/main/java/com/example/commercialarea/importer/StoreImportRunner.java`
- Test: `src/test/java/com/example/commercialarea/importer/StoreImportRunnerTest.java`

**Interfaces:**
- Consumes: `StoreCsvParser`, `StoreRepository.insertBatch/countAll`, `Store`.
- Produces:
  - `AppProperties.Import` — `enabled`, `dir`, `include`, `batchSize`
  - `StoreImportRunner.importAll() → ImportSummary`
  - `record ImportSummary(long succeeded, long failed)`
  - `LookupBuilder.rebuild()` — `region`/`industry` 재생성

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/importer/StoreImportRunnerTest.java`:

```java
package com.example.commercialarea.importer;

import com.example.commercialarea.store.StoreRepository;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreImportRunnerTest {

    @Autowired StoreImportRunner runner;
    @Autowired StoreRepository storeRepository;
    @Autowired LookupBuilder lookupBuilder;
    @Autowired JdbcClient jdbc;

    @TempDir Path csvDir;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
        jdbc.sql("DELETE FROM region").update();
        jdbc.sql("DELETE FROM industry").update();
    }

    private void writeFixture(String fileName) throws IOException {
        byte[] content = getClass().getResourceAsStream("/fixtures/sample-stores.csv").readAllBytes();
        Files.write(csvDir.resolve(fileName), content);
    }

    @Test
    void 디렉토리의_CSV를_적재하고_깨진_행은_건너뛴다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");

        ImportSummary summary = runner.importFrom(csvDir);

        // 픽스처 6행 중 좌표 없는 1행은 실패한다.
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.succeeded()).isEqualTo(5);
        assertThat(storeRepository.countAll()).isEqualTo(5);
    }

    @Test
    void include_패턴에_맞지_않는_파일은_건너뛴다() throws Exception {
        writeFixture("소상공인_제주_202606.csv");

        ImportSummary summary = runner.importFrom(csvDir);

        assertThat(summary.succeeded()).isZero();
        assertThat(storeRepository.countAll()).isZero();
    }

    @Test
    void 두_번_적재해도_행이_중복되지_않는다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");

        runner.importFrom(csvDir);
        runner.importFrom(csvDir);

        assertThat(storeRepository.countAll()).isEqualTo(5);
    }

    @Test
    void 룩업_테이블에_건수와_bbox가_채워진다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        runner.importFrom(csvDir);

        lookupBuilder.rebuild();

        long regionTotal = jdbc.sql("SELECT SUM(store_count) FROM region").query(Long.class).single();
        long industryTotal = jdbc.sql("SELECT SUM(store_count) FROM industry").query(Long.class).single();
        assertThat(regionTotal).isEqualTo(5);
        assertThat(industryTotal).isEqualTo(5);

        var bbox = jdbc.sql("""
                SELECT MIN(min_lat) AS a, MAX(max_lat) AS b, MIN(min_lon) AS c, MAX(max_lon) AS d
                FROM region
                """).query((rs, n) -> new double[]{
                        rs.getDouble("a"), rs.getDouble("b"), rs.getDouble("c"), rs.getDouble("d")})
                .single();

        assertThat(bbox[0]).isLessThanOrEqualTo(bbox[1]);
        assertThat(bbox[2]).isLessThanOrEqualTo(bbox[3]);
        assertThat(bbox[0]).isBetween(37.0, 38.0);
        assertThat(bbox[2]).isBetween(126.0, 128.0);
    }

    @Test
    void 행정동이_없는_행도_룩업에_포함된다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        runner.importFrom(csvDir);

        lookupBuilder.rebuild();

        // dong_code가 NULL인 행은 빈 문자열로 정규화되어 PK 제약을 만족한다.
        long emptyDong = jdbc.sql("SELECT COUNT(*) FROM region WHERE dong_code = ''")
                .query(Long.class).single();
        assertThat(emptyDong).isEqualTo(1);
    }
}
```

`src/test/resources/application.properties`에 테스트용 include 패턴을 둔다:

```properties
app.import.include=서울,경기
app.import.batch-size=100
app.import.dir=/tmp/unused
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'StoreImportRunnerTest'
```

Expected: FAIL — `cannot find symbol: class StoreImportRunner`.

- [ ] **Step 3: `AppProperties` 작성**

`src/main/java/com/example/commercialarea/config/AppProperties.java`:

```java
package com.example.commercialarea.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Import importSettings) {

    public record Import(boolean enabled, String dir, List<String> include, int batchSize) {
        public Import {
            if (include == null) {
                include = List.of();
            }
            if (batchSize <= 0) {
                batchSize = 1000;
            }
        }

        /** include가 비어 있으면 모든 CSV를 적재한다. */
        public boolean matches(String fileName) {
            if (include.isEmpty()) {
                return true;
            }
            return include.stream().anyMatch(fileName::contains);
        }
    }
}
```

`app.import`는 `import`가 Java 예약어라 필드명을 `importSettings`로 두고 바인딩 이름을 맞춘다. `@ConfigurationProperties` 레코드 바인딩은 생성자 파라미터명을 쓰므로, 아래처럼 명시적으로 이름을 지정한다:

```java
package com.example.commercialarea.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(@Name("import") Import importSettings) {
    // ... 위 Import 레코드 동일
}
```

**두 번째 버전을 사용한다.** 첫 번째는 `app.importSettings`로 바인딩되어 설정 키가 어긋난다.

- [ ] **Step 4: `LookupBuilder` 작성**

`src/main/java/com/example/commercialarea/importer/LookupBuilder.java`:

```java
package com.example.commercialarea.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LookupBuilder {

    private static final Logger log = LoggerFactory.getLogger(LookupBuilder.class);

    private final JdbcClient jdbc;

    public LookupBuilder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 드롭다운이 122만 행에 SELECT DISTINCT를 돌리지 않도록 룩업 테이블을 만든다.
     * region에 bbox를 함께 담아, 지역 선택 시 해당 구역으로 지도를 이동시킨다.
     */
    @Transactional
    public void rebuild() {
        long started = System.currentTimeMillis();

        jdbc.sql("DELETE FROM region").update();
        jdbc.sql("""
                INSERT INTO region (sido_code, sido_name, sgg_code, sgg_name, dong_code, dong_name,
                                    store_count, min_lat, max_lat, min_lon, max_lon)
                SELECT sido_code, sido_name, sgg_code, sgg_name,
                       COALESCE(dong_code, ''), COALESCE(dong_name, ''),
                       COUNT(*), MIN(lat), MAX(lat), MIN(lon), MAX(lon)
                FROM store
                GROUP BY sido_code, sido_name, sgg_code, sgg_name,
                         COALESCE(dong_code, ''), COALESCE(dong_name, '')
                """).update();

        jdbc.sql("DELETE FROM industry").update();
        jdbc.sql("""
                INSERT INTO industry (large_code, large_name, medium_code, medium_name,
                                      small_code, small_name, store_count)
                SELECT large_code, large_name, medium_code, medium_name,
                       small_code, small_name, COUNT(*)
                FROM store
                GROUP BY large_code, large_name, medium_code, medium_name, small_code, small_name
                """).update();

        log.info("룩업 테이블 재생성 완료 ({}ms)", System.currentTimeMillis() - started);
    }
}
```

- [ ] **Step 5: `ImportSummary`와 `StoreImportRunner` 작성**

`src/main/java/com/example/commercialarea/importer/ImportSummary.java`:

```java
package com.example.commercialarea.importer;

public record ImportSummary(long succeeded, long failed) {
    public long total() {
        return succeeded + failed;
    }

    public double failureRate() {
        return total() == 0 ? 0.0 : (double) failed / total();
    }
}
```

`src/main/java/com/example/commercialarea/importer/StoreImportRunner.java`:

```java
package com.example.commercialarea.importer;

import com.example.commercialarea.config.AppProperties;
import com.example.commercialarea.store.Store;
import com.example.commercialarea.store.StoreRepository;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class StoreImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreImportRunner.class);
    private static final long PROGRESS_INTERVAL = 100_000;

    private final AppProperties.Import settings;
    private final StoreCsvParser parser = new StoreCsvParser();
    private final StoreRepository repository;
    private final LookupBuilder lookupBuilder;

    public StoreImportRunner(AppProperties properties, StoreRepository repository, LookupBuilder lookupBuilder) {
        this.settings = properties.importSettings();
        this.repository = repository;
        this.lookupBuilder = lookupBuilder;
    }

    /**
     * 부팅 직후 실행된다. 웹 서버는 이미 요청을 받는 상태이므로 적재가
     * 화면 노출을 막지 않는다. 적재 중에도 지도는 뜨고 점이 점차 채워진다.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!settings.enabled()) {
            log.info("CSV 적재 비활성화 (app.import.enabled=false)");
            return;
        }
        if (repository.countAll() > 0) {
            log.info("store 테이블에 데이터가 이미 있어 적재를 건너뛴다");
            return;
        }
        ImportSummary summary = importFrom(Path.of(settings.dir()));
        if (summary.succeeded() > 0) {
            lookupBuilder.rebuild();
        }
    }

    public ImportSummary importFrom(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("CSV 디렉토리가 없다: {}", dir);
            return new ImportSummary(0, 0);
        }

        long started = System.currentTimeMillis();
        long succeeded = 0;
        long failed = 0;

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> targets = files
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .filter(p -> settings.matches(p.getFileName().toString()))
                    .sorted()
                    .toList();

            log.info("적재 대상 파일 {}개", targets.size());
            for (Path file : targets) {
                ImportSummary one = importFile(file);
                succeeded += one.succeeded();
                failed += one.failed();
            }
        } catch (IOException e) {
            throw new IllegalStateException("CSV 디렉토리 탐색 실패: " + dir, e);
        }

        ImportSummary total = new ImportSummary(succeeded, failed);
        log.info("전체 적재 완료: 성공 {} / 실패 {} ({}초)",
                succeeded, failed, (System.currentTimeMillis() - started) / 1000);
        return total;
    }

    private ImportSummary importFile(Path file) {
        log.info("적재 시작: {}", file.getFileName());
        long started = System.currentTimeMillis();
        long succeeded = 0;
        long failed = 0;
        List<Store> buffer = new ArrayList<>(settings.batchSize());

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser csv = new CSVParser(reader, StoreCsvParser.FORMAT)) {

            for (CSVRecord record : csv) {
                try {
                    buffer.add(parser.parse(record));
                } catch (MalformedRowException e) {
                    failed++;
                    if (failed <= 20) {
                        log.warn("행 건너뜀 ({}행): {}", record.getRecordNumber(), e.getMessage());
                    }
                    continue;
                }
                if (buffer.size() >= settings.batchSize()) {
                    succeeded += flush(buffer);
                    if (succeeded % PROGRESS_INTERVAL < settings.batchSize()) {
                        log.info("  {} 진행: {}행 ({}초)", file.getFileName(), succeeded,
                                (System.currentTimeMillis() - started) / 1000);
                    }
                }
            }
            succeeded += flush(buffer);
        } catch (IOException e) {
            throw new IllegalStateException("CSV 읽기 실패: " + file, e);
        }

        ImportSummary summary = new ImportSummary(succeeded, failed);
        if (summary.failureRate() > 0.01) {
            log.error("{} 실패율 {}%가 1%를 초과한다. CSV 형식이 바뀌었을 수 있다.",
                    file.getFileName(), Math.round(summary.failureRate() * 1000) / 10.0);
        }
        log.info("적재 완료: {} — 성공 {} / 실패 {} ({}초)", file.getFileName(),
                succeeded, failed, (System.currentTimeMillis() - started) / 1000);
        return summary;
    }

    private int flush(List<Store> buffer) {
        if (buffer.isEmpty()) {
            return 0;
        }
        int n = repository.insertBatch(buffer);
        buffer.clear();
        return n;
    }
}
```

- [ ] **Step 6: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'StoreImportRunnerTest'
```

Expected: PASS (5개).

- [ ] **Step 7: 실제 데이터로 적재 검증**

```bash
cp .env.example .env
docker compose up -d --build
docker compose logs -f app
```

Expected: `적재 대상 파일 2개` → 파일별 진행 로그 → `전체 적재 완료: 성공 1226772 / 실패 0`.

행 수를 직접 확인한다:

```bash
docker compose exec mysql mysql -uapp -papp commercial_area \
  -e "SELECT COUNT(*) FROM store; SELECT COUNT(*) FROM region; SELECT COUNT(*) FROM industry;"
```

Expected: `store` = 1,226,772 (±소수의 중복 상가번호). `region`, `industry`는 0보다 큼.

**소요 시간을 기록한다.** 스펙 §12의 "2~4분" 가정을 검증하는 지점이다. 10분을 넘으면 `app.import.batch-size`를 5000으로 올려 재측정한다.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat: CSV 적재 러너와 룩업 테이블 생성 추가

부팅 직후 ApplicationRunner로 적재하므로 웹 서버를 블로킹하지 않는다.
깨진 행은 건너뛰고 실패율이 1%를 넘으면 ERROR로 알린다.
드롭다운이 122만 행을 스캔하지 않도록 region/industry를 미리 만든다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: 격자 집계 쿼리와 공유 필터 SQL

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/MapQuery.java`
- Create: `src/main/java/com/example/commercialarea/store/GridCell.java`
- Create: `src/main/java/com/example/commercialarea/store/StoreFilterSql.java`
- Modify: `src/main/java/com/example/commercialarea/store/StoreRepository.java`
- Test: `src/test/java/com/example/commercialarea/store/StoreAggregateTest.java`

**Interfaces:**
- Consumes: `Store`, `StoreRepository.insertBatch` (Task 3).
- Produces:
  - `record MapQuery(double minLat, double maxLat, double minLon, double maxLon, int zoom, String sido, String sgg, String dong, String large, String medium, String small, String q)`
  - `record GridCell(double lat, double lon, long count)`
  - `StoreFilterSql.appendWhere(StringBuilder, Map<String,Object>, MapQuery)` — **집계·점·목록·카운트 4개 쿼리가 공유**
  - `StoreRepository.aggregate(MapQuery, double cell) → List<GridCell>`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/store/StoreAggregateTest.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreAggregateTest {

    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    /** 서울 강남 일대를 넉넉히 덮는 bbox. */
    private static MapQuery seoul() {
        return new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, null);
    }

    private static Store store(String id, double lat, double lon,
                               String sggCode, String sggName,
                               String largeCode, String largeName,
                               String name) {
        return new Store(id, name, null,
                largeCode, largeName, largeCode + "01", "중분류", largeCode + "0101", "소분류",
                "11", "서울특별시", sggCode, sggName, sggCode + "510", "역삼1동",
                "지번주소", null, "도로명주소", null,
                lon, lat);
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        repository.insertBatch(List.of(
                store("S1", 37.5000, 127.0000, "11680", "강남구", "I2", "음식", "한밥"),
                store("S2", 37.5001, 127.0001, "11680", "강남구", "I2", "음식", "한솥"),
                store("S3", 37.5002, 127.0002, "11680", "강남구", "G2", "소매", "가나슈퍼"),
                store("S4", 37.8000, 127.5000, "11110", "종로구", "I2", "음식", "한우촌"),
                // bbox 밖 (제주)
                store("S5", 33.4000, 126.5000, "50110", "제주시", "I2", "음식", "제주국수")
        ));
    }

    @Test
    void bbox_밖의_행은_집계에서_제외된다() {
        List<GridCell> cells = repository.aggregate(seoul(), 0.1);

        long total = cells.stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(4);   // S5 제외
    }

    @Test
    void 셀_건수의_합이_전체_건수와_일치한다() {
        for (double cell : new double[]{0.5, 0.1, 0.01, 0.001}) {
            long total = repository.aggregate(seoul(), cell).stream()
                    .mapToLong(GridCell::count).sum();
            assertThat(total).as("cell=%s", cell).isEqualTo(4);
        }
    }

    @Test
    void 가까운_점들은_큰_셀에서_하나로_묶인다() {
        List<GridCell> cells = repository.aggregate(seoul(), 0.1);

        // S1,S2,S3은 37.500x/127.000x 로 같은 셀, S4는 다른 셀
        assertThat(cells).hasSize(2);
        assertThat(cells).anyMatch(c -> c.count() == 3);
        assertThat(cells).anyMatch(c -> c.count() == 1);
    }

    @Test
    void 셀_대표좌표는_셀_중심이_아니라_무게중심이다() {
        GridCell cell = repository.aggregate(seoul(), 0.1).stream()
                .filter(c -> c.count() == 3).findFirst().orElseThrow();

        // (37.5000+37.5001+37.5002)/3 = 37.5001
        assertThat(cell.lat()).isCloseTo(37.5001, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cell.lon()).isCloseTo(127.0001, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void 시군구_필터가_적용된다() {
        MapQuery q = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, "11680", null, null, null, null, null);

        long total = repository.aggregate(q, 0.1).stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(3);
    }

    @Test
    void 업종_대분류_필터가_적용된다() {
        MapQuery q = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, "I2", null, null, null);

        long total = repository.aggregate(q, 0.1).stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(3);   // S1,S2,S4
    }

    @Test
    void 지역과_업종_필터를_함께_걸면_교집합이_된다() {
        MapQuery q = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, "11680", null, "I2", null, null, null);

        long total = repository.aggregate(q, 0.1).stream().mapToLong(GridCell::count).sum();
        assertThat(total).isEqualTo(2);   // S1,S2
    }

    @Test
    void 상호명_앞부분_일치로_검색한다() {
        MapQuery prefix = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, "한");
        MapQuery middle = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, "밥");

        assertThat(repository.aggregate(prefix, 0.1).stream().mapToLong(GridCell::count).sum())
                .isEqualTo(3);   // 한밥, 한솥, 한우촌
        assertThat(repository.aggregate(middle, 0.1).stream().mapToLong(GridCell::count).sum())
                .isZero();       // 앞부분 일치만 지원한다
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'StoreAggregateTest'
```

Expected: FAIL — `cannot find symbol: class MapQuery`.

- [ ] **Step 3: `MapQuery`와 `GridCell` 작성**

`src/main/java/com/example/commercialarea/store/MapQuery.java`:

```java
package com.example.commercialarea.store;

public record MapQuery(
        double minLat, double maxLat,
        double minLon, double maxLon,
        int zoom,
        String sido, String sgg, String dong,
        String large, String medium, String small,
        String q
) {
    /** 격자 크기는 전역 원점에 고정한다. 뷰포트를 N등분하면 패닝할 때 클러스터가 튄다. */
    public double cellSize() {
        return 360.0 / Math.pow(2, zoom + 3);
    }
}
```

`src/main/java/com/example/commercialarea/store/GridCell.java`:

```java
package com.example.commercialarea.store;

public record GridCell(double lat, double lon, long count) {
}
```

- [ ] **Step 4: `StoreFilterSql` 작성**

`src/main/java/com/example/commercialarea/store/StoreFilterSql.java`:

```java
package com.example.commercialarea.store;

import java.util.Map;

/**
 * 집계·점 조회·목록·카운트 네 쿼리가 동일한 필터 조건을 써야 한다.
 * 각자 조립하면 필터를 하나 추가할 때 네 군데를 고쳐야 하고 반드시 어긋난다.
 */
final class StoreFilterSql {

    private StoreFilterSql() {
    }

    static void appendWhere(StringBuilder sql, Map<String, Object> params, MapQuery q) {
        sql.append(" WHERE lat BETWEEN :minLat AND :maxLat")
           .append(" AND lon BETWEEN :minLon AND :maxLon");
        params.put("minLat", q.minLat());
        params.put("maxLat", q.maxLat());
        params.put("minLon", q.minLon());
        params.put("maxLon", q.maxLon());

        eq(sql, params, "sido_code", "sido", q.sido());
        eq(sql, params, "sgg_code", "sgg", q.sgg());
        eq(sql, params, "dong_code", "dong", q.dong());
        eq(sql, params, "large_code", "large", q.large());
        eq(sql, params, "medium_code", "medium", q.medium());
        eq(sql, params, "small_code", "small", q.small());

        if (hasText(q.q())) {
            // idx_name(store_name(20)) 을 쓰려면 앞부분 일치여야 한다.
            sql.append(" AND store_name LIKE :namePrefix");
            params.put("namePrefix", q.q().trim() + "%");
        }
    }

    private static void eq(StringBuilder sql, Map<String, Object> params,
                           String column, String param, String value) {
        if (hasText(value)) {
            sql.append(" AND ").append(column).append(" = :").append(param);
            params.put(param, value.trim());
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
```

- [ ] **Step 5: `StoreRepository`에 집계 메서드 추가**

`StoreRepository.java`의 `countAll()` 아래에 추가한다:

```java
    public List<GridCell> aggregate(MapQuery query, double cell) {
        StringBuilder sql = new StringBuilder("""
                SELECT FLOOR(lat / :cell) AS gy,
                       FLOOR(lon / :cell) AS gx,
                       COUNT(*) AS cnt,
                       AVG(lat) AS clat,
                       AVG(lon) AS clon
                FROM store
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("cell", cell);
        StoreFilterSql.appendWhere(sql, params, query);
        sql.append(" GROUP BY gy, gx");

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new GridCell(
                        rs.getDouble("clat"),
                        rs.getDouble("clon"),
                        rs.getLong("cnt")))
                .list();
    }
```

import에 `java.util.HashMap`, `java.util.Map`을 추가한다.

- [ ] **Step 6: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'StoreAggregateTest'
```

Expected: PASS (8개).

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "feat: 격자 집계 쿼리와 공유 필터 SQL 추가

격자는 전역 원점에 고정해 패닝 시 클러스터가 튀지 않게 한다.
셀 대표 좌표는 중심이 아니라 AVG 무게중심을 쓴다.
셀 중심을 쓰면 해안 지역에서 원이 바다 위에 표시된다.
필터 조립은 StoreFilterSql 한 곳에만 둔다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: 점·목록·단건 조회

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/MapPoint.java`
- Create: `src/main/java/com/example/commercialarea/store/StoreSummary.java`
- Create: `src/main/java/com/example/commercialarea/store/StoreDetail.java`
- Create: `src/main/java/com/example/commercialarea/store/PageResponse.java`
- Modify: `src/main/java/com/example/commercialarea/store/StoreRepository.java`
- Test: `src/test/java/com/example/commercialarea/store/StoreQueryTest.java`

**Interfaces:**
- Consumes: `MapQuery`, `StoreFilterSql` (Task 5).
- Produces:
  - `record MapPoint(String id, String name, double lat, double lon, String large)`
  - `record StoreSummary(String id, String name, String branchName, String largeName, String mediumName, String smallName, String roadAddress, double lat, double lon)`
  - `record StoreDetail(...)` — `store` 21개 필드 전체
  - `record PageResponse<T>(int page, int size, long total, List<T> items)`
  - `StoreRepository.findPoints(MapQuery) → List<MapPoint>`
  - `StoreRepository.countFiltered(MapQuery) → long`
  - `StoreRepository.findPage(MapQuery, int page, int size) → List<StoreSummary>`
  - `StoreRepository.findById(String) → Optional<StoreDetail>`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/store/StoreQueryTest.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreQueryTest {

    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private static MapQuery seoul() {
        return new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, null);
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        List<Store> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            batch.add(new Store("Q" + i, "가게" + i, i == 0 ? "본점" : null,
                    "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "서울특별시 강남구 역삼동 1-" + i, "테스트빌딩",
                    "서울특별시 강남구 테헤란로 " + i, "2",
                    127.0 + i * 0.0001, 37.5 + i * 0.0001));
        }
        batch.add(new Store("QJEJU", "제주국수", null,
                "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                "50", "제주특별자치도", "50110", "제주시", "50110550", "일도1동",
                "제주 지번", null, "제주 도로명", null,
                126.5, 33.4));
        repository.insertBatch(batch);
    }

    @Test
    void 점_조회는_bbox_안의_행만_반환한다() {
        List<MapPoint> points = repository.findPoints(seoul());

        assertThat(points).hasSize(25);
        assertThat(points).noneMatch(p -> p.id().equals("QJEJU"));
        assertThat(points).allMatch(p -> p.large().equals("음식"));
    }

    @Test
    void 필터된_건수를_센다() {
        assertThat(repository.countFiltered(seoul())).isEqualTo(25);

        MapQuery jeju = new MapQuery(33.0, 34.0, 126.0, 127.0, 11,
                null, null, null, null, null, null, null);
        assertThat(repository.countFiltered(jeju)).isEqualTo(1);
    }

    @Test
    void 목록은_요청한_크기만큼_반환한다() {
        List<StoreSummary> page0 = repository.findPage(seoul(), 0, 20);

        assertThat(page0).hasSize(20);
        assertThat(page0.get(0).largeName()).isEqualTo("음식");
        assertThat(page0.get(0).roadAddress()).startsWith("서울특별시");
    }

    @Test
    void 마지막_페이지는_남은_만큼만_반환한다() {
        assertThat(repository.findPage(seoul(), 1, 20)).hasSize(5);
    }

    @Test
    void 범위를_넘은_페이지는_빈_목록이다() {
        assertThat(repository.findPage(seoul(), 99, 20)).isEmpty();
    }

    @Test
    void 목록_정렬은_페이지_간_안정적이다() {
        List<String> all = new ArrayList<>();
        repository.findPage(seoul(), 0, 20).forEach(s -> all.add(s.id()));
        repository.findPage(seoul(), 1, 20).forEach(s -> all.add(s.id()));

        assertThat(all).doesNotHaveDuplicates().hasSize(25);
    }

    @Test
    void 단건_조회는_전체_필드를_반환한다() {
        StoreDetail detail = repository.findById("Q0").orElseThrow();

        assertThat(detail.storeName()).isEqualTo("가게0");
        assertThat(detail.branchName()).isEqualTo("본점");
        assertThat(detail.buildingName()).isEqualTo("테스트빌딩");
        assertThat(detail.floorInfo()).isEqualTo("2");
        assertThat(detail.sggName()).isEqualTo("강남구");
        assertThat(detail.dongName()).isEqualTo("역삼1동");
        assertThat(detail.lat()).isEqualTo(37.5);
    }

    @Test
    void 없는_id는_빈_Optional이다() {
        assertThat(repository.findById("NOPE")).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'StoreQueryTest'
```

Expected: FAIL — `cannot find symbol: class MapPoint`.

- [ ] **Step 3: DTO 레코드 4개 작성**

`src/main/java/com/example/commercialarea/store/MapPoint.java`:

```java
package com.example.commercialarea.store;

/** 지도 마커용 최소 payload. 주소는 담지 않는다 — 2,000개에 주소까지 실으면 응답이 몇 배가 된다. */
public record MapPoint(String id, String name, double lat, double lon, String large) {
}
```

`src/main/java/com/example/commercialarea/store/StoreSummary.java`:

```java
package com.example.commercialarea.store;

public record StoreSummary(
        String id, String name, String branchName,
        String largeName, String mediumName, String smallName,
        String roadAddress, double lat, double lon
) {
}
```

`src/main/java/com/example/commercialarea/store/StoreDetail.java`:

```java
package com.example.commercialarea.store;

public record StoreDetail(
        String storeId, String storeName, String branchName,
        String largeCode, String largeName,
        String mediumCode, String mediumName,
        String smallCode, String smallName,
        String sidoCode, String sidoName,
        String sggCode, String sggName,
        String dongCode, String dongName,
        String lotAddress, String buildingName, String roadAddress, String floorInfo,
        double lon, double lat
) {
}
```

`src/main/java/com/example/commercialarea/store/PageResponse.java`:

```java
package com.example.commercialarea.store;

import java.util.List;

public record PageResponse<T>(int page, int size, long total, List<T> items) {
}
```

- [ ] **Step 4: `StoreRepository`에 조회 메서드 추가**

`aggregate()` 아래에 추가한다:

```java
    public List<MapPoint> findPoints(MapQuery query) {
        StringBuilder sql = new StringBuilder(
                "SELECT store_id, store_name, lat, lon, large_name FROM store");
        Map<String, Object> params = new HashMap<>();
        StoreFilterSql.appendWhere(sql, params, query);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new MapPoint(
                        rs.getString("store_id"),
                        rs.getString("store_name"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getString("large_name")))
                .list();
    }

    public long countFiltered(MapQuery query) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM store");
        Map<String, Object> params = new HashMap<>();
        StoreFilterSql.appendWhere(sql, params, query);

        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    public List<StoreSummary> findPage(MapQuery query, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT store_id, store_name, branch_name,
                       large_name, medium_name, small_name, road_address, lat, lon
                FROM store
                """);
        Map<String, Object> params = new HashMap<>();
        StoreFilterSql.appendWhere(sql, params, query);
        // store_id는 PK라 페이지 간 정렬이 안정적이다.
        sql.append(" ORDER BY store_id LIMIT :limit OFFSET :offset");
        params.put("limit", size);
        params.put("offset", (long) page * size);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new StoreSummary(
                        rs.getString("store_id"),
                        rs.getString("store_name"),
                        rs.getString("branch_name"),
                        rs.getString("large_name"),
                        rs.getString("medium_name"),
                        rs.getString("small_name"),
                        rs.getString("road_address"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon")))
                .list();
    }

    public Optional<StoreDetail> findById(String storeId) {
        return jdbc.sql("""
                        SELECT store_id, store_name, branch_name,
                               large_code, large_name, medium_code, medium_name,
                               small_code, small_name,
                               sido_code, sido_name, sgg_code, sgg_name, dong_code, dong_name,
                               lot_address, building_name, road_address, floor_info, lon, lat
                        FROM store WHERE store_id = :id
                        """)
                .param("id", storeId)
                .query((rs, n) -> new StoreDetail(
                        rs.getString("store_id"), rs.getString("store_name"), rs.getString("branch_name"),
                        rs.getString("large_code"), rs.getString("large_name"),
                        rs.getString("medium_code"), rs.getString("medium_name"),
                        rs.getString("small_code"), rs.getString("small_name"),
                        rs.getString("sido_code"), rs.getString("sido_name"),
                        rs.getString("sgg_code"), rs.getString("sgg_name"),
                        rs.getString("dong_code"), rs.getString("dong_name"),
                        rs.getString("lot_address"), rs.getString("building_name"),
                        rs.getString("road_address"), rs.getString("floor_info"),
                        rs.getDouble("lon"), rs.getDouble("lat")))
                .optional();
    }
```

import에 `java.util.Optional`을 추가한다.

- [ ] **Step 5: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'StoreQueryTest'
```

Expected: PASS (8개).

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat: 점·목록·단건 조회 쿼리 추가

마커 payload에 주소를 담지 않는다. 2,000개에 주소까지 실으면
응답이 몇 배로 커지므로 상세는 클릭 시 별도 조회한다.
목록 정렬은 PK 기준이라 페이지 간 중복·누락이 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: 오류 응답 규약과 표시 모드 결정

**Files:**
- Create: `src/main/java/com/example/commercialarea/config/ApiException.java`
- Create: `src/main/java/com/example/commercialarea/config/ErrorResponse.java`
- Create: `src/main/java/com/example/commercialarea/config/GlobalExceptionHandler.java`
- Create: `src/main/java/com/example/commercialarea/store/MapResponse.java`
- Create: `src/main/java/com/example/commercialarea/store/StoreService.java`
- Modify: `src/main/java/com/example/commercialarea/store/MapQuery.java` (validate 추가)
- Test: `src/test/java/com/example/commercialarea/store/MapQueryTest.java`
- Test: `src/test/java/com/example/commercialarea/store/StoreServiceTest.java`

**Interfaces:**
- Consumes: `StoreRepository.aggregate/findPoints/countFiltered/findPage/findById` (Task 5·6).
- Produces:
  - `ApiException(HttpStatus, String code, String message)`
  - `record ErrorResponse(String error, String message)`
  - `record MapResponse(String mode, long total, List<GridCell> cells, List<MapPoint> points)` + 팩토리 `cluster(...)`, `point(...)`
  - `MapQuery.validate()` — 실패 시 `ApiException`
  - `StoreService.POINT_THRESHOLD = 2000`
  - `StoreService.map(MapQuery) → MapResponse`
  - `StoreService.list(MapQuery, int page, int size) → PageResponse<StoreSummary>`
  - `StoreService.detail(String id) → StoreDetail` (없으면 `ApiException` 404)

- [ ] **Step 1: 격자 크기 단위 테스트 작성 (컨테이너 불필요)**

`src/test/java/com/example/commercialarea/store/MapQueryTest.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapQueryTest {

    private static MapQuery at(double minLat, double maxLat, double minLon, double maxLon, int zoom) {
        return new MapQuery(minLat, maxLat, minLon, maxLon, zoom,
                null, null, null, null, null, null, null);
    }

    @Test
    void 격자_크기는_줌이_1_커질_때마다_절반이_된다() {
        double z10 = at(37.0, 38.0, 126.0, 127.0, 10).cellSize();
        double z11 = at(37.0, 38.0, 126.0, 127.0, 11).cellSize();

        assertThat(z11).isCloseTo(z10 / 2, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void 격자_크기는_전역_원점_기준_공식을_따른다() {
        assertThat(at(37.0, 38.0, 126.0, 127.0, 0).cellSize()).isEqualTo(45.0);   // 360/8
        assertThat(at(37.0, 38.0, 126.0, 127.0, 3).cellSize()).isEqualTo(5.625);  // 360/64
    }

    @Test
    void 정상_bbox는_통과한다() {
        assertThatCode(() -> at(37.0, 38.0, 126.0, 127.0, 11).validate()).doesNotThrowAnyException();
    }

    @Test
    void 위도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(38.0, 37.0, 126.0, 127.0, 11).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }

    @Test
    void 경도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(37.0, 38.0, 127.0, 126.0, 11).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }

    @Test
    void 줌이_범위_밖이면_INVALID_ZOOM을_던진다() {
        assertThatThrownBy(() -> at(37.0, 38.0, 126.0, 127.0, 23).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ZOOM");
        assertThatThrownBy(() -> at(37.0, 38.0, 126.0, 127.0, -1).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ZOOM");
    }
}
```

- [ ] **Step 2: 모드 전환 통합 테스트 작성**

`src/test/java/com/example/commercialarea/store/StoreServiceTest.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class StoreServiceTest {

    @Autowired StoreService service;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private static MapQuery seoul() {
        return new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, null, null, null, null);
    }

    /** 강남 일대에 n개의 점을 만든다. */
    private void seed(int n, String largeCode, String largeName) {
        List<Store> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(new Store(largeCode + "-" + i, "가게" + i, null,
                    largeCode, largeName, largeCode + "01", "중분류", largeCode + "0101", "소분류",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "지번", null, "도로명", null,
                    127.0 + (i % 100) * 0.0001, 37.5 + (i / 100) * 0.0001));
        }
        repository.insertBatch(batch);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
    }

    @Test
    void 임계값_이하면_point_모드다() {
        seed(StoreService.POINT_THRESHOLD, "I2", "음식");

        MapResponse response = service.map(seoul());

        assertThat(response.mode()).isEqualTo("point");
        assertThat(response.total()).isEqualTo(StoreService.POINT_THRESHOLD);
        assertThat(response.points()).hasSize(StoreService.POINT_THRESHOLD);
        assertThat(response.cells()).isNull();
    }

    @Test
    void 임계값을_넘으면_cluster_모드다() {
        seed(StoreService.POINT_THRESHOLD + 1, "I2", "음식");

        MapResponse response = service.map(seoul());

        assertThat(response.mode()).isEqualTo("cluster");
        assertThat(response.total()).isEqualTo(StoreService.POINT_THRESHOLD + 1);
        assertThat(response.cells()).isNotEmpty();
        assertThat(response.points()).isNull();
    }

    @Test
    void cluster_모드의_셀_합계는_total과_같다() {
        seed(StoreService.POINT_THRESHOLD + 500, "I2", "음식");

        MapResponse response = service.map(seoul());

        long sum = response.cells().stream().mapToLong(GridCell::count).sum();
        assertThat(sum).isEqualTo(response.total());
    }

    @Test
    void 필터를_걸어_건수가_줄면_같은_줌에서도_point_모드로_바뀐다() {
        seed(3000, "I2", "음식");
        seed(50, "G2", "소매");

        MapQuery unfiltered = seoul();
        MapQuery filtered = new MapQuery(37.0, 38.0, 126.0, 128.0, 11,
                null, null, null, "G2", null, null, null);

        assertThat(service.map(unfiltered).mode()).isEqualTo("cluster");
        assertThat(service.map(filtered).mode()).isEqualTo("point");
        assertThat(service.map(filtered).total()).isEqualTo(50);
    }

    @Test
    void 결과가_없으면_point_모드에_빈_목록이다() {
        MapResponse response = service.map(seoul());

        assertThat(response.mode()).isEqualTo("point");
        assertThat(response.total()).isZero();
        assertThat(response.points()).isEmpty();
    }

    @Test
    void 없는_상세를_요청하면_STORE_NOT_FOUND를_던진다() {
        assertThatThrownBy(() -> service.detail("NOPE"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "STORE_NOT_FOUND");
    }
}
```

"필터를 걸어 건수가 줄면 같은 줌에서도 point 모드로 바뀐다"가 이 설계의 핵심 근거를 지키는 테스트다. 줌 임계값 방식으로 되돌리면 이 테스트가 깨진다.

- [ ] **Step 3: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'MapQueryTest' --tests 'StoreServiceTest'
```

Expected: FAIL — `cannot find symbol: class ApiException`.

- [ ] **Step 4: 오류 응답 클래스 3개 작성**

`src/main/java/com/example/commercialarea/config/ApiException.java`:

```java
package com.example.commercialarea.config;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
```

`src/main/java/com/example/commercialarea/config/ErrorResponse.java`:

```java
package com.example.commercialarea.config;

public record ErrorResponse(String error, String message) {
}
```

`src/main/java/com/example/commercialarea/config/GlobalExceptionHandler.java`:

```java
package com.example.commercialarea.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Set<String> BBOX_PARAMS = Set.of("minLat", "maxLat", "minLon", "maxLon");

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        String code = BBOX_PARAMS.contains(e.getParameterName()) ? "MISSING_BBOX" : "MISSING_PARAMETER";
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(code, "필수 파라미터 누락: " + e.getParameterName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
    }
}
```

- [ ] **Step 5: `MapQuery.validate()` 추가**

`MapQuery.java`의 `cellSize()` 아래에 추가하고, import에 `com.example.commercialarea.config.ApiException`을 넣는다:

```java
    public void validate() {
        if (minLat > maxLat || minLon > maxLon) {
            throw ApiException.badRequest("INVALID_BBOX",
                    "minLat/minLon은 maxLat/maxLon보다 클 수 없습니다.");
        }
        if (zoom < 0 || zoom > 22) {
            throw ApiException.badRequest("INVALID_ZOOM", "zoom은 0~22 범위여야 합니다.");
        }
    }
```

- [ ] **Step 6: `MapResponse` 작성**

`src/main/java/com/example/commercialarea/store/MapResponse.java`:

```java
package com.example.commercialarea.store;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapResponse(String mode, long total, List<GridCell> cells, List<MapPoint> points) {

    public static MapResponse cluster(long total, List<GridCell> cells) {
        return new MapResponse("cluster", total, cells, null);
    }

    public static MapResponse point(long total, List<MapPoint> points) {
        return new MapResponse("point", total, null, points);
    }
}
```

- [ ] **Step 7: `StoreService` 작성**

`src/main/java/com/example/commercialarea/store/StoreService.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StoreService {

    /** 이 건수 이하면 개별 마커를, 초과하면 격자 집계를 보낸다. */
    public static final int POINT_THRESHOLD = 2000;

    private final StoreRepository repository;

    public StoreService(StoreRepository repository) {
        this.repository = repository;
    }

    /**
     * 줌이 아니라 실제 건수로 표시 모드를 가른다.
     * 필터를 세게 걸면 줌이 낮아도 결과가 적으므로, 그때는 곧바로 마커를 보여주는 것이 옳다.
     * 총 건수는 집계 쿼리에서 이미 나오므로 별도 COUNT 쿼리가 필요 없다.
     */
    public MapResponse map(MapQuery query) {
        List<GridCell> cells = repository.aggregate(query, query.cellSize());
        long total = cells.stream().mapToLong(GridCell::count).sum();

        if (total <= POINT_THRESHOLD) {
            // 여기서는 결과가 2,000건 이하임이 확정이라 LIMIT이 필요 없다.
            return MapResponse.point(total, repository.findPoints(query));
        }
        return MapResponse.cluster(total, cells);
    }

    public PageResponse<StoreSummary> list(MapQuery query, int page, int size) {
        long total = repository.countFiltered(query);
        List<StoreSummary> items = total == 0 ? List.of() : repository.findPage(query, page, size);
        return new PageResponse<>(page, size, total, items);
    }

    public StoreDetail detail(String storeId) {
        return repository.findById(storeId)
                .orElseThrow(() -> ApiException.notFound("STORE_NOT_FOUND",
                        "상가업소번호를 찾을 수 없습니다: " + storeId));
    }
}
```

- [ ] **Step 8: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'MapQueryTest' --tests 'StoreServiceTest'
```

Expected: PASS (6 + 6 = 12개).

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "feat: 오류 응답 규약과 표시 모드 결정 로직 추가

표시 모드를 줌이 아니라 실제 건수(2,000)로 가른다.
필터를 세게 걸면 줌이 낮아도 결과가 적으므로 그때는 마커를 보여주는 것이 옳다.
총 건수는 집계 쿼리에서 나오므로 별도 COUNT 쿼리가 없다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: 지도 API 엔드포인트

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/MapController.java`
- Test: `src/test/java/com/example/commercialarea/store/MapApiTest.java`

**Interfaces:**
- Consumes: `StoreService.map`, `MapQuery.validate`, `GlobalExceptionHandler` (Task 7).
- Produces: `GET /api/map` — 스펙 §6.1의 응답 계약.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/store/MapApiTest.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class MapApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private void seed(int n) {
        List<Store> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(new Store("M" + i, "가게" + i, null,
                    "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "지번", null, "도로명", null,
                    127.0 + (i % 100) * 0.0001, 37.5 + (i / 100) * 0.0001));
        }
        repository.insertBatch(batch);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
    }

    private static String bbox() {
        return "?minLat=37.0&maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=11";
    }

    @Test
    void 임계값_이하면_point_모드로_응답한다() throws Exception {
        seed(StoreService.POINT_THRESHOLD);

        mvc.perform(get("/api/map" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("point"))
                .andExpect(jsonPath("$.total").value(StoreService.POINT_THRESHOLD))
                .andExpect(jsonPath("$.points.length()").value(StoreService.POINT_THRESHOLD))
                .andExpect(jsonPath("$.cells").doesNotExist());
    }

    @Test
    void 임계값을_넘으면_cluster_모드로_응답한다() throws Exception {
        seed(StoreService.POINT_THRESHOLD + 1);

        mvc.perform(get("/api/map" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("cluster"))
                .andExpect(jsonPath("$.total").value(StoreService.POINT_THRESHOLD + 1))
                .andExpect(jsonPath("$.cells").isArray())
                .andExpect(jsonPath("$.points").doesNotExist());
    }

    @Test
    void point_응답에_주소가_들어있지_않다() throws Exception {
        seed(3);

        mvc.perform(get("/api/map" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].id").exists())
                .andExpect(jsonPath("$.points[0].name").exists())
                .andExpect(jsonPath("$.points[0].lat").exists())
                .andExpect(jsonPath("$.points[0].lon").exists())
                .andExpect(jsonPath("$.points[0].large").value("음식"))
                .andExpect(jsonPath("$.points[0].roadAddress").doesNotExist());
    }

    @Test
    void bbox가_없으면_MISSING_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/map?maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_BBOX"));
    }

    @Test
    void bbox가_뒤집히면_INVALID_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/map?minLat=38.0&maxLat=37.0&minLon=126.0&maxLon=128.0&zoom=11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BBOX"));
    }

    @Test
    void 줌이_범위_밖이면_INVALID_ZOOM_400이다() throws Exception {
        mvc.perform(get("/api/map?minLat=37.0&maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=23"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ZOOM"));
    }

    @Test
    void 필터_파라미터가_반영된다() throws Exception {
        seed(10);

        mvc.perform(get("/api/map" + bbox() + "&large=G2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/map" + bbox() + "&large=I2&sgg=11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10));
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'MapApiTest'
```

Expected: FAIL — 404 (`/api/map` 핸들러 없음).

- [ ] **Step 3: `MapController` 작성**

`src/main/java/com/example/commercialarea/store/MapController.java`:

```java
package com.example.commercialarea.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MapController {

    private final StoreService service;

    public MapController(StoreService service) {
        this.service = service;
    }

    @GetMapping("/api/map")
    public MapResponse map(
            @RequestParam double minLat,
            @RequestParam double maxLat,
            @RequestParam double minLon,
            @RequestParam double maxLon,
            @RequestParam int zoom,
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sgg,
            @RequestParam(required = false) String dong,
            @RequestParam(required = false) String large,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String small,
            @RequestParam(required = false) String q) {

        MapQuery query = new MapQuery(minLat, maxLat, minLon, maxLon, zoom,
                sido, sgg, dong, large, medium, small, q);
        query.validate();
        return service.map(query);
    }
}
```

- [ ] **Step 4: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'MapApiTest'
```

Expected: PASS (7개).

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat: 지도 데이터 API 추가

GET /api/map — bbox + 선택적 필터로 집계 또는 개별 점을 반환한다.
bbox 누락/역전, 줌 범위 밖은 400과 오류 코드로 응답한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: 목록·상세 API

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/StoreController.java`
- Test: `src/test/java/com/example/commercialarea/store/StoreApiTest.java`

**Interfaces:**
- Consumes: `StoreService.list/detail` (Task 7).
- Produces: `GET /api/stores`, `GET /api/stores/{id}` — 스펙 §6.4·§6.5.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/store/StoreApiTest.java`:

```java
package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class StoreApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private static String bbox() {
        return "?minLat=37.0&maxLat=38.0&minLon=126.0&maxLon=128.0&zoom=11";
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        List<Store> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            batch.add(new Store(String.format("L%02d", i), "가게" + i, i == 0 ? "본점" : null,
                    "I2", "음식", "I201", "한식", "I20101", "백반·가정식",
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "서울특별시 강남구 역삼동 1-" + i, "테스트빌딩",
                    "서울특별시 강남구 테헤란로 " + i, "2",
                    127.0 + i * 0.0001, 37.5 + i * 0.0001));
        }
        repository.insertBatch(batch);
    }

    @Test
    void 목록은_페이징_메타와_항목을_반환한다() throws Exception {
        mvc.perform(get("/api/stores" + bbox() + "&page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].roadAddress").exists())
                .andExpect(jsonPath("$.items[0].largeName").value("음식"));
    }

    @Test
    void 마지막_페이지는_남은_만큼_반환한다() throws Exception {
        mvc.perform(get("/api/stores" + bbox() + "&page=1&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void page와_size를_생략하면_기본값을_쓴다() throws Exception {
        mvc.perform(get("/api/stores" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void 목록에도_같은_bbox_검증이_적용된다() throws Exception {
        mvc.perform(get("/api/stores?minLat=38.0&maxLat=37.0&minLon=126.0&maxLon=128.0&zoom=11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BBOX"));
    }

    @Test
    void 상세는_전체_필드를_반환한다() throws Exception {
        mvc.perform(get("/api/stores/L00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("가게0"))
                .andExpect(jsonPath("$.branchName").value("본점"))
                .andExpect(jsonPath("$.sggName").value("강남구"))
                .andExpect(jsonPath("$.dongName").value("역삼1동"))
                .andExpect(jsonPath("$.roadAddress").exists())
                .andExpect(jsonPath("$.buildingName").value("테스트빌딩"))
                .andExpect(jsonPath("$.floorInfo").value("2"));
    }

    @Test
    void 없는_상세는_STORE_NOT_FOUND_404다() throws Exception {
        mvc.perform(get("/api/stores/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("STORE_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'StoreApiTest'
```

Expected: FAIL — 404.

- [ ] **Step 3: `StoreController` 작성**

`src/main/java/com/example/commercialarea/store/StoreController.java`:

```java
package com.example.commercialarea.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StoreController {

    private static final int MAX_PAGE_SIZE = 100;

    private final StoreService service;

    public StoreController(StoreService service) {
        this.service = service;
    }

    @GetMapping("/api/stores")
    public PageResponse<StoreSummary> list(
            @RequestParam double minLat,
            @RequestParam double maxLat,
            @RequestParam double minLon,
            @RequestParam double maxLon,
            @RequestParam int zoom,
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sgg,
            @RequestParam(required = false) String dong,
            @RequestParam(required = false) String large,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String small,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        MapQuery query = new MapQuery(minLat, maxLat, minLon, maxLon, zoom,
                sido, sgg, dong, large, medium, small, q);
        query.validate();
        return service.list(query, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/stores/{id}")
    public StoreDetail detail(@PathVariable String id) {
        return service.detail(id);
    }
}
```

`Math.clamp`는 Java 21에 추가된 메서드다.

- [ ] **Step 4: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'StoreApiTest'
```

Expected: PASS (6개).

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat: 목록·상세 API 추가

목록은 지도와 동일한 bbox·필터를 쓴다.
페이지 크기는 100으로 상한을 둔다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: 지역·업종 룩업 API

**Files:**
- Create: `src/main/java/com/example/commercialarea/lookup/LookupItem.java`
- Create: `src/main/java/com/example/commercialarea/lookup/RegionRepository.java`
- Create: `src/main/java/com/example/commercialarea/lookup/IndustryRepository.java`
- Create: `src/main/java/com/example/commercialarea/lookup/LookupController.java`
- Test: `src/test/java/com/example/commercialarea/lookup/LookupApiTest.java`

**Interfaces:**
- Consumes: `LookupBuilder.rebuild()` (Task 4), `StoreRepository.insertBatch` (Task 3).
- Produces:
  - `record LookupItem(String code, String name, long count, Double minLat, Double maxLat, Double minLon, Double maxLon)` + 팩토리 `of(code, name, count)`
  - `RegionRepository.findSido() / findSgg(String sido) / findDong(String sgg)`
  - `IndustryRepository.findLarge() / findMedium(String large) / findSmall(String medium)`
  - `GET /api/regions/sido|sgg|dong`, `GET /api/industries/large|medium|small`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/lookup/LookupApiTest.java`:

```java
package com.example.commercialarea.lookup;

import com.example.commercialarea.importer.LookupBuilder;
import com.example.commercialarea.store.Store;
import com.example.commercialarea.store.StoreRepository;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class LookupApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository storeRepository;
    @Autowired LookupBuilder lookupBuilder;
    @Autowired JdbcClient jdbc;

    private static Store store(String id, String sggCode, String sggName,
                               String dongCode, String dongName,
                               String large, String largeName,
                               String medium, String mediumName,
                               String small, String smallName,
                               double lat, double lon) {
        return new Store(id, "가게" + id, null,
                large, largeName, medium, mediumName, small, smallName,
                "11", "서울특별시", sggCode, sggName, dongCode, dongName,
                "지번", null, "도로명", null, lon, lat);
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM store").update();
        storeRepository.insertBatch(List.of(
                store("R1", "11680", "강남구", "11680510", "역삼1동",
                        "I2", "음식", "I201", "한식", "I20101", "백반·가정식", 37.50, 127.02),
                store("R2", "11680", "강남구", "11680510", "역삼1동",
                        "I2", "음식", "I201", "한식", "I20102", "국수·면요리", 37.51, 127.03),
                store("R3", "11680", "강남구", "11680520", "역삼2동",
                        "G2", "소매", "G205", "식료품 소매", "G20501", "슈퍼마켓", 37.52, 127.04),
                store("R4", "11110", "종로구", "11110515", "사직동",
                        "I2", "음식", "I202", "중식", "I20201", "중식 일반", 37.57, 126.97)
        ));
        lookupBuilder.rebuild();
    }

    @Test
    void 시도_목록은_건수를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/sido"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("11"))
                .andExpect(jsonPath("$[0].name").value("서울특별시"))
                .andExpect(jsonPath("$[0].count").value(4));
    }

    @Test
    void 시군구_목록은_행정동_행을_집계하고_bbox를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/sgg?sido=11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680')].count").value(3))
                .andExpect(jsonPath("$[?(@.code=='11680')].minLat").value(37.50))
                .andExpect(jsonPath("$[?(@.code=='11680')].maxLat").value(37.52))
                .andExpect(jsonPath("$[?(@.code=='11680')].minLon").value(127.02))
                .andExpect(jsonPath("$[?(@.code=='11680')].maxLon").value(127.04));
    }

    @Test
    void 행정동_목록도_bbox를_포함한다() throws Exception {
        mvc.perform(get("/api/regions/dong?sgg=11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680510')].count").value(2))
                .andExpect(jsonPath("$[?(@.code=='11680510')].maxLat").value(37.51));
    }

    @Test
    void 업종_대분류는_중복_없이_집계된다() throws Exception {
        mvc.perform(get("/api/industries/large"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='I2')].count").value(3))
                .andExpect(jsonPath("$[?(@.code=='G2')].count").value(1));
    }

    @Test
    void 업종_중분류는_대분류로_좁혀진다() throws Exception {
        mvc.perform(get("/api/industries/medium?large=I2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='I201')].count").value(2));
    }

    @Test
    void 업종_소분류는_중분류로_좁혀진다() throws Exception {
        mvc.perform(get("/api/industries/small?medium=I201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.code=='I20101')].name").value("백반·가정식"));
    }

    @Test
    void 업종_목록에는_bbox가_없다() throws Exception {
        mvc.perform(get("/api/industries/large"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].minLat").doesNotExist());
    }
}
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'LookupApiTest'
```

Expected: FAIL — 404.

- [ ] **Step 3: `LookupItem` 작성**

`src/main/java/com/example/commercialarea/lookup/LookupItem.java`:

```java
package com.example.commercialarea.lookup;

import com.fasterxml.jackson.annotation.JsonInclude;

/** bbox는 지역 항목에만 담긴다. 업종에는 null이라 JSON에서 생략된다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LookupItem(
        String code, String name, long count,
        Double minLat, Double maxLat, Double minLon, Double maxLon
) {
    public static LookupItem of(String code, String name, long count) {
        return new LookupItem(code, name, count, null, null, null, null);
    }
}
```

- [ ] **Step 4: `RegionRepository` 작성**

`src/main/java/com/example/commercialarea/lookup/RegionRepository.java`:

```java
package com.example.commercialarea.lookup;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RegionRepository {

    private final JdbcClient jdbc;

    public RegionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** region은 행정동 단위 한 행씩이라, 상위 단계는 집계해서 만든다. */
    public List<LookupItem> findSido() {
        return jdbc.sql("""
                        SELECT sido_code AS code, sido_name AS name, SUM(store_count) AS cnt
                        FROM region
                        GROUP BY sido_code, sido_name
                        ORDER BY sido_name
                        """)
                .query((rs, n) -> LookupItem.of(rs.getString("code"), rs.getString("name"), rs.getLong("cnt")))
                .list();
    }

    public List<LookupItem> findSgg(String sidoCode) {
        return jdbc.sql("""
                        SELECT sgg_code AS code, sgg_name AS name, SUM(store_count) AS cnt,
                               MIN(min_lat) AS min_lat, MAX(max_lat) AS max_lat,
                               MIN(min_lon) AS min_lon, MAX(max_lon) AS max_lon
                        FROM region
                        WHERE sido_code = :sido
                        GROUP BY sgg_code, sgg_name
                        ORDER BY sgg_name
                        """)
                .param("sido", sidoCode)
                .query(RegionRepository::withBbox)
                .list();
    }

    public List<LookupItem> findDong(String sggCode) {
        return jdbc.sql("""
                        SELECT dong_code AS code, dong_name AS name, store_count AS cnt,
                               min_lat, max_lat, min_lon, max_lon
                        FROM region
                        WHERE sgg_code = :sgg AND dong_code <> ''
                        ORDER BY dong_name
                        """)
                .param("sgg", sggCode)
                .query(RegionRepository::withBbox)
                .list();
    }

    private static LookupItem withBbox(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LookupItem(
                rs.getString("code"), rs.getString("name"), rs.getLong("cnt"),
                rs.getDouble("min_lat"), rs.getDouble("max_lat"),
                rs.getDouble("min_lon"), rs.getDouble("max_lon"));
    }
}
```

- [ ] **Step 5: `IndustryRepository` 작성**

`src/main/java/com/example/commercialarea/lookup/IndustryRepository.java`:

```java
package com.example.commercialarea.lookup;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class IndustryRepository {

    private final JdbcClient jdbc;

    public IndustryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LookupItem> findLarge() {
        return jdbc.sql("""
                        SELECT large_code AS code, large_name AS name, SUM(store_count) AS cnt
                        FROM industry
                        GROUP BY large_code, large_name
                        ORDER BY cnt DESC
                        """)
                .query(IndustryRepository::toItem)
                .list();
    }

    public List<LookupItem> findMedium(String largeCode) {
        return jdbc.sql("""
                        SELECT medium_code AS code, medium_name AS name, SUM(store_count) AS cnt
                        FROM industry
                        WHERE large_code = :large
                        GROUP BY medium_code, medium_name
                        ORDER BY cnt DESC
                        """)
                .param("large", largeCode)
                .query(IndustryRepository::toItem)
                .list();
    }

    public List<LookupItem> findSmall(String mediumCode) {
        return jdbc.sql("""
                        SELECT small_code AS code, small_name AS name, SUM(store_count) AS cnt
                        FROM industry
                        WHERE medium_code = :medium
                        GROUP BY small_code, small_name
                        ORDER BY cnt DESC
                        """)
                .param("medium", mediumCode)
                .query(IndustryRepository::toItem)
                .list();
    }

    private static LookupItem toItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return LookupItem.of(rs.getString("code"), rs.getString("name"), rs.getLong("cnt"));
    }
}
```

- [ ] **Step 6: `LookupController` 작성**

`src/main/java/com/example/commercialarea/lookup/LookupController.java`:

```java
package com.example.commercialarea.lookup;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class LookupController {

    private final RegionRepository regions;
    private final IndustryRepository industries;

    public LookupController(RegionRepository regions, IndustryRepository industries) {
        this.regions = regions;
        this.industries = industries;
    }

    @GetMapping("/api/regions/sido")
    public List<LookupItem> sido() {
        return regions.findSido();
    }

    @GetMapping("/api/regions/sgg")
    public List<LookupItem> sgg(@RequestParam String sido) {
        return regions.findSgg(sido);
    }

    @GetMapping("/api/regions/dong")
    public List<LookupItem> dong(@RequestParam String sgg) {
        return regions.findDong(sgg);
    }

    @GetMapping("/api/industries/large")
    public List<LookupItem> large() {
        return industries.findLarge();
    }

    @GetMapping("/api/industries/medium")
    public List<LookupItem> medium(@RequestParam String large) {
        return industries.findMedium(large);
    }

    @GetMapping("/api/industries/small")
    public List<LookupItem> small(@RequestParam String medium) {
        return industries.findSmall(medium);
    }
}
```

- [ ] **Step 7: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'LookupApiTest'
```

Expected: PASS (7개).

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat: 지역·업종 룩업 API 추가

드롭다운이 122만 행을 스캔하지 않도록 룩업 테이블에서만 읽는다.
region은 행정동 단위 저장이라 시도·시군구는 집계해서 만든다.
시군구·행정동 응답에 bbox를 담아 선택 시 지도를 이동시킬 수 있게 한다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: 화면 껍데기와 지도 렌더링

**Files:**
- Create: `src/main/java/com/example/commercialarea/web/PageController.java`
- Create: `src/main/resources/templates/index.html`
- Create: `src/main/resources/static/app.css`
- Create: `src/main/resources/static/app.js`
- Test: `src/test/java/com/example/commercialarea/web/PageAndAssetTest.java`

**Interfaces:**
- Consumes: `GET /api/map` (Task 8).
- Produces: `GET /` — 지도가 렌더링되는 단일 페이지. `app.js`의 `refresh()`, `renderMap(data)`, `currentParams()` — Task 12가 필터를 얹는 지점.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/commercialarea/web/PageAndAssetTest.java`:

```java
package com.example.commercialarea.web;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.import.enabled=false")
@AutoConfigureMockMvc
@Import(MySqlTestContainer.class)
class PageAndAssetTest {

    @Autowired MockMvc mvc;

    @Test
    void 루트는_지도_컨테이너가_있는_HTML을_반환한다() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"map\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/app.js")));
    }

    @Test
    void Leaflet_에셋이_webjar에서_서빙된다() throws Exception {
        mvc.perform(get("/webjars/leaflet/dist/leaflet.js")).andExpect(status().isOk());
        mvc.perform(get("/webjars/leaflet/dist/leaflet.css")).andExpect(status().isOk());
    }

    @Test
    void markercluster_에셋이_webjar에서_서빙된다() throws Exception {
        mvc.perform(get("/webjars/leaflet.markercluster/dist/leaflet.markercluster.js"))
                .andExpect(status().isOk());
        mvc.perform(get("/webjars/leaflet.markercluster/dist/MarkerCluster.css"))
                .andExpect(status().isOk());
        mvc.perform(get("/webjars/leaflet.markercluster/dist/MarkerCluster.Default.css"))
                .andExpect(status().isOk());
    }

    @Test
    void Leaflet_기본_마커_이미지가_서빙된다() throws Exception {
        mvc.perform(get("/webjars/leaflet/dist/images/marker-icon.png")).andExpect(status().isOk());
    }

    @Test
    void 정적_자원이_서빙된다() throws Exception {
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/app.css")).andExpect(status().isOk());
    }
}
```

이 테스트가 webjars 경로를 실측한다. 실패하면 실제 jar 내부 경로를 확인한다:

```bash
find ~/.gradle/caches -name 'leaflet-1.9.4.jar' -exec unzip -l {} \; | head -40
find ~/.gradle/caches -name 'leaflet.markercluster-1.5.3.jar' -exec unzip -l {} \; | head -40
```

- [ ] **Step 2: 테스트 실행해 실패 확인**

```bash
./gradlew test --tests 'PageAndAssetTest'
```

Expected: FAIL — `/` 가 404.

- [ ] **Step 3: `PageController` 작성**

`src/main/java/com/example/commercialarea/web/PageController.java`:

```java
package com.example.commercialarea.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "index";
    }
}
```

- [ ] **Step 4: `index.html` 작성**

`src/main/resources/templates/index.html`:

```html
<!DOCTYPE html>
<html lang="ko" xmlns:th="http://www.thymeleaf.org">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>상권정보 지도</title>
  <link rel="stylesheet" href="/webjars/leaflet/dist/leaflet.css">
  <link rel="stylesheet" href="/webjars/leaflet.markercluster/dist/MarkerCluster.css">
  <link rel="stylesheet" href="/webjars/leaflet.markercluster/dist/MarkerCluster.Default.css">
  <link rel="stylesheet" href="/app.css">
</head>
<body>
  <header class="topbar">
    <h1>상권정보 지도</h1>
    <div class="total">총 <strong id="total">0</strong>건</div>
  </header>

  <main class="layout">
    <aside class="panel" id="panel">
      <!-- Task 12에서 필터 컨트롤이 들어온다 -->
    </aside>

    <section class="content">
      <div id="map"></div>
      <div class="list" id="list">
        <!-- Task 12에서 결과 목록이 들어온다 -->
      </div>
    </section>
  </main>

  <script src="/webjars/leaflet/dist/leaflet.js"></script>
  <script src="/webjars/leaflet.markercluster/dist/leaflet.markercluster.js"></script>
  <script src="/app.js"></script>
</body>
</html>
```

- [ ] **Step 5: `app.css` 작성**

`src/main/resources/static/app.css`:

```css
* { box-sizing: border-box; }

body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
  color: #1c1c1e;
}

.topbar {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid #e0e0e4;
  background: #fff;
}

.topbar h1 { font-size: 17px; margin: 0; font-weight: 650; }
.topbar .total { font-size: 14px; color: #6b6b70; }
.topbar .total strong { color: #1c1c1e; font-variant-numeric: tabular-nums; }

.layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  height: calc(100vh - 49px);
}

.panel {
  border-right: 1px solid #e0e0e4;
  padding: 16px;
  overflow-y: auto;
  background: #fafafa;
}

.content {
  display: grid;
  grid-template-rows: 1fr 240px;
  min-width: 0;
}

#map { width: 100%; height: 100%; }

.list {
  border-top: 1px solid #e0e0e4;
  overflow-y: auto;
  background: #fff;
}

/* 집계 셀의 건수 라벨 */
.cell-label {
  background: transparent;
  border: none;
  box-shadow: none;
  font-size: 11px;
  font-weight: 700;
  color: #fff;
  text-shadow: 0 0 3px rgba(0, 0, 0, .55);
}
.cell-label::before { display: none; }
```

- [ ] **Step 6: `app.js` 작성 (지도 부분만)**

`src/main/resources/static/app.js`:

```js
'use strict';

// webjars 경로에서는 Leaflet이 기본 마커 이미지 경로를 스스로 찾지 못한다.
L.Icon.Default.imagePath = '/webjars/leaflet/dist/images/';

const SEOUL = [37.5665, 126.9780];

const map = L.map('map').setView(SEOUL, 11);

L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).addTo(map);

const cellLayer = L.layerGroup().addTo(map);
const markerLayer = L.markerClusterGroup({ chunkedLoading: true });
map.addLayer(markerLayer);

/** Task 12에서 필터 값이 채워진다. */
const filters = {
  sido: '', sgg: '', dong: '',
  large: '', medium: '', small: '',
  q: ''
};

function currentParams() {
  const b = map.getBounds();
  const params = new URLSearchParams({
    minLat: b.getSouth(),
    maxLat: b.getNorth(),
    minLon: b.getWest(),
    maxLon: b.getEast(),
    zoom: map.getZoom()
  });
  for (const [key, value] of Object.entries(filters)) {
    if (value) params.set(key, value);
  }
  return params;
}

function cellRadius(count) {
  // 선형으로 키우면 대도시 셀 하나가 화면을 덮는다.
  return Math.min(38, 7 + Math.sqrt(count) * 0.75);
}

function renderMap(data) {
  cellLayer.clearLayers();
  markerLayer.clearLayers();

  if (data.mode === 'cluster') {
    for (const cell of data.cells) {
      L.circleMarker([cell.lat, cell.lon], {
        radius: cellRadius(cell.count),
        color: '#1c5ed6',
        weight: 1,
        fillColor: '#3b82f6',
        fillOpacity: 0.55
      })
        .bindTooltip(cell.count.toLocaleString(), {
          permanent: true, direction: 'center', className: 'cell-label'
        })
        .on('click', () => map.setView([cell.lat, cell.lon], Math.min(19, map.getZoom() + 2)))
        .addTo(cellLayer);
    }
    return;
  }

  const markers = data.points.map(point => {
    const marker = L.marker([point.lat, point.lon]);
    marker.on('click', () => openDetail(marker, point.id));
    return marker;
  });
  markerLayer.addLayers(markers);
}

async function openDetail(marker, id) {
  marker.bindPopup('불러오는 중…').openPopup();
  try {
    const response = await fetch('/api/stores/' + encodeURIComponent(id));
    if (!response.ok) throw new Error('not found');
    const s = await response.json();
    marker.setPopupContent(`
      <div class="popup">
        <strong>${escapeHtml(s.storeName)}</strong>${s.branchName ? ' ' + escapeHtml(s.branchName) : ''}
        <div>${escapeHtml(s.largeName)} &gt; ${escapeHtml(s.mediumName)} &gt; ${escapeHtml(s.smallName)}</div>
        <div>${escapeHtml(s.roadAddress || s.lotAddress || '')}</div>
        ${s.buildingName ? `<div>${escapeHtml(s.buildingName)}${s.floorInfo ? ' ' + escapeHtml(s.floorInfo) + '층' : ''}</div>` : ''}
      </div>
    `);
  } catch (e) {
    marker.setPopupContent('정보를 불러오지 못했습니다.');
  }
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

// Task 12에서 목록 갱신을 합치기 위해 재할당하므로 let으로 선언한다.
let refresh = async function () {
  const params = currentParams();
  try {
    const data = await fetch('/api/map?' + params).then(r => r.json());
    if (data.error) {
      console.warn('지도 조회 실패', data);
      return;
    }
    renderMap(data);
    document.getElementById('total').textContent = data.total.toLocaleString();
  } catch (e) {
    console.error('지도 조회 실패', e);
  }
};

let refreshTimer;
function scheduleRefresh() {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refresh, 300);
}

map.on('moveend', scheduleRefresh);
refresh();
```

- [ ] **Step 7: 테스트 실행해 통과 확인**

```bash
./gradlew test --tests 'PageAndAssetTest'
```

Expected: PASS (5개).

webjars 경로 테스트가 실패하면 Step 1의 `unzip -l` 명령으로 실제 경로를 확인하고, `index.html`·`app.js`·테스트의 경로를 함께 고친다.

- [ ] **Step 8: 실제 데이터로 화면 확인**

```bash
docker compose up -d --build
open http://localhost:8080
```

확인 항목:
- 서울 중심에 지도가 뜬다
- 줌 11에서 파란 집계 원과 건수 라벨이 보인다
- 원을 클릭하면 2단계 줌인된다
- 충분히 줌인하면 개별 마커로 바뀌고, 마커 클릭 시 상호명·업종·주소 팝업이 뜬다
- 상단 "총 N건"이 지도를 움직일 때마다 갱신된다

**줌아웃 응답 시간을 기록한다.** 브라우저 개발자도구 Network 탭에서 `/api/map` 응답 시간을 본다. 스펙 §12의 "1초 이내" 가정을 검증하는 지점이다. 2초를 넘으면 Task 13에서 대응한다.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "feat: 지도 화면과 렌더링 추가

Leaflet은 webjars로 로컬 번들해 사내망·오프라인에서도 로드된다.
webjars 경로에서는 기본 마커 이미지 경로를 명시해야 한다.
집계 원 반지름은 건수의 제곱근에 비례시킨다.
선형으로 키우면 대도시 셀 하나가 화면을 덮는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 12: 필터 패널과 결과 목록

**Files:**
- Modify: `src/main/resources/templates/index.html` (panel·list 영역)
- Modify: `src/main/resources/static/app.css` (필터·목록 스타일)
- Modify: `src/main/resources/static/app.js` (필터 연쇄, 목록 렌더링)

**Interfaces:**
- Consumes: `GET /api/regions/*`, `GET /api/industries/*` (Task 10), `GET /api/stores` (Task 9), `app.js`의 `filters`, `currentParams()`, `refresh()`, `escapeHtml()` (Task 11).
- Produces: 완성된 화면. 이후 태스크는 검증과 문서화만 한다.

- [ ] **Step 1: `index.html`의 panel·list 영역 채우기**

`<aside class="panel" id="panel">` 내용을 아래로 교체한다:

```html
    <aside class="panel" id="panel">
      <div class="group">
        <h2>지역</h2>
        <select id="sido"><option value="">시도 전체</option></select>
        <select id="sgg" disabled><option value="">시군구 전체</option></select>
        <select id="dong" disabled><option value="">행정동 전체</option></select>
      </div>

      <div class="group">
        <h2>업종</h2>
        <select id="large"><option value="">대분류 전체</option></select>
        <select id="medium" disabled><option value="">중분류 전체</option></select>
        <select id="small" disabled><option value="">소분류 전체</option></select>
      </div>

      <div class="group">
        <h2>상호명</h2>
        <input id="q" type="search" placeholder="앞부분 일치 검색" autocomplete="off">
        <p class="hint">입력한 글자로 <em>시작하는</em> 상호를 찾습니다.</p>
      </div>

      <button id="reset" type="button" class="reset">필터 초기화</button>
    </aside>
```

`<div class="list" id="list">` 내용을 아래로 교체한다:

```html
      <div class="list" id="list">
        <table class="list-table">
          <thead>
            <tr><th>상호명</th><th>업종</th><th>주소</th></tr>
          </thead>
          <tbody id="list-body">
            <tr class="empty"><td colspan="3">지도를 움직이면 목록이 갱신됩니다.</td></tr>
          </tbody>
        </table>
        <div class="list-footer">
          <button id="prev" type="button" disabled>이전</button>
          <span id="page-info">0 / 0</span>
          <button id="next" type="button" disabled>다음</button>
        </div>
      </div>
```

- [ ] **Step 2: `app.css`에 필터·목록 스타일 추가**

파일 끝에 덧붙인다:

```css
.group { margin-bottom: 22px; }
.group h2 {
  font-size: 12px;
  font-weight: 650;
  color: #6b6b70;
  letter-spacing: .02em;
  margin: 0 0 8px;
}

.panel select,
.panel input[type="search"] {
  width: 100%;
  padding: 7px 9px;
  margin-bottom: 6px;
  border: 1px solid #d4d4d8;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  font-family: inherit;
}
.panel select:disabled { background: #f0f0f2; color: #9a9aa0; }

.hint { font-size: 11px; color: #8a8a90; margin: 4px 0 0; line-height: 1.5; }
.hint em { font-style: normal; font-weight: 650; color: #6b6b70; }

.reset {
  width: 100%;
  padding: 8px;
  border: 1px solid #d4d4d8;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
}
.reset:hover { background: #f0f0f2; }

.list-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.list-table th {
  position: sticky;
  top: 0;
  background: #fafafa;
  text-align: left;
  font-weight: 600;
  font-size: 12px;
  color: #6b6b70;
  padding: 7px 12px;
  border-bottom: 1px solid #e0e0e4;
}
.list-table td { padding: 7px 12px; border-bottom: 1px solid #f0f0f2; }
.list-table tbody tr:not(.empty) { cursor: pointer; }
.list-table tbody tr:not(.empty):hover { background: #f5f8ff; }
.list-table td.addr { color: #6b6b70; }
.list-table tr.empty td { color: #9a9aa0; text-align: center; padding: 24px; }

.list-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 8px;
  border-top: 1px solid #f0f0f2;
  font-size: 12px;
  color: #6b6b70;
}
.list-footer button {
  padding: 4px 12px;
  border: 1px solid #d4d4d8;
  border-radius: 5px;
  background: #fff;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}
.list-footer button:disabled { color: #c0c0c6; cursor: default; }

.popup { font-size: 13px; line-height: 1.6; }
.popup div { color: #4a4a50; }
```

- [ ] **Step 3: `app.js`에 필터·목록 로직 추가**

`app.js`의 `map.on('moveend', scheduleRefresh); refresh();` 두 줄을 **삭제**하고, 파일 끝에 아래를 덧붙인다.

```js
/* ---------- 필터 드롭다운 ---------- */

const el = id => document.getElementById(id);

const CASCADE = [
  { id: 'sido',   child: 'sgg',    url: () => '/api/regions/sido' },
  { id: 'sgg',    child: 'dong',   url: v => '/api/regions/sgg?sido=' + encodeURIComponent(v) },
  { id: 'dong',   child: null,     url: v => '/api/regions/dong?sgg=' + encodeURIComponent(v) },
  { id: 'large',  child: 'medium', url: () => '/api/industries/large' },
  { id: 'medium', child: 'small',  url: v => '/api/industries/medium?large=' + encodeURIComponent(v) },
  { id: 'small',  child: null,     url: v => '/api/industries/small?medium=' + encodeURIComponent(v) }
];

const PLACEHOLDER = {
  sido: '시도 전체', sgg: '시군구 전체', dong: '행정동 전체',
  large: '대분류 전체', medium: '중분류 전체', small: '소분류 전체'
};

function resetSelect(id) {
  const select = el(id);
  select.innerHTML = `<option value="">${PLACEHOLDER[id]}</option>`;
  select.disabled = true;
  filters[id] = '';
}

async function fillSelect(id, url) {
  const select = el(id);
  try {
    const items = await fetch(url).then(r => r.json());
    select.innerHTML = `<option value="">${PLACEHOLDER[id]}</option>` + items.map(item => {
      const bbox = item.minLat != null
        ? ` data-bbox="${item.minLat},${item.minLon},${item.maxLat},${item.maxLon}"`
        : '';
      return `<option value="${escapeHtml(item.code)}"${bbox}>`
           + `${escapeHtml(item.name)} (${item.count.toLocaleString()})</option>`;
    }).join('');
    select.disabled = items.length === 0;
  } catch (e) {
    console.error('옵션 로딩 실패: ' + id, e);
    select.disabled = true;
  }
}

function fitToSelected(select) {
  const bbox = select.selectedOptions[0]?.dataset.bbox;
  if (!bbox) return;
  const [minLat, minLon, maxLat, maxLon] = bbox.split(',').map(Number);
  // 한 점뿐인 구역은 bounds가 0 넓이라 지도가 최대 줌으로 튄다. 약간 넓혀준다.
  const pad = 0.002;
  map.fitBounds([[minLat - pad, minLon - pad], [maxLat + pad, maxLon + pad]]);
}

for (const level of CASCADE) {
  el(level.id).addEventListener('change', async event => {
    const value = event.target.value;
    filters[level.id] = value;

    // 하위 단계를 모두 초기화한다.
    let child = level.child;
    while (child) {
      resetSelect(child);
      child = CASCADE.find(l => l.id === child)?.child;
    }

    if (value && level.child) {
      const childLevel = CASCADE.find(l => l.id === level.child);
      await fillSelect(childLevel.id, childLevel.url(value));
    }

    page = 0;
    // 지역을 고르면 지도를 그쪽으로 옮긴다. moveend가 refresh를 부른다.
    if (value && event.target.selectedOptions[0]?.dataset.bbox) {
      fitToSelected(event.target);
    } else {
      refresh();
    }
  });
}

let searchTimer;
el('q').addEventListener('input', event => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    filters.q = event.target.value.trim();
    page = 0;
    refresh();
  }, 300);
});

el('reset').addEventListener('click', () => {
  for (const level of CASCADE) {
    if (level.id === 'sido' || level.id === 'large') {
      el(level.id).value = '';
      filters[level.id] = '';
    } else {
      resetSelect(level.id);
    }
  }
  el('q').value = '';
  filters.q = '';
  page = 0;
  map.setView(SEOUL, 11);   // moveend가 refresh를 부른다
});

/* ---------- 결과 목록 ---------- */

const PAGE_SIZE = 20;
let page = 0;
let totalPages = 0;

function renderList(data) {
  const body = el('list-body');
  totalPages = Math.ceil(data.total / PAGE_SIZE);

  if (data.items.length === 0) {
    body.innerHTML = '<tr class="empty"><td colspan="3">조건에 맞는 상가가 없습니다.</td></tr>';
  } else {
    body.innerHTML = data.items.map(item => `
      <tr data-id="${escapeHtml(item.id)}" data-lat="${item.lat}" data-lon="${item.lon}">
        <td>${escapeHtml(item.name)}${item.branchName ? ' ' + escapeHtml(item.branchName) : ''}</td>
        <td>${escapeHtml(item.smallName)}</td>
        <td class="addr">${escapeHtml(item.roadAddress || '')}</td>
      </tr>
    `).join('');
  }

  el('page-info').textContent = totalPages === 0 ? '0 / 0' : `${page + 1} / ${totalPages}`;
  el('prev').disabled = page <= 0;
  el('next').disabled = page >= totalPages - 1;
}

el('list-body').addEventListener('click', event => {
  const row = event.target.closest('tr[data-id]');
  if (!row) return;
  const lat = Number(row.dataset.lat);
  const lon = Number(row.dataset.lon);
  map.setView([lat, lon], Math.max(map.getZoom(), 17));
  const marker = L.marker([lat, lon]).addTo(map);
  openDetail(marker, row.dataset.id);
  marker.on('popupclose', () => map.removeLayer(marker));
});

el('prev').addEventListener('click', () => { if (page > 0) { page--; refreshList(); } });
el('next').addEventListener('click', () => { if (page < totalPages - 1) { page++; refreshList(); } });

async function refreshList() {
  const params = currentParams();
  params.set('page', page);
  params.set('size', PAGE_SIZE);
  try {
    const data = await fetch('/api/stores?' + params).then(r => r.json());
    if (data.error) {
      console.warn('목록 조회 실패', data);
      return;
    }
    renderList(data);
  } catch (e) {
    console.error('목록 조회 실패', e);
  }
}

/* ---------- 초기화 ---------- */

// Task 11의 refresh()를 감싸 목록까지 함께 갱신한다.
const refreshMapOnly = refresh;
refresh = async function () {
  await Promise.all([refreshMapOnly(), refreshList()]);
};

map.on('moveend', () => { page = 0; scheduleRefresh(); });

(async function init() {
  await Promise.all([
    fillSelect('sido', '/api/regions/sido'),
    fillSelect('large', '/api/industries/large')
  ]);
  el('sido').disabled = false;
  el('large').disabled = false;
  refresh();
})();
```

`refresh`는 Task 11에서 이미 `let refresh = async function () { ... };` 로 선언해 두었으므로 재할당이 그대로 동작한다.

- [ ] **Step 4: 빌드 확인**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL` (전체 테스트 통과).

- [ ] **Step 5: 브라우저에서 수동 검증**

```bash
docker compose up -d --build
open http://localhost:8080
```

아래를 순서대로 확인한다.

| 확인 항목 | 기대 동작 |
|---|---|
| 초기 로딩 | 시도·대분류 드롭다운에 항목과 건수가 채워짐 |
| 시도 선택 | 시군구 드롭다운이 활성화되고 채워짐 |
| 시군구 선택 | 지도가 해당 구로 이동, 지도·목록 갱신 |
| 행정동 선택 | 지도가 해당 동으로 이동 |
| 대분류 선택 | 중분류 활성화, 지도·목록 즉시 갱신 |
| 지역+업종 동시 | 두 조건의 교집합만 표시 |
| 상호명 입력 | 300ms 뒤 갱신, 앞부분 일치만 |
| 필터 초기화 | 모든 드롭다운·검색어가 비고 서울 전체로 복귀 |
| 목록 행 클릭 | 해당 좌표로 이동 + 팝업 |
| 이전/다음 | 페이지 이동, 경계에서 버튼 비활성 |
| 지도 드래그 | 목록이 1페이지로 돌아가며 갱신 |

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat: 필터 패널과 결과 목록 추가

지역·업종 3단계 연쇄 필터와 상호명 검색을 붙인다.
시군구·행정동을 고르면 해당 구역 bbox로 지도를 이동시킨다.
선택만 되고 지도가 그대로면 필터가 무의미하기 때문이다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 13: 성능 실측과 문서화

**Files:**
- Create: `README.md`
- Modify: `src/main/resources/schema.sql` (필요 시 인덱스 추가)
- Modify: `docs/superpowers/specs/2026-09-16-commercial-area-poc-design.md` (§12 실측 결과 기록)

**Interfaces:**
- Consumes: 전체 시스템.
- Produces: 실행 가능한 README와 검증된 성능 수치.

- [ ] **Step 1: 깨끗한 상태에서 전체 검증**

볼륨까지 지우고 처음부터 돌린다. 이것이 스펙 §11 완료 기준 1·2·8을 검증한다.

```bash
docker compose down -v
time docker compose up -d --build
docker compose logs -f app
```

Expected: `전체 적재 완료: 성공 1226772 / 실패 0` 과 소요 시간. **이 숫자를 기록한다.**

```bash
docker compose exec mysql mysql -uapp -papp commercial_area -e "
  SELECT COUNT(*) AS store FROM store;
  SELECT COUNT(*) AS region FROM region;
  SELECT COUNT(*) AS industry FROM industry;"
```

- [ ] **Step 2: 재기동 시 적재를 건너뛰는지 확인**

```bash
docker compose restart app
docker compose logs app | grep "적재를 건너뛴다"
```

Expected: `store 테이블에 데이터가 이미 있어 적재를 건너뛴다`.

- [ ] **Step 3: 줌아웃 집계 응답 시간 실측**

스펙 §12에서 가장 불확실하다고 표시한 항목이다.

```bash
# 서울·경기 전역을 덮는 bbox, 줌 9
time curl -s -o /dev/null -w '%{time_total}s\n' \
  'http://localhost:8080/api/map?minLat=36.8&maxLat=38.2&minLon=126.3&maxLon=127.9&zoom=9'

# 업종 필터를 건 경우
time curl -s -o /dev/null -w '%{time_total}s\n' \
  'http://localhost:8080/api/map?minLat=36.8&maxLat=38.2&minLon=126.3&maxLon=127.9&zoom=9&large=I2'
```

**1초 이내면 Step 5로 건너뛴다.** 넘으면 Step 4를 수행한다.

- [ ] **Step 4: (느릴 때만) 실행 계획 확인 후 인덱스 보강**

```bash
docker compose exec mysql mysql -uapp -papp commercial_area -e "
EXPLAIN SELECT FLOOR(lat/0.087890625) gy, FLOOR(lon/0.087890625) gx,
       COUNT(*) cnt, AVG(lat) clat, AVG(lon) clon
FROM store
WHERE lat BETWEEN 36.8 AND 38.2 AND lon BETWEEN 126.3 AND 127.9
GROUP BY gy, gx\G"
```

`type: ALL`(풀스캔)이거나 `key: NULL`이면 커버링 인덱스를 추가한다. `idx_geo(lat, lon)`은 있지만 `AVG`/`COUNT`를 위해 행 조회가 발생하는 경우 아래가 효과적이다.

`schema.sql`에 추가하고 기존 DB에도 적용한다:

```sql
ALTER TABLE store ADD KEY idx_geo_cover (lat, lon, large_code, sgg_code);
```

그래도 2초를 넘으면 줌 구간별 격자 키를 생성 컬럼으로 둔다. 줌 9~13 구간을 하나의 컬럼으로 덮는다 (`cell = 360/2^12 = 0.087890625`):

```sql
ALTER TABLE store
  ADD COLUMN grid_mid BIGINT AS (
    FLOOR((lat + 90) / 0.087890625) * 100000 + FLOOR((lon + 180) / 0.087890625)
  ) STORED,
  ADD KEY idx_grid_mid (grid_mid, lat, lon);
```

그 뒤 `StoreRepository.aggregate()`에서 `query.zoom()`이 9~13이면 `GROUP BY grid_mid`를 쓰도록 분기한다. **이 분기를 넣었다면 `StoreAggregateTest`의 "셀 건수의 합이 전체 건수와 일치한다"를 해당 줌 구간으로도 확장해 실행한다.**

변경했다면 커밋한다:

```bash
git add -A
git commit -m "perf: 줌아웃 집계 쿼리 인덱스 보강

실측 결과 서울·경기 전역 줌아웃 집계가 N초로 목표를 초과해
커버링 인덱스를 추가했다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

- [ ] **Step 5: 스펙의 §12 표를 실측값으로 갱신**

`docs/superpowers/specs/2026-09-16-commercial-area-poc-design.md`의 §12 표에 **실측** 열을 추가하고 Step 1·3에서 기록한 값을 채운다. 예상과 다른 항목은 무엇을 어떻게 대응했는지 한 줄씩 적는다.

- [ ] **Step 6: `README.md` 작성**

```markdown
# 상권정보 지도 (PoC)

소상공인시장진흥공단 상가(상권)정보를 MySQL에 적재하고 지도 위에서
지역·업종으로 필터링해 탐색하는 로컬 전용 PoC.

## 요구 사항

- Docker Desktop (Compose v2 이상)
- 상가(상권)정보 CSV 파일 — [공공데이터포털](https://www.data.go.kr)에서 내려받는다

## 실행

```bash
cp .env.example .env
# .env의 CSV_DIR을 CSV가 있는 디렉토리로 바꾼다
docker compose up -d --build
docker compose logs -f app     # 적재 진행 확인
open http://localhost:8080
```

최초 기동에서만 CSV를 적재한다(서울·경기 122만 행, 약 N분).
MySQL 볼륨이 유지되므로 이후 기동은 즉시 뜬다.

처음부터 다시 적재하려면:

```bash
docker compose down -v && docker compose up -d --build
```

## 설정

`.env`에서 바꾼다.

| 변수 | 설명 | 기본값 |
|---|---|---|
| `CSV_DIR` | CSV가 있는 호스트 디렉토리 (읽기 전용 마운트) | — |
| `IMPORT_INCLUDE` | 적재할 파일명 필터(쉼표 구분). 비우면 전체 | `서울,경기` |

전국 데이터를 적재하려면 `IMPORT_INCLUDE=`로 비우고 `docker compose down -v` 후 재기동한다.
약 250만 행이라 적재 시간과 집계 응답이 비례해 늘어난다.

## 기능

- 지도에 상가 표시 — 줌아웃 시 격자 집계 원, 결과가 2,000건 이하면 개별 마커
- 지역 필터 — 시도 → 시군구 → 행정동 (선택 시 해당 구역으로 지도 이동)
- 업종 필터 — 대분류 → 중분류 → 소분류
- 상호명 앞부분 일치 검색
- 현재 지도 영역 기준 결과 목록과 페이징
- 마커·목록 클릭 시 상호명·업종·주소 상세 팝업

## 지도

Leaflet + OpenStreetMap 타일을 쓴다. API 키가 필요 없다.
Leaflet 자체는 webjars로 번들되지만 **타일 이미지는 인터넷 연결이 필요하다.**

국내 지명 표기가 더 필요하면 `src/main/resources/static/app.js`의
`L.tileLayer(...)` URL을 VWorld 등으로 교체한다(키 발급 필요).

## 개발

```bash
./gradlew test    # Testcontainers가 MySQL을 띄우므로 Docker가 실행 중이어야 한다
./gradlew build
```

## API

| 엔드포인트 | 설명 |
|---|---|
| `GET /api/map` | bbox + 필터 → 격자 집계 또는 개별 점 |
| `GET /api/stores` | 목록 (페이징) |
| `GET /api/stores/{id}` | 상세 |
| `GET /api/regions/sido\|sgg\|dong` | 지역 드롭다운 |
| `GET /api/industries/large\|medium\|small` | 업종 드롭다운 |

설계 배경과 결정 근거는 [설계 문서](docs/superpowers/specs/2026-09-16-commercial-area-poc-design.md)를 참조한다.

## 알려진 제약

- 상호명은 **앞부분 일치**만 지원한다. 한글 부분검색은 ngram FULLTEXT 인덱스가 필요하다.
- 인증·권한이 없다. 로컬 전용이다.
- 클라우드 배포 설정이 없다.
```

README의 `약 N분`을 Step 1에서 기록한 실제 시간으로 바꾼다.

- [ ] **Step 7: 전체 테스트 최종 실행**

```bash
./gradlew clean build
```

Expected: `BUILD SUCCESSFUL`. 실패한 테스트가 하나라도 있으면 완료가 아니다.

- [ ] **Step 8: 완료 기준 점검**

스펙 §11의 9개 항목을 하나씩 직접 확인하고 통과 여부를 적는다. 통과하지 못한 항목이 있으면 해당 태스크로 돌아간다.

- [ ] **Step 9: 커밋**

```bash
git add -A
git commit -m "docs: README 추가와 성능 실측 결과 반영

깨끗한 볼륨에서 전체 실행을 검증하고 설계 문서 §12의
가정을 실측값으로 갱신했다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. 스펙 커버리지**

| 스펙 항목 | 구현 태스크 |
|---|---|
| §2 기술 스택 | Task 1 |
| §3 CSV 특성·컬럼 매핑 | Task 2 |
| §4 스키마 3개 테이블 | Task 2 (`store`) · Task 2 (`region`,`industry` DDL) |
| §5 적재 (배치·멱등·로깅·오류) | Task 3, Task 4 |
| §5 룩업 테이블 생성 | Task 4 |
| §6.1 `/api/map` 격자·모드 결정 | Task 5, Task 7, Task 8 |
| §6.2 지역 룩업 | Task 10 |
| §6.3 업종 룩업 | Task 10 |
| §6.4 목록 | Task 6, Task 9 |
| §6.5 상세 | Task 6, Task 9 |
| §6.6 오류 응답 | Task 7, Task 8 |
| §7 화면·상호작용·에셋 | Task 11, Task 12 |
| §8 프로젝트 구조 | Task 1~12 전반 |
| §9 테스트 전략 4종 | Task 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 |
| §10 Docker 구성 | Task 1 |
| §11 완료 기준 | Task 13 Step 8 |
| §12 검증이 필요한 가정 | Task 4 Step 7, Task 11 Step 8, Task 13 Step 1·3·5 |

누락 없음.

**2. 플레이스홀더 점검**

"적절한 오류 처리", "TBD", "Task N과 유사" 같은 표현 없음. 모든 코드 단계에 실제 코드가 있다.

Task 13 Step 4는 조건부 단계지만 실행할 SQL과 판단 기준(1초/2초)을 구체적으로 적었다. Task 13의 README에 남는 `N분`·`N초`는 **실측 후 채우도록 지시된 값**이며, Step 1·3에서 측정 방법을 명시했다.

**3. 타입 일관성**

- `Store` 21개 필드 순서는 Task 2 정의 → Task 3·5·6·7·8·9·10의 모든 생성자 호출에서 동일하다 (`lotAddress, buildingName, roadAddress, floorInfo, lon, lat` 순). `schema.sql`의 컬럼 순서와 `StoreRepository.bind()`의 인덱스 1~21도 여기에 맞춰져 있다.
- `MapQuery` 12개 인자 순서는 Task 5 정의 → Task 7·8·9·테스트 전체에서 동일하다.
- `StoreFilterSql.appendWhere(StringBuilder, Map, MapQuery)` 시그니처는 Task 5 정의 → Task 6의 3개 호출에서 동일하다.
- `MapResponse.cluster/point` 팩토리는 Task 7 정의 → Task 7 테스트·Task 8 테스트의 `mode` 문자열(`"cluster"`, `"point"`)과 일치한다.
- `LookupItem.of(code, name, count)` 3인자 팩토리와 7인자 생성자를 Task 10에서 일관되게 쓴다.
- `app.js`의 `refresh`는 Task 12에서 재할당되므로 Task 11에서 처음부터 `let refresh = async function () {...};` 로 선언한다. 나중에 함수 선언을 고치는 재작업이 생기지 않는다.
- `cellLayer`/`markerLayer` 이름은 Task 11 정의 → Task 12에서 재사용한다. `clearLayers()` 호출명이 일관된다.

---

## 실행 순서 요약

| # | 태스크 | 결과물 |
|---:|---|---|
| 1 | 스캐폴딩·Docker | 앱이 기동한다 |
| 2 | 스키마·CSV 파서 | 한 행을 정확히 파싱한다 |
| 3 | Testcontainers·배치 인서트 | DB에 행이 들어간다 |
| 4 | 적재 러너·룩업 생성 | **실제 122만 행이 적재된다** |
| 5 | 격자 집계 쿼리 | 집계가 정확하다 |
| 6 | 점·목록·단건 조회 | 조회가 정확하다 |
| 7 | 오류 규약·모드 결정 | 건수로 모드가 갈린다 |
| 8 | `/api/map` | 지도 API가 동작한다 |
| 9 | `/api/stores` | 목록·상세 API가 동작한다 |
| 10 | 룩업 API | 드롭다운 데이터가 나온다 |
| 11 | 화면·지도 렌더링 | **지도에 상가가 보인다** |
| 12 | 필터·목록 | **필터가 동작한다 (기능 완성)** |
| 13 | 실측·문서화 | 완료 기준 검증 |

Task 4, 11, 12가 눈으로 확인되는 이정표다.
