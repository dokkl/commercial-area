# 시간축 스냅샷 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 분기별 슬림 이력(`store_snapshot`)을 누적해 `store_id` 기준 개·폐업과 지역·업종별 점포수 추이를 계산하는 `GET /api/trend`와 "상권 추이" UI를 추가한다.

**Architecture:** 새 `store_snapshot`(슬림) + `import_log`(멱등 기준) 테이블을 두고, `store`/`region`/`industry`는 최신 스냅샷 기준으로 그대로 유지해 지도·분석 기능을 건드리지 않는다. 임포터는 `app.import.snapshot`(YYYYMM) 라벨로 스냅샷 단위 멱등 적재한다. 추이/개폐업은 `store_snapshot`에 대한 집계·집합차 쿼리로 계산한다.

**Tech Stack:** Java 21, Spring Boot(웹 MVC, `JdbcClient`/`JdbcTemplate`), MySQL 8.4, Testcontainers, JUnit5 + AssertJ + MockMvc + Mockito, 바닐라 JS(인라인 SVG 차트).

**Design spec:** `docs/superpowers/specs/2026-09-20-time-axis-snapshots-design.md`

---

## File Structure

**신규 파일**
- `src/main/java/com/example/commercialarea/snapshot/SnapshotRepository.java` — store_snapshot·import_log 적재/조회
- `src/main/java/com/example/commercialarea/snapshot/SnapshotCount.java` — (ym, count) 집계 행
- `src/main/java/com/example/commercialarea/snapshot/TrendQuery.java` — 추이 요청(지역+업종)
- `src/main/java/com/example/commercialarea/snapshot/TrendFilterSql.java` — 추이 WHERE/ON 조건 조립
- `src/main/java/com/example/commercialarea/snapshot/SnapshotTrend.java` — 응답 항목(ym, count, opened, closed)
- `src/main/java/com/example/commercialarea/snapshot/TrendResponse.java` — 응답 DTO
- `src/main/java/com/example/commercialarea/snapshot/TrendService.java` — 시계열+개폐업 조립
- `src/main/java/com/example/commercialarea/snapshot/TrendController.java` — `GET /api/trend`
- 테스트: `SnapshotRepositoryTest`, `TrendServiceTest`, `TrendApiTest`(모두 `.../snapshot/`)

**변경 파일**
- `src/main/resources/schema.sql` — `store_snapshot`, `import_log` 추가
- `src/main/java/com/example/commercialarea/config/ImportProperties.java` — `snapshot` 필드
- `src/main/java/com/example/commercialarea/store/StoreRepository.java` — `deleteAllStores()`
- `src/main/java/com/example/commercialarea/importer/StoreImportRunner.java` — 스냅샷 단위 적재 로직
- 테스트: `ImportPropertiesTest`, `StoreImportRunnerUnitTest`, `StoreImportRunnerTest` (생성자/로직 갱신)
- `src/main/resources/templates/index.html`, `static/app.js`, `static/app.css` — 추이 UI
- `src/main/resources/application.yml`, `.env.example`, `docker-compose.yml`, `README.md` — 설정·문서

> 패키지 주의: 추이 관련 신규 클래스는 새 패키지 `com.example.commercialarea.snapshot`에 둔다. `Store` record는 `com.example.commercialarea.store.Store`이므로 import 한다.

---

## Task 1: 스키마 — store_snapshot + import_log

**Files:**
- Modify: `src/main/resources/schema.sql`

`spring.sql.init.mode: always`라 부팅 시 schema.sql이 실행된다. `IF NOT EXISTS`라 기존 테이블에 영향 없다.

- [ ] **Step 1: Append the two tables**

`src/main/resources/schema.sql` 맨 끝에 추가:
```sql
CREATE TABLE IF NOT EXISTS store_snapshot (
  snapshot_ym VARCHAR(6)  NOT NULL,
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

- [ ] **Step 2: Verify build**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (테이블 존재는 Task 3의 Testcontainers 테스트가 실제로 검증한다.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/schema.sql
git commit -m "feat: store_snapshot·import_log 스키마 추가"
```

---

## Task 2: ImportProperties.snapshot 필드 + 호출부 갱신

`ImportProperties`는 record이므로 컴포넌트를 추가하면 **모든 생성자 호출부**가 깨진다. 이 태스크에서 필드 추가와 함께 테스트 호출부를 모두 고쳐 프로젝트가 컴파일되게 한다.

**Files:**
- Modify: `src/main/java/com/example/commercialarea/config/ImportProperties.java`
- Modify: `src/test/java/com/example/commercialarea/config/ImportPropertiesTest.java`
- Modify: `src/test/java/com/example/commercialarea/importer/StoreImportRunnerUnitTest.java` (생성자 호출부만; 로직 테스트는 Task 4에서)
- Modify: `src/test/java/com/example/commercialarea/importer/StoreImportRunnerTest.java` (생성자 호출부만; 로직은 Task 4에서)

- [ ] **Step 1: Add the field**

`src/main/java/com/example/commercialarea/config/ImportProperties.java` 를 다음으로 교체:
```java
package com.example.commercialarea.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.text.Normalizer;
import java.util.List;

@ConfigurationProperties(prefix = "app.import")
public record ImportProperties(boolean enabled, String dir, List<String> include, int batchSize,
                               String snapshot) {
    public ImportProperties {
        if (include == null) include = List.of();
        if (batchSize <= 0) batchSize = 1000;
    }

    /**
     * include가 비어 있으면 모든 CSV를 적재한다.
     *
     * macOS에서 압축 해제된 CSV 파일명은 한글이 NFD(자모 분해) 형태로 저장되는 경우가 있는 반면,
     * application.yml/.env의 include 값은 NFC(완성형)다. String.contains는 정규화를 하지 않으므로
     * 두 값을 모두 NFC로 정규화한 뒤 비교해야 실제 파일명과 올바르게 매칭된다.
     */
    public boolean matches(String fileName) {
        if (include.isEmpty()) return true;
        String normalizedFileName = Normalizer.normalize(fileName, Normalizer.Form.NFC);
        return include.stream()
                .map(pattern -> Normalizer.normalize(pattern, Normalizer.Form.NFC))
                .anyMatch(normalizedFileName::contains);
    }

    /** 스냅샷 라벨(YYYYMM)이 유효한 6자리 숫자인지. */
    public boolean hasValidSnapshot() {
        return snapshot != null && snapshot.matches("\\d{6}");
    }
}
```

- [ ] **Step 2: Fix ImportPropertiesTest constructor calls + add snapshot test**

`src/test/java/com/example/commercialarea/config/ImportPropertiesTest.java` 의 세 `new ImportProperties(...)` 호출에 다섯 번째 인자(snapshot)를 추가하고, 유효성 테스트를 더한다. 기존 세 테스트의 생성자만 아래처럼 바꾼다(로직 본문은 유지):
```java
        ImportProperties props = new ImportProperties(true, "/tmp", List.of(), 1000, "202606");
```
```java
        ImportProperties props = new ImportProperties(true, "/tmp", List.of("서울", "경기"), 1000, "202606");
```
```java
        ImportProperties props = new ImportProperties(true, "/tmp", List.of("서울"), 1000, "202606");
```
그리고 클래스 끝에 테스트를 추가:
```java
    @Test
    void snapshot이_6자리_숫자면_유효하다() {
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, "202606").hasValidSnapshot()).isTrue();
    }

    @Test
    void snapshot이_없거나_형식이_틀리면_무효다() {
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, null).hasValidSnapshot()).isFalse();
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, "2026Q2").hasValidSnapshot()).isFalse();
        assertThat(new ImportProperties(true, "/tmp", List.of(), 1000, "20260").hasValidSnapshot()).isFalse();
    }
```

- [ ] **Step 3: Fix other constructor call sites (compile only)**

`StoreImportRunnerUnitTest.java`의 `props()` 헬퍼:
```java
    private ImportProperties props() {
        return new ImportProperties(true, csvDir.toString(), List.of("서울"), 100, "202606");
    }
```
`StoreImportRunnerTest.java`에 `new ImportProperties(...)` 호출이 있으면 동일하게 다섯 번째 인자 `"202606"`을 추가한다. (없으면 이 단계는 건너뛴다. Task 4에서 이 파일들의 로직을 마저 손본다 — 지금은 컴파일만 통과시키면 된다. 이 시점에 Task 4에서 새로 생길 협력자(SnapshotRepository) 참조로 컴파일이 깨지면, 그건 Task 4 소관이니 이 태스크에서는 **ImportProperties 생성자 인자만** 맞춘다.)

- [ ] **Step 4: Run the affected unit tests**

Run: `./gradlew test --tests '*ImportPropertiesTest'`
Expected: PASS (기존 3 + 신규 2 = 5 tests). 이 테스트는 Docker 불필요.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/config/ImportProperties.java \
        src/test/java/com/example/commercialarea/config/ImportPropertiesTest.java \
        src/test/java/com/example/commercialarea/importer/StoreImportRunnerUnitTest.java \
        src/test/java/com/example/commercialarea/importer/StoreImportRunnerTest.java
git commit -m "feat: ImportProperties에 snapshot(YYYYMM) 라벨과 검증 추가"
```

---

## Task 3: SnapshotRepository (적재/멱등) + StoreRepository.deleteAllStores

**Files:**
- Create: `src/main/java/com/example/commercialarea/snapshot/SnapshotRepository.java`
- Modify: `src/main/java/com/example/commercialarea/store/StoreRepository.java`
- Test: `src/test/java/com/example/commercialarea/snapshot/SnapshotRepositoryTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/commercialarea/snapshot/SnapshotRepositoryTest.java`:
```java
package com.example.commercialarea.snapshot;

import com.example.commercialarea.store.Store;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.import.enabled=false")
@Import(MySqlTestContainer.class)
class SnapshotRepositoryTest {

    @Autowired SnapshotRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store_snapshot").update();
        jdbc.sql("DELETE FROM import_log").update();
    }

    private static Store store(String id, String sgg, String small) {
        return new Store(id, "가게" + id, null,
                "I2", "음식", "I201", "중분류", small, "소분류",
                "11", "서울특별시", sgg, "구", sgg + "510", "동",
                "지번", null, "도로명", null, 127.0, 37.5);
    }

    @Test
    void 슬림_스냅샷_행을_적재한다() {
        repository.insertSnapshotBatch("202506", List.of(store("A1", "11680", "C1"), store("A2", "11680", "H1")));

        long n = jdbc.sql("SELECT COUNT(*) FROM store_snapshot WHERE snapshot_ym='202506'")
                .query(Long.class).single();
        assertThat(n).isEqualTo(2);
    }

    @Test
    void 같은_스냅샷_같은_store_id는_INSERT_IGNORE로_중복되지_않는다() {
        repository.insertSnapshotBatch("202506", List.of(store("A1", "11680", "C1")));
        repository.insertSnapshotBatch("202506", List.of(store("A1", "11680", "C1")));

        long n = jdbc.sql("SELECT COUNT(*) FROM store_snapshot WHERE snapshot_ym='202506'")
                .query(Long.class).single();
        assertThat(n).isEqualTo(1);
    }

    @Test
    void import_log_기록과_조회() {
        assertThat(repository.hasSnapshot("202506")).isFalse();
        assertThat(repository.latestSnapshotYm()).isEmpty();

        repository.recordImport("202503", 100);
        repository.recordImport("202506", 120);

        assertThat(repository.hasSnapshot("202506")).isTrue();
        assertThat(repository.latestSnapshotYm()).isEqualTo(Optional.of("202506"));
    }

    @Test
    void 특정_스냅샷_행만_삭제한다() {
        repository.insertSnapshotBatch("202503", List.of(store("A1", "11680", "C1")));
        repository.insertSnapshotBatch("202506", List.of(store("A1", "11680", "C1")));

        repository.deleteSnapshotRows("202503");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM store_snapshot WHERE snapshot_ym='202503'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM store_snapshot WHERE snapshot_ym='202506'")
                .query(Long.class).single()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*SnapshotRepositoryTest'`
Expected: compile failure (`SnapshotRepository` 없음). Testcontainers 사용 — Docker 필요.

- [ ] **Step 3: Write SnapshotRepository**

`src/main/java/com/example/commercialarea/snapshot/SnapshotRepository.java`:
```java
package com.example.commercialarea.snapshot;

import com.example.commercialarea.store.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class SnapshotRepository {

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO store_snapshot
              (snapshot_ym, store_id, sido_code, sgg_code, dong_code,
               large_code, medium_code, small_code)
            VALUES (?,?,?,?,?,?,?,?)
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public SnapshotRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 슬림 스냅샷 행을 배치 적재한다. INSERT IGNORE라 (snapshot_ym, store_id) 중복은 무시된다.
     * 호출부(러너)가 배치 크기를 나눠 전달한다 — StoreRepository.insertBatch와 동일 계약.
     */
    public int insertSnapshotBatch(String snapshotYm, List<Store> stores) {
        jdbcTemplate.batchUpdate(INSERT_SQL, stores, stores.size(),
                (ps, s) -> bind(ps, snapshotYm, s));
        return stores.size();
    }

    private static void bind(PreparedStatement ps, String ym, Store s) throws SQLException {
        ps.setString(1, ym);
        ps.setString(2, s.storeId());
        ps.setString(3, s.sidoCode());
        ps.setString(4, s.sggCode());
        ps.setString(5, s.dongCode());
        ps.setString(6, s.largeCode());
        ps.setString(7, s.mediumCode());
        ps.setString(8, s.smallCode());
    }

    public boolean hasSnapshot(String snapshotYm) {
        return jdbc.sql("SELECT COUNT(*) FROM import_log WHERE snapshot_ym = :ym")
                .param("ym", snapshotYm).query(Long.class).single() > 0;
    }

    /** 지금까지 적재 완료된 스냅샷 중 가장 최신(문자열 비교). 없으면 empty. */
    public Optional<String> latestSnapshotYm() {
        return jdbc.sql("SELECT snapshot_ym FROM import_log ORDER BY snapshot_ym DESC LIMIT 1")
                .query(String.class).optional();
    }

    public void deleteSnapshotRows(String snapshotYm) {
        jdbc.sql("DELETE FROM store_snapshot WHERE snapshot_ym = :ym")
                .param("ym", snapshotYm).update();
    }

    public void recordImport(String snapshotYm, long rowCount) {
        jdbc.sql("""
                INSERT INTO import_log (snapshot_ym, row_count, completed_at)
                VALUES (:ym, :cnt, NOW())
                """)
                .param("ym", snapshotYm).param("cnt", rowCount).update();
    }
}
```

- [ ] **Step 4: Add deleteAllStores to StoreRepository**

`src/main/java/com/example/commercialarea/store/StoreRepository.java` 의 클래스 안(예: `countAll()` 근처)에 추가:
```java
    /** 새 최신 스냅샷으로 교체하기 위해 store 전체를 비운다. */
    public void deleteAllStores() {
        jdbc.sql("DELETE FROM store").update();
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests '*SnapshotRepositoryTest'`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/commercialarea/snapshot/SnapshotRepository.java \
        src/main/java/com/example/commercialarea/store/StoreRepository.java \
        src/test/java/com/example/commercialarea/snapshot/SnapshotRepositoryTest.java
git commit -m "feat: SnapshotRepository(슬림 적재·import_log·멱등) 추가"
```

---

## Task 4: StoreImportRunner 스냅샷 단위 적재

기존 "행 단위 INSERT IGNORE 이어적재"를 "스냅샷 단위 멱등 적재"로 바꾼다. `import_log` 기준으로 중복 스킵, 최신 스냅샷이면 `store` 교체+룩업 재생성, 과거 백필이면 슬림만 기록.

**Files:**
- Modify: `src/main/java/com/example/commercialarea/importer/StoreImportRunner.java`
- Modify: `src/test/java/com/example/commercialarea/importer/StoreImportRunnerUnitTest.java` (로직 재작성)
- Modify: `src/test/java/com/example/commercialarea/importer/StoreImportRunnerTest.java` (통합 시나리오 갱신)

- [ ] **Step 1: Rewrite StoreImportRunner**

`src/main/java/com/example/commercialarea/importer/StoreImportRunner.java` 를 다음으로 교체:
```java
package com.example.commercialarea.importer;

import com.example.commercialarea.config.ImportProperties;
import com.example.commercialarea.snapshot.SnapshotRepository;
import com.example.commercialarea.store.Store;
import com.example.commercialarea.store.StoreRepository;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class StoreImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoreImportRunner.class);
    private static final long PROGRESS_INTERVAL = 100_000;

    private final ImportProperties settings;
    private final StoreCsvParser parser = new StoreCsvParser();
    private final StoreRepository repository;
    private final SnapshotRepository snapshotRepository;
    private final LookupBuilder lookupBuilder;

    public StoreImportRunner(ImportProperties settings, StoreRepository repository,
                             SnapshotRepository snapshotRepository, LookupBuilder lookupBuilder) {
        this.settings = settings;
        this.repository = repository;
        this.snapshotRepository = snapshotRepository;
        this.lookupBuilder = lookupBuilder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.enabled()) {
            log.info("CSV 적재 비활성화 (app.import.enabled=false)");
            return;
        }
        if (!settings.hasValidSnapshot()) {
            throw new IllegalStateException(
                    "app.import.snapshot(YYYYMM)이 필요합니다. 예: SNAPSHOT=202506. 현재 값: " + settings.snapshot());
        }
        String snapshot = settings.snapshot();

        if (snapshotRepository.hasSnapshot(snapshot)) {
            log.info("이미 적재된 스냅샷이라 건너뛴다: {}", snapshot);
            return;
        }

        Optional<String> latest = snapshotRepository.latestSnapshotYm();
        boolean isNewest = latest.isEmpty() || snapshot.compareTo(latest.get()) >= 0;

        // 중단된 이전 시도의 잔여를 지우고 이 스냅샷을 처음부터 적재한다(멱등).
        snapshotRepository.deleteSnapshotRows(snapshot);
        if (isNewest) {
            repository.deleteAllStores();   // store = 새 최신 스냅샷으로 교체
        }
        log.info("스냅샷 {} 적재 시작 (최신여부={})", snapshot, isNewest);

        ImportSummary summary = importFrom(Path.of(settings.dir()), snapshot, isNewest);

        if (isNewest && repository.countAll() > 0) {
            lookupBuilder.rebuild();
        }
        snapshotRepository.recordImport(snapshot, summary.processed());
        log.info("스냅샷 {} 적재 완료: 처리 {} / 실패 {}", snapshot, summary.processed(), summary.failed());
    }

    public ImportSummary importFrom(Path dir, String snapshot, boolean isNewest) {
        if (!Files.isDirectory(dir)) {
            log.warn("CSV 디렉토리가 없다: {}", dir);
            return new ImportSummary(0, 0);
        }

        long started = System.currentTimeMillis();
        long processed = 0;
        long failed = 0;

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> targets = files
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .filter(p -> settings.matches(p.getFileName().toString()))
                    .sorted()
                    .toList();

            log.info("적재 대상 파일 {}개", targets.size());
            for (Path file : targets) {
                ImportSummary one = importFile(file, snapshot, isNewest);
                processed += one.processed();
                failed += one.failed();
            }
        } catch (IOException e) {
            throw new IllegalStateException("CSV 디렉토리 탐색 실패: " + dir, e);
        }

        ImportSummary total = new ImportSummary(processed, failed);
        log.info("전체 적재 완료: 처리 {} / 실패 {} ({}초)",
                processed, failed, (System.currentTimeMillis() - started) / 1000);
        return total;
    }

    private ImportSummary importFile(Path file, String snapshot, boolean isNewest) {
        log.info("적재 시작: {}", file.getFileName());
        long started = System.currentTimeMillis();
        long processed = 0;
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
                    processed += flush(buffer, snapshot, isNewest, file, processed);
                    if (processed % PROGRESS_INTERVAL < settings.batchSize()) {
                        log.info("  {} 진행: {}행 ({}초)", file.getFileName(), processed,
                                (System.currentTimeMillis() - started) / 1000);
                    }
                }
            }
            processed += flush(buffer, snapshot, isNewest, file, processed);
        } catch (IOException e) {
            throw new IllegalStateException("CSV 읽기 실패: " + file, e);
        }

        ImportSummary summary = new ImportSummary(processed, failed);
        if (summary.failureRate() > 0.01) {
            log.error("{} 실패율 {}%가 1%를 초과한다. CSV 형식이 바뀌었을 수 있다.",
                    file.getFileName(), Math.round(summary.failureRate() * 1000) / 10.0);
        }
        log.info("적재 완료: {} — 처리 {} / 실패 {} ({}초)", file.getFileName(),
                processed, failed, (System.currentTimeMillis() - started) / 1000);
        return summary;
    }

    /**
     * 슬림 스냅샷 행은 항상 적재하고, 이 스냅샷이 최신일 때만 store(전체)도 적재한다.
     * DB 적재 실패 시 파일명·진행 행수를 남기고 원 예외를 cause로 보존한다.
     */
    private int flush(List<Store> buffer, String snapshot, boolean isNewest, Path file, long processedSoFar) {
        if (buffer.isEmpty()) {
            return 0;
        }
        try {
            snapshotRepository.insertSnapshotBatch(snapshot, buffer);
            if (isNewest) {
                repository.insertBatch(buffer);
            }
            int n = buffer.size();
            buffer.clear();
            return n;
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "DB 적재 실패: %s (%d행까지 처리한 상태에서 실패)"
                            .formatted(file.getFileName(), processedSoFar),
                    e);
        }
    }
}
```

- [ ] **Step 2: Rewrite StoreImportRunnerUnitTest**

`src/test/java/com/example/commercialarea/importer/StoreImportRunnerUnitTest.java` 를 다음으로 교체(새 협력자 `SnapshotRepository` 목 포함, 새 로직 검증):
```java
package com.example.commercialarea.importer;

import com.example.commercialarea.config.ImportProperties;
import com.example.commercialarea.snapshot.SnapshotRepository;
import com.example.commercialarea.store.Store;
import com.example.commercialarea.store.StoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StoreRepository/SnapshotRepository/LookupBuilder를 목으로 대체해 run()의 분기 로직을
 * 검증한다. 실제 DB(Testcontainers)가 필요 없는 순수 단위 테스트다.
 */
class StoreImportRunnerUnitTest {

    @TempDir Path csvDir;

    private void writeFixture(String fileName) throws IOException {
        byte[] content = getClass().getResourceAsStream("/fixtures/sample-stores.csv").readAllBytes();
        Files.write(csvDir.resolve(fileName), content);
    }

    private ImportProperties props(String snapshot) {
        return new ImportProperties(true, csvDir.toString(), List.of("서울"), 100, snapshot);
    }

    private StoreImportRunner runner(ImportProperties p, StoreRepository repo,
                                     SnapshotRepository snap, LookupBuilder lookup) {
        return new StoreImportRunner(p, repo, snap, lookup);
    }

    @Test
    void snapshot이_없으면_예외를_던진다() {
        StoreImportRunner runner = runner(props(null),
                mock(StoreRepository.class), mock(SnapshotRepository.class), mock(LookupBuilder.class));

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot");
    }

    @Test
    void 이미_적재된_스냅샷이면_아무것도_하지_않는다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repo = mock(StoreRepository.class);
        SnapshotRepository snap = mock(SnapshotRepository.class);
        LookupBuilder lookup = mock(LookupBuilder.class);
        when(snap.hasSnapshot("202606")).thenReturn(true);

        runner(props("202606"), repo, snap, lookup).run(new DefaultApplicationArguments());

        verify(snap, never()).insertSnapshotBatch(eq("202606"), anyList());
        verify(repo, never()).insertBatch(anyList());
        verify(lookup, never()).rebuild();
    }

    @Test
    void 최신_스냅샷이면_store를_비우고_슬림과_store에_모두_적재하고_룩업을_재생성한다() throws Exception {
        writeFixture("소상공인_서울_202606.csv");
        StoreRepository repo = mock(StoreRepository.class);
        SnapshotRepository snap = mock(SnapshotRepository.class);
        LookupBuilder lookup = mock(LookupBuilder.class);
        when(snap.hasSnapshot("202606")).thenReturn(false);
        when(snap.latestSnapshotYm()).thenReturn(Optional.empty()); // 첫 스냅샷 = 최신
        when(repo.insertBatch(anyList())).thenAnswer(i -> ((List<?>) i.getArgument(0)).size());
        when(repo.countAll()).thenReturn(3L);

        runner(props("202606"), repo, snap, lookup).run(new DefaultApplicationArguments());

        verify(repo, times(1)).deleteAllStores();
        verify(snap, times(1)).insertSnapshotBatch(eq("202606"), anyList());
        verify(repo, times(1)).insertBatch(anyList());
        verify(lookup, times(1)).rebuild();
        verify(snap, times(1)).recordImport(eq("202606"), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 과거_백필_스냅샷이면_store를_건드리지_않고_슬림만_적재한다() throws Exception {
        writeFixture("소상공인_서울_202503.csv");
        StoreRepository repo = mock(StoreRepository.class);
        SnapshotRepository snap = mock(SnapshotRepository.class);
        LookupBuilder lookup = mock(LookupBuilder.class);
        when(snap.hasSnapshot("202503")).thenReturn(false);
        when(snap.latestSnapshotYm()).thenReturn(Optional.of("202506")); // 이미 더 최신이 있다

        runner(props("202503"), repo, snap, lookup).run(new DefaultApplicationArguments());

        verify(repo, never()).deleteAllStores();
        verify(repo, never()).insertBatch(anyList());
        verify(lookup, never()).rebuild();
        verify(snap, times(1)).insertSnapshotBatch(eq("202503"), anyList());
        verify(snap, times(1)).recordImport(eq("202503"), org.mockito.ArgumentMatchers.anyLong());
    }
}
```

- [ ] **Step 3: Update StoreImportRunnerTest (integration)**

이 테스트는 `@Autowired StoreImportRunner runner`를 주입받고(Spring이 `SnapshotRepository`
포함 모든 의존성을 자동 주입하므로 생성자 관련 수정은 불필요), `runner.importFrom(csvDir)`를
직접 호출한다. `importFrom`의 새 시그니처(`importFrom(Path, String snapshot, boolean isNewest)`)에
맞춰 다음 세 가지만 수정한다.

1. `@BeforeEach clean()`에 두 줄을 추가한다(importFrom이 이제 store_snapshot에도 기록하므로):
```java
        jdbc.sql("DELETE FROM store_snapshot").update();
        jdbc.sql("DELETE FROM import_log").update();
```
2. 네 곳의 `runner.importFrom(csvDir)` 호출을 모두 `runner.importFrom(csvDir, "202606", true)`로
   바꾼다(테스트 4개 중 `importFrom`을 호출하는 곳 전부 — `두_번_적재해도...` 테스트의 두 호출 포함).
3. `디렉토리의_CSV를_적재하고_깨진_행은_건너뛴다` 테스트 끝에 store_snapshot에도 슬림 행이
   적재됐는지 단언을 추가한다:
```java
        assertThat(jdbc.sql("SELECT COUNT(*) FROM store_snapshot WHERE snapshot_ym='202606'")
                .query(Long.class).single()).isEqualTo(5);
```

기존 단언(processed=5, countAll=5 등)은 그대로 통과한다: `isNewest=true`이면 `importFrom`이
store와 store_snapshot에 모두 INSERT IGNORE로 적재하므로 두 번 호출해도 store는 5건을
유지한다(멱등). `run()` 레벨의 import_log 멱등 스킵은 Task 4 Step 2의 단위 테스트가 검증한다.

- [ ] **Step 4: Run importer tests**

Run: `./gradlew test --tests '*StoreImportRunnerUnitTest' --tests '*StoreImportRunnerTest'`
Expected: PASS. 단위 테스트 4건 + 통합 테스트(기존 수+추가 단언). Docker 필요(통합).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/importer/StoreImportRunner.java \
        src/test/java/com/example/commercialarea/importer/StoreImportRunnerUnitTest.java \
        src/test/java/com/example/commercialarea/importer/StoreImportRunnerTest.java
git commit -m "feat: 스냅샷 단위 멱등 적재로 StoreImportRunner 재작성"
```

---

## Task 5: TrendQuery + TrendResponse + TrendFilterSql

**Files:**
- Create: `src/main/java/com/example/commercialarea/snapshot/TrendQuery.java`
- Create: `src/main/java/com/example/commercialarea/snapshot/SnapshotCount.java`
- Create: `src/main/java/com/example/commercialarea/snapshot/SnapshotTrend.java`
- Create: `src/main/java/com/example/commercialarea/snapshot/TrendResponse.java`
- Create: `src/main/java/com/example/commercialarea/snapshot/TrendFilterSql.java`

- [ ] **Step 1: Create the records + filter SQL**

`TrendQuery.java`:
```java
package com.example.commercialarea.snapshot;

/** 추이 요청 필터. 지역(시도/시군구/행정동) + 업종(대/중/소). bbox·상호명 없음. */
public record TrendQuery(String sido, String sgg, String dong,
                         String large, String medium, String small) {
}
```

`SnapshotCount.java`:
```java
package com.example.commercialarea.snapshot;

/** 분기별 점포수 집계 행. */
record SnapshotCount(String ym, long count) {
}
```

`SnapshotTrend.java`:
```java
package com.example.commercialarea.snapshot;

/** 응답 항목. opened/closed는 시계열 첫 분기에서 null. */
public record SnapshotTrend(String ym, long count, Long opened, Long closed) {
}
```

`TrendResponse.java`:
```java
package com.example.commercialarea.snapshot;

import java.util.List;

public record TrendResponse(List<SnapshotTrend> snapshots) {
}
```

`TrendFilterSql.java`:
```java
package com.example.commercialarea.snapshot;

import java.util.Map;

/**
 * 추이 쿼리의 지역·업종 동등조건을 조립한다. alias는 컬럼 접두어("s." 등, 없으면 "").
 * paramSuffix는 한 SQL 안에서 같은 필터를 두 번 쓸 때 파라미터 이름 충돌을 막는 접미어다.
 */
final class TrendFilterSql {

    private TrendFilterSql() {
    }

    static void appendConditions(StringBuilder sql, Map<String, Object> params,
                                 TrendQuery q, String alias, String paramSuffix) {
        eq(sql, params, alias, "sido_code", "sido" + paramSuffix, q.sido());
        eq(sql, params, alias, "sgg_code", "sgg" + paramSuffix, q.sgg());
        eq(sql, params, alias, "dong_code", "dong" + paramSuffix, q.dong());
        eq(sql, params, alias, "large_code", "large" + paramSuffix, q.large());
        eq(sql, params, alias, "medium_code", "medium" + paramSuffix, q.medium());
        eq(sql, params, alias, "small_code", "small" + paramSuffix, q.small());
    }

    private static void eq(StringBuilder sql, Map<String, Object> params,
                           String alias, String column, String param, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(alias).append(column).append(" = :").append(param);
            params.put(param, value.trim());
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/commercialarea/snapshot/TrendQuery.java \
        src/main/java/com/example/commercialarea/snapshot/SnapshotCount.java \
        src/main/java/com/example/commercialarea/snapshot/SnapshotTrend.java \
        src/main/java/com/example/commercialarea/snapshot/TrendResponse.java \
        src/main/java/com/example/commercialarea/snapshot/TrendFilterSql.java
git commit -m "feat: 추이 요청·응답 DTO와 TrendFilterSql 추가"
```

---

## Task 6: SnapshotRepository 추이 쿼리

`SnapshotRepository`에 카운트 시계열과 개·폐업 집합차 쿼리를 추가한다. 검증은 Task 7의 서비스 테스트가 종단으로 수행하므로, 이 태스크는 컴파일 + 무회귀만 확인한다.

**Files:**
- Modify: `src/main/java/com/example/commercialarea/snapshot/SnapshotRepository.java`

- [ ] **Step 1: Add imports**

`SnapshotRepository.java` import 블록에 추가:
```java
import java.util.HashMap;
import java.util.Map;
```

- [ ] **Step 2: Add three query methods**

`SnapshotRepository` 클래스 안에 추가:
```java
    /**
     * 적재된 모든 스냅샷(import_log)에 대해 필터에 맞는 점포수를 분기순으로 반환한다.
     * 필터를 LEFT JOIN의 ON 조건에 넣어, 해당 분기에 매칭 0건이어도 count 0으로 계열에 남긴다.
     */
    public java.util.List<SnapshotCount> countBySnapshot(TrendQuery filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.snapshot_ym AS ym, COUNT(s.store_id) AS cnt
                FROM import_log l
                LEFT JOIN store_snapshot s
                  ON s.snapshot_ym = l.snapshot_ym
                """);
        Map<String, Object> params = new HashMap<>();
        TrendFilterSql.appendConditions(sql, params, filter, "s.", "");
        sql.append(" GROUP BY l.snapshot_ym ORDER BY l.snapshot_ym");

        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new SnapshotCount(rs.getString("ym"), rs.getLong("cnt")))
                .list();
    }

    /** curr에 있고(필터 적용) prev에는 store_id가 없는 신규 점포 수. */
    public long openedBetween(String prev, String curr, TrendQuery filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM store_snapshot c
                WHERE c.snapshot_ym = :curr
                  AND NOT EXISTS (SELECT 1 FROM store_snapshot p
                                  WHERE p.snapshot_ym = :prev AND p.store_id = c.store_id)
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("curr", curr);
        params.put("prev", prev);
        TrendFilterSql.appendConditions(sql, params, filter, "c.", "");
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }

    /** prev에 있고(필터 적용) curr에는 store_id가 없는 폐업 점포 수. */
    public long closedBetween(String prev, String curr, TrendQuery filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM store_snapshot p
                WHERE p.snapshot_ym = :prev
                  AND NOT EXISTS (SELECT 1 FROM store_snapshot c
                                  WHERE c.snapshot_ym = :curr AND c.store_id = p.store_id)
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("curr", curr);
        params.put("prev", prev);
        TrendFilterSql.appendConditions(sql, params, filter, "p.", "");
        return jdbc.sql(sql.toString()).params(params).query(Long.class).single();
    }
```

- [ ] **Step 3: Verify compile + no regression**

Run: `./gradlew test --tests '*SnapshotRepositoryTest'`
Expected: PASS (기존 4건 무회귀; 새 메서드는 Task 7에서 검증).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/commercialarea/snapshot/SnapshotRepository.java
git commit -m "feat: 분기별 카운트·개폐업 집합차 추이 쿼리 추가"
```

---

## Task 7: TrendService

**Files:**
- Create: `src/main/java/com/example/commercialarea/snapshot/TrendService.java`
- Test: `src/test/java/com/example/commercialarea/snapshot/TrendServiceTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/commercialarea/snapshot/TrendServiceTest.java`:
```java
package com.example.commercialarea.snapshot;

import com.example.commercialarea.store.Store;
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
class TrendServiceTest {

    @Autowired TrendService service;
    @Autowired SnapshotRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store_snapshot").update();
        jdbc.sql("DELETE FROM import_log").update();
    }

    private static Store s(String id, String sgg, String small) {
        return new Store(id, "가게" + id, null,
                "I2", "음식", "I201", "중분류", small, "소분류",
                "11", "서울특별시", sgg, "구", sgg + "510", "동",
                "지번", null, "도로명", null, 127.0, 37.5);
    }

    /** 스냅샷을 슬림 적재하고 import_log에 등록한다. */
    private void snapshot(String ym, List<Store> rows) {
        repository.insertSnapshotBatch(ym, rows);
        repository.recordImport(ym, rows.size());
    }

    private static TrendQuery all() {
        return new TrendQuery(null, null, null, null, null, null);
    }

    @Test
    void 분기별_점포수_시계열을_반환한다() {
        snapshot("202503", List.of(s("A1", "11680", "C1"), s("A2", "11680", "C1")));
        snapshot("202506", List.of(s("A1", "11680", "C1"), s("A2", "11680", "C1"), s("A3", "11680", "C1")));

        TrendResponse r = service.trend(all());

        assertThat(r.snapshots()).extracting(SnapshotTrend::ym).containsExactly("202503", "202506");
        assertThat(r.snapshots()).extracting(SnapshotTrend::count).containsExactly(2L, 3L);
    }

    @Test
    void 첫_분기는_개폐업이_null이고_이후는_집합차로_계산된다() {
        // 202503: A1,A2  → 202506: A2,A3  (A1 폐업, A3 신규, A2 생존)
        snapshot("202503", List.of(s("A1", "11680", "C1"), s("A2", "11680", "C1")));
        snapshot("202506", List.of(s("A2", "11680", "C1"), s("A3", "11680", "C1")));

        TrendResponse r = service.trend(all());

        SnapshotTrend first = r.snapshots().get(0);
        SnapshotTrend second = r.snapshots().get(1);
        assertThat(first.opened()).isNull();
        assertThat(first.closed()).isNull();
        assertThat(second.opened()).isEqualTo(1L); // A3
        assertThat(second.closed()).isEqualTo(1L); // A1
    }

    @Test
    void 지역_필터가_적용된다() {
        snapshot("202506", List.of(s("A1", "11680", "C1"), s("A2", "11110", "C1")));

        TrendResponse r = service.trend(new TrendQuery(null, "11680", null, null, null, null));

        assertThat(r.snapshots()).hasSize(1);
        assertThat(r.snapshots().get(0).count()).isEqualTo(1L);
    }

    @Test
    void 필터로_비는_분기도_count_0으로_계열에_남는다() {
        snapshot("202503", List.of(s("A1", "11110", "C1")));           // 종로구만
        snapshot("202506", List.of(s("A2", "11680", "C1")));           // 강남구만

        TrendResponse r = service.trend(new TrendQuery(null, "11680", null, null, null, null));

        assertThat(r.snapshots()).extracting(SnapshotTrend::ym).containsExactly("202503", "202506");
        assertThat(r.snapshots()).extracting(SnapshotTrend::count).containsExactly(0L, 1L);
    }

    @Test
    void 스냅샷이_없으면_빈_계열이다() {
        assertThat(service.trend(all()).snapshots()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TrendServiceTest'`
Expected: compile failure (`TrendService` 없음). Docker 필요.

- [ ] **Step 3: Write TrendService**

`src/main/java/com/example/commercialarea/snapshot/TrendService.java`:
```java
package com.example.commercialarea.snapshot;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TrendService {

    private final SnapshotRepository repository;

    public TrendService(SnapshotRepository repository) {
        this.repository = repository;
    }

    public TrendResponse trend(TrendQuery query) {
        List<SnapshotCount> counts = repository.countBySnapshot(query);

        List<SnapshotTrend> out = new ArrayList<>(counts.size());
        for (int i = 0; i < counts.size(); i++) {
            SnapshotCount cur = counts.get(i);
            Long opened = null;
            Long closed = null;
            if (i > 0) {
                String prev = counts.get(i - 1).ym();
                opened = repository.openedBetween(prev, cur.ym(), query);
                closed = repository.closedBetween(prev, cur.ym(), query);
            }
            out.add(new SnapshotTrend(cur.ym(), cur.count(), opened, closed));
        }
        return new TrendResponse(out);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TrendServiceTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/snapshot/TrendService.java \
        src/test/java/com/example/commercialarea/snapshot/TrendServiceTest.java
git commit -m "feat: 추이 시계열·개폐업을 조립하는 TrendService 추가"
```

---

## Task 8: TrendController + API 테스트

**Files:**
- Create: `src/main/java/com/example/commercialarea/snapshot/TrendController.java`
- Test: `src/test/java/com/example/commercialarea/snapshot/TrendApiTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/commercialarea/snapshot/TrendApiTest.java`:
```java
package com.example.commercialarea.snapshot;

import com.example.commercialarea.store.Store;
import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class TrendApiTest {

    @Autowired MockMvc mvc;
    @Autowired SnapshotRepository repository;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store_snapshot").update();
        jdbc.sql("DELETE FROM import_log").update();
    }

    private static Store s(String id, String sgg, String small) {
        return new Store(id, "가게" + id, null,
                "I2", "음식", "I201", "중분류", small, "소분류",
                "11", "서울특별시", sgg, "구", sgg + "510", "동",
                "지번", null, "도로명", null, 127.0, 37.5);
    }

    private void snapshot(String ym, List<Store> rows) {
        repository.insertSnapshotBatch(ym, rows);
        repository.recordImport(ym, rows.size());
    }

    @Test
    void 추이_응답_구조를_반환한다() throws Exception {
        snapshot("202503", List.of(s("A1", "11680", "C1"), s("A2", "11680", "C1")));
        snapshot("202506", List.of(s("A2", "11680", "C1"), s("A3", "11680", "C1")));

        mvc.perform(get("/api/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.snapshots[0].ym").value("202503"))
                .andExpect(jsonPath("$.snapshots[0].count").value(2))
                .andExpect(jsonPath("$.snapshots[0].opened").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.snapshots[1].opened").value(1))
                .andExpect(jsonPath("$.snapshots[1].closed").value(1));
    }

    @Test
    void 지역_필터가_반영된다() throws Exception {
        snapshot("202506", List.of(s("A1", "11680", "C1"), s("A2", "11110", "C1")));

        mvc.perform(get("/api/trend?sgg=11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].count").value(1));
    }

    @Test
    void 스냅샷이_없으면_빈_배열이다() throws Exception {
        mvc.perform(get("/api/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots.length()").value(0));
    }
}
```

> 참고: `SnapshotTrend.opened/closed`는 boxed `Long`이라 null이어도 Jackson이 필드를 `null`로 직렬화해 JSON에 포함한다. 그래서 첫 분기 검증은 `doesNotExist()`가 아니라 위처럼 `value(org.hamcrest.Matchers.nullValue())`를 쓴다(별도 import 불필요, 완전수식 사용).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*TrendApiTest'`
Expected: 실패(컨트롤러 없음 → 404). Docker 필요.

- [ ] **Step 3: Write TrendController**

`src/main/java/com/example/commercialarea/snapshot/TrendController.java`:
```java
package com.example.commercialarea.snapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrendController {

    private final TrendService service;

    public TrendController(TrendService service) {
        this.service = service;
    }

    @GetMapping("/api/trend")
    public TrendResponse trend(
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sgg,
            @RequestParam(required = false) String dong,
            @RequestParam(required = false) String large,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String small) {

        return service.trend(new TrendQuery(sido, sgg, dong, large, medium, small));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*TrendApiTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/snapshot/TrendController.java \
        src/test/java/com/example/commercialarea/snapshot/TrendApiTest.java
git commit -m "feat: GET /api/trend 엔드포인트 추가"
```

---

## Task 9: UI — "상권 추이" 섹션 (인라인 SVG 차트)

**Files:**
- Modify: `src/main/resources/templates/index.html`
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/app.css`

- [ ] **Step 1: Add markup**

`src/main/resources/templates/index.html` 의 분석 그룹(`<div class="group analysis">...</div>`) 바로 다음, `</aside>` 앞에 추가:
```html
      <div class="group trend">
        <button id="trend" type="button" class="trend-btn">상권 추이 보기</button>
        <div id="trend-result" class="trend-result" hidden>
          <div id="trend-chart" class="trend-chart"></div>
          <table class="trend-table">
            <thead>
              <tr><th>분기</th><th>점포수</th><th>신규</th><th>폐업</th></tr>
            </thead>
            <tbody id="trend-body"></tbody>
          </table>
        </div>
      </div>
```

- [ ] **Step 2: Add JS (inline SVG line chart + table)**

`src/main/resources/static/app.js` 맨 끝에 추가(기존 `el`, `filters`, `escapeHtml` 재사용):
```javascript
/* ---------- 상권 추이 ---------- */

function trendParams() {
  const params = new URLSearchParams();
  for (const key of ['sido', 'sgg', 'dong', 'large', 'medium', 'small']) {
    if (filters[key]) params.set(key, filters[key]);
  }
  return params;
}

function renderTrendChart(snapshots) {
  const W = 260, H = 90, padX = 8, padY = 12;
  if (snapshots.length < 2) {
    return '<p class="hint">추이를 보려면 2개 이상 분기 스냅샷이 필요합니다.</p>';
  }
  const counts = snapshots.map(s => s.count);
  const max = Math.max(...counts), min = Math.min(...counts);
  const span = max - min || 1;
  const stepX = (W - padX * 2) / (snapshots.length - 1);
  const points = snapshots.map((s, i) => {
    const x = padX + i * stepX;
    const y = padY + (H - padY * 2) * (1 - (s.count - min) / span);
    return [x, y];
  });
  const poly = points.map(p => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
  const dots = points.map(p =>
    `<circle cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="2.5" fill="#1c5ed6"/>`).join('');
  return `<svg viewBox="0 0 ${W} ${H}" class="trend-svg" role="img" aria-label="점포수 추이">
      <polyline fill="none" stroke="#1c5ed6" stroke-width="2" points="${poly}"/>
      ${dots}
    </svg>`;
}

function renderTrend(data) {
  el('trend-result').hidden = false;
  el('trend-chart').innerHTML = renderTrendChart(data.snapshots);

  el('trend-body').innerHTML = data.snapshots.map(s => {
    const opened = s.opened == null ? '—' : `<span class="up">▲ ${s.opened.toLocaleString()}</span>`;
    const closed = s.closed == null ? '—' : `<span class="down">▼ ${s.closed.toLocaleString()}</span>`;
    return `<tr>
      <td>${escapeHtml(s.ym)}</td>
      <td>${s.count.toLocaleString()}</td>
      <td>${opened}</td>
      <td>${closed}</td>
    </tr>`;
  }).join('') || '<tr class="empty"><td colspan="4">적재된 스냅샷이 없습니다.</td></tr>';
}

async function loadTrend() {
  const button = el('trend');
  button.disabled = true;
  button.textContent = '불러오는 중…';
  try {
    const data = await fetch('/api/trend?' + trendParams()).then(r => r.json());
    if (data.error) {
      console.warn('추이 조회 실패', data);
      return;
    }
    renderTrend(data);
  } catch (e) {
    console.error('추이 조회 실패', e);
  } finally {
    button.disabled = false;
    button.textContent = '상권 추이 보기';
  }
}

el('trend').addEventListener('click', loadTrend);
```

- [ ] **Step 3: Add styles**

`src/main/resources/static/app.css` 맨 끝에 추가:
```css
/* ---------- 상권 추이 ---------- */
.trend-btn {
  width: 100%;
  padding: 0.5rem;
  margin-top: 0.5rem;
  background: #0f766e;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.9rem;
}
.trend-btn:disabled { opacity: 0.6; cursor: default; }

.trend-result { margin-top: 0.75rem; }
.trend-chart { margin-bottom: 0.5rem; }
.trend-svg { width: 100%; height: auto; background: #f7f7f8; border-radius: 4px; }

.trend-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.trend-table th, .trend-table td { padding: 0.25rem 0.3rem; text-align: right; border-bottom: 1px solid #f0f0f0; }
.trend-table th:first-child, .trend-table td:first-child { text-align: left; }
.trend-table .up { color: #059669; }
.trend-table .down { color: #dc2626; }
.trend-table .empty td { text-align: center; color: #999; }
```

- [ ] **Step 4: Verify**

Run: `node --check src/main/resources/static/app.js` (node 있으면; 없으면 추가 블록의 중괄호·백틱 균형을 눈으로 확인).
Run: `./gradlew compileJava`
Expected: JS OK, BUILD SUCCESSFUL. 브라우저 시각 확인은 별도(2개 이상 스냅샷 적재 필요).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/index.html src/main/resources/static/app.js src/main/resources/static/app.css
git commit -m "feat: 상권 추이 버튼과 SVG 꺾은선·개폐업 표 UI 추가"
```

---

## Task 10: 설정·문서 배선

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `README.md`

- [ ] **Step 1: application.yml**

`app.import` 블록에 `snapshot`을 추가(기본은 빈 값 — 환경변수로 주입):
```yaml
app:
  import:
    enabled: true
    dir: /data/csv
    include: ["서울", "경기"]
    batch-size: 1000
    snapshot: ${APP_IMPORT_SNAPSHOT:}
```

- [ ] **Step 2: docker-compose.yml**

app 서비스 `environment`에 추가(기존 `APP_IMPORT_INCLUDE` 아래):
```yaml
      APP_IMPORT_SNAPSHOT: ${SNAPSHOT}
```

- [ ] **Step 3: .env.example**

`IMPORT_INCLUDE` 아래에 추가:
```
SNAPSHOT=202606
```

- [ ] **Step 4: README.md**

`## 설정` 표에 행 추가:
```markdown
| `SNAPSHOT` | 적재할 스냅샷 분기(YYYYMM). 적재 시 필수 | `202606` |
```
`## 기능` 목록에 추가:
```markdown
- 상권 추이 — "상권 추이 보기"로 선택한 지역·업종의 분기별 점포수 추이와 개·폐업(신규/폐업) 건수.
  분기별 스냅샷을 누적 적재해야 동작한다.
```
`## API` 표에 행 추가:
```markdown
| `GET /api/trend` | 지역+업종 필터 → 분기별 점포수·개폐업 추이 |
```
`## 알려진 제약`에 추가:
```markdown
- 개·폐업은 `store_id`(상가업소번호)가 분기 간 유지된다고 가정한다. 공단이 ID를 재발급하면
  같은 점포가 폐업+신규로 이중 집계되어 개·폐업 수가 과대평가될 수 있다.
- 상권 추이는 2개 이상 분기 스냅샷이 적재돼야 의미가 생긴다.
```
`### 다른 지역 적재하기` 아래에 새 소절을 추가:
```markdown
### 다른 분기 스냅샷 추가하기

추이 기능은 분기별 스냅샷을 누적해야 동작한다. 새 분기 CSV를 받아 `.env`의 `CSV_DIR`을
그 분기 디렉토리로, `SNAPSHOT`을 그 분기(YYYYMM)로 바꾼 뒤 다시 올린다. 볼륨은 지우지
않는다(누적해야 하므로).

\`\`\`
CSV_DIR=/path/to/2025Q1_dir
SNAPSHOT=202503
\`\`\`

\`\`\`bash
docker compose up -d --build
\`\`\`

같은 `SNAPSHOT`을 두 번 적재하면 `import_log` 기준으로 자동으로 건너뛴다. 가장 최신
`SNAPSHOT`이 지도·분석에 쓰이는 `store`가 된다.
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml .env.example docker-compose.yml README.md
git commit -m "docs: SNAPSHOT 설정 배선과 추이 기능 README 반영"
```

---

## Final Verification

- [ ] **전체 테스트**

Run: `./gradlew test`
Expected: 전부 PASS — 신규(ImportProperties +2, SnapshotRepository 4, TrendService 5, TrendApi 3, StoreImportRunnerUnit 4) + 기존 무회귀.

- [ ] **전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.
