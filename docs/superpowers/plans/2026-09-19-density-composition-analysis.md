# 밀도·업종구성 분석 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현재 지도 화면(bbox)에 대해 밀도(건/km²)·업종 구성 비중·소분류 순위+특화도(LQ)를 계산하는 `GET /api/analysis`와 화면 UI를 추가한다.

**Architecture:** 기존 `store` 패키지의 Controller→Service→Repository 패턴을 따른다. `StoreFilterSql`을 `MapQuery`/`AnalysisQuery` 공용 인터페이스(`StoreFilter`)로 일반화해 필터 조건을 재사용한다. 밀도 계산은 순수 유틸(`GeoArea`), 특화도는 `industry` 테이블의 전역 집계를 사용한다. 온디맨드 호출(버튼)로 부하를 통제한다.

**Tech Stack:** Java 21, Spring Boot(웹 MVC, `JdbcClient`), MySQL 8.4, Testcontainers, JUnit5 + AssertJ + MockMvc, 바닐라 JS + Leaflet.

**Design spec:** `docs/superpowers/specs/2026-09-19-density-composition-analysis-design.md`

---

## File Structure

**신규 파일**
- `src/main/java/com/example/commercialarea/store/GeoArea.java` — bbox → km² 순수 유틸
- `src/main/java/com/example/commercialarea/store/StoreFilter.java` — bbox+필터 접근자 인터페이스
- `src/main/java/com/example/commercialarea/store/AnalysisQuery.java` — 분석 요청 record(+bbox 검증)
- `src/main/java/com/example/commercialarea/store/CategoryCount.java` — 구성 집계 행(code,name,count)
- `src/main/java/com/example/commercialarea/store/SmallCount.java` — 소분류 순위 집계 행
- `src/main/java/com/example/commercialarea/store/CompositionItem.java` — 응답 구성 항목
- `src/main/java/com/example/commercialarea/store/RankingItem.java` — 응답 순위 항목
- `src/main/java/com/example/commercialarea/store/AnalysisResponse.java` — 응답 DTO
- `src/main/java/com/example/commercialarea/store/AnalysisService.java` — 조립 로직
- `src/main/java/com/example/commercialarea/store/AnalysisController.java` — `GET /api/analysis`
- `src/test/java/com/example/commercialarea/store/GeoAreaTest.java`
- `src/test/java/com/example/commercialarea/store/AnalysisQueryTest.java`
- `src/test/java/com/example/commercialarea/store/AnalysisServiceTest.java`
- `src/test/java/com/example/commercialarea/store/AnalysisApiTest.java`

**변경 파일**
- `src/main/java/com/example/commercialarea/store/StoreFilterSql.java` — `appendWhere` 시그니처를 `StoreFilter`로 일반화
- `src/main/java/com/example/commercialarea/store/MapQuery.java` — `implements StoreFilter`
- `src/main/java/com/example/commercialarea/store/StoreRepository.java` — `groupCount`, `rankingSmall`, `industryCountsForSmall`, `industryTotal` 추가
- `src/main/resources/templates/index.html` — 분석 버튼/패널 마크업
- `src/main/resources/static/app.js` — 분석 호출·렌더
- `src/main/resources/static/app.css` — 분석 패널 스타일
- `README.md` — 기능·API·알려진 제약 갱신

---

## Task 1: GeoArea 유틸 (bbox → km²)

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/GeoArea.java`
- Test: `src/test/java/com/example/commercialarea/store/GeoAreaTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/commercialarea/store/GeoAreaTest.java`:
```java
package com.example.commercialarea.store;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoAreaTest {

    @Test
    void 알려진_bbox의_면적을_근사한다() {
        // 0.1도 x 0.1도, 위도 약 37도. 등장방형 근사로 약 98.2 km².
        double km2 = GeoArea.km2(37.0, 37.1, 127.0, 127.1);
        assertThat(km2).isCloseTo(98.2, Offset.offset(1.0));
    }

    @Test
    void 넓이가_0인_bbox는_0을_반환한다() {
        assertThat(GeoArea.km2(37.0, 37.0, 127.0, 127.1)).isZero();
        assertThat(GeoArea.km2(37.0, 37.1, 127.0, 127.0)).isZero();
    }

    @Test
    void 역전된_bbox는_0을_반환한다() {
        assertThat(GeoArea.km2(37.1, 37.0, 127.0, 127.1)).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*GeoAreaTest'`
Expected: 컴파일 실패(`GeoArea` 심볼 없음).

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/example/commercialarea/store/GeoArea.java`:
```java
package com.example.commercialarea.store;

/**
 * bbox(위경도 도 단위)의 근사 면적(km²)을 등장방형(equirectangular) 근사로 구한다.
 * 사각형 전체 면적이므로 비상업 공간(강·산·도로)을 포함한다 — 밀도는 화면 간 상대
 * 비교용 근사치다. 역전·0넓이 bbox는 0을 반환한다.
 */
public final class GeoArea {

    private static final double KM_PER_LAT_DEGREE = 110.574;
    private static final double KM_PER_LON_DEGREE = 111.320;

    private GeoArea() {
    }

    public static double km2(double minLat, double maxLat, double minLon, double maxLon) {
        double latKm = (maxLat - minLat) * KM_PER_LAT_DEGREE;
        double midLat = (minLat + maxLat) / 2.0;
        double lonKm = (maxLon - minLon) * KM_PER_LON_DEGREE * Math.cos(Math.toRadians(midLat));
        double area = latKm * lonKm;
        return area > 0 ? area : 0.0;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*GeoAreaTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/store/GeoArea.java \
        src/test/java/com/example/commercialarea/store/GeoAreaTest.java
git commit -m "feat: bbox 면적(km²) 근사 유틸 GeoArea 추가"
```

---

## Task 2: StoreFilter 인터페이스 추출 및 StoreFilterSql 일반화

기존 `StoreFilterSql.appendWhere`는 `MapQuery`만 받는다. `AnalysisQuery`도 같은 필터를 쓰도록 공용 인터페이스를 추출한다. 동작 변경이 없으므로 기존 테스트가 그대로 통과해야 한다(회귀 방지).

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/StoreFilter.java`
- Modify: `src/main/java/com/example/commercialarea/store/MapQuery.java` (선언부에 `implements StoreFilter`)
- Modify: `src/main/java/com/example/commercialarea/store/StoreFilterSql.java` (`appendWhere` 파라미터 타입)

- [ ] **Step 1: Create the interface**

`src/main/java/com/example/commercialarea/store/StoreFilter.java`:
```java
package com.example.commercialarea.store;

/**
 * bbox + 지역/업종/상호명 필터 조건. MapQuery와 AnalysisQuery가 함께 구현해
 * StoreFilterSql이 두 요청 타입을 모두 받도록 한다.
 */
public interface StoreFilter {
    double minLat();
    double maxLat();
    double minLon();
    double maxLon();
    String sido();
    String sgg();
    String dong();
    String large();
    String medium();
    String small();
    String q();
}
```

- [ ] **Step 2: Make MapQuery implement it**

`src/main/java/com/example/commercialarea/store/MapQuery.java` — record 선언부만 수정(접근자는 record가 이미 제공):
```java
public record MapQuery(
        double minLat, double maxLat,
        double minLon, double maxLon,
        int zoom,
        String sido, String sgg, String dong,
        String large, String medium, String small,
        String q
) implements StoreFilter {
```
(나머지 본문 `cellSize()`/`validate()`는 변경 없음.)

- [ ] **Step 3: Generalize appendWhere signature**

`src/main/java/com/example/commercialarea/store/StoreFilterSql.java` — 메서드 시그니처의 `MapQuery q`를 `StoreFilter q`로 변경(본문 로직은 동일):
```java
    static void appendWhere(StringBuilder sql, Map<String, Object> params, StoreFilter q) {
```

- [ ] **Step 4: Run existing store tests to verify no regression**

Run: `./gradlew test --tests '*StoreAggregateTest' --tests '*StoreQueryTest' --tests '*MapQueryTest' --tests '*MapApiTest'`
Expected: PASS (기존 테스트 전부 통과 — 리팩터링이 동작을 바꾸지 않았음).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/store/StoreFilter.java \
        src/main/java/com/example/commercialarea/store/MapQuery.java \
        src/main/java/com/example/commercialarea/store/StoreFilterSql.java
git commit -m "refactor: StoreFilter 인터페이스 추출, StoreFilterSql이 공용 필터를 받도록 일반화"
```

---

## Task 3: AnalysisQuery record

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/AnalysisQuery.java`
- Test: `src/test/java/com/example/commercialarea/store/AnalysisQueryTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/commercialarea/store/AnalysisQueryTest.java`:
```java
package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisQueryTest {

    private static AnalysisQuery at(double minLat, double maxLat, double minLon, double maxLon) {
        return new AnalysisQuery(minLat, maxLat, minLon, maxLon,
                null, null, null, null, null, null, null);
    }

    @Test
    void 정상_bbox는_통과한다() {
        assertThatCode(() -> at(37.0, 38.0, 126.0, 127.0).validate()).doesNotThrowAnyException();
    }

    @Test
    void 위도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(38.0, 37.0, 126.0, 127.0).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }

    @Test
    void 경도가_뒤집히면_INVALID_BBOX를_던진다() {
        assertThatThrownBy(() -> at(37.0, 38.0, 127.0, 126.0).validate())
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_BBOX");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*AnalysisQueryTest'`
Expected: 컴파일 실패(`AnalysisQuery` 심볼 없음).

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/example/commercialarea/store/AnalysisQuery.java`:
```java
package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;

/**
 * 화면(bbox) 분석 요청. 지도 필터와 동일하되 zoom이 없다.
 */
public record AnalysisQuery(
        double minLat, double maxLat,
        double minLon, double maxLon,
        String sido, String sgg, String dong,
        String large, String medium, String small,
        String q
) implements StoreFilter {

    public void validate() {
        if (minLat > maxLat || minLon > maxLon) {
            throw ApiException.badRequest("INVALID_BBOX",
                    "minLat/minLon은 maxLat/maxLon보다 클 수 없습니다.");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*AnalysisQueryTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/store/AnalysisQuery.java \
        src/test/java/com/example/commercialarea/store/AnalysisQueryTest.java
git commit -m "feat: 화면 분석 요청 AnalysisQuery 추가"
```

---

## Task 4: 응답·집계 DTO

집계 결과와 API 응답을 담는 record들. 로직이 없어 단독 테스트는 두지 않고 이후 서비스/API 테스트에서 검증한다.

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/CategoryCount.java`
- Create: `src/main/java/com/example/commercialarea/store/SmallCount.java`
- Create: `src/main/java/com/example/commercialarea/store/CompositionItem.java`
- Create: `src/main/java/com/example/commercialarea/store/RankingItem.java`
- Create: `src/main/java/com/example/commercialarea/store/AnalysisResponse.java`

- [ ] **Step 1: Create the records**

`src/main/java/com/example/commercialarea/store/CategoryCount.java`:
```java
package com.example.commercialarea.store;

/** 업종 구성 집계 행(레벨 무관). code/name은 집계 레벨의 코드·명칭. */
record CategoryCount(String code, String name, long count) {
}
```

`src/main/java/com/example/commercialarea/store/SmallCount.java`:
```java
package com.example.commercialarea.store;

/** 소분류 순위 집계 행. */
record SmallCount(String smallCode, String smallName, long count) {
}
```

`src/main/java/com/example/commercialarea/store/CompositionItem.java`:
```java
package com.example.commercialarea.store;

/**
 * 업종 구성 항목. level 은 집계 레벨("large"|"medium"|"small"),
 * share 는 화면 내 비중(0~1).
 */
public record CompositionItem(String level, String code, String name, long count, double share) {
}
```

`src/main/java/com/example/commercialarea/store/RankingItem.java`:
```java
package com.example.commercialarea.store;

/** 소분류 순위 항목. lq(특화지수)는 전역 집계가 없으면 null. */
public record RankingItem(String smallCode, String smallName, long count, Double lq) {
}
```

`src/main/java/com/example/commercialarea/store/AnalysisResponse.java`:
```java
package com.example.commercialarea.store;

import java.util.List;

public record AnalysisResponse(
        long total,
        double areaKm2,
        double densityPerKm2,
        List<CompositionItem> composition,
        List<RankingItem> ranking
) {
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/commercialarea/store/CategoryCount.java \
        src/main/java/com/example/commercialarea/store/SmallCount.java \
        src/main/java/com/example/commercialarea/store/CompositionItem.java \
        src/main/java/com/example/commercialarea/store/RankingItem.java \
        src/main/java/com/example/commercialarea/store/AnalysisResponse.java
git commit -m "feat: 분석 응답·집계 DTO 추가"
```

---

## Task 5: StoreRepository 집계 메서드

화면 필터로 업종 레벨별 그룹 카운트, 소분류 TOP N, `industry` 전역 집계를 조회하는 메서드를 추가한다.

**Files:**
- Modify: `src/main/java/com/example/commercialarea/store/StoreRepository.java`
- Test: `src/test/java/com/example/commercialarea/store/AnalysisServiceTest.java` (Task 6에서 이 메서드들을 서비스 경유로 검증. 이 태스크에서는 컴파일과 기존 테스트 무회귀만 확인)

- [ ] **Step 1: Add imports**

`src/main/java/com/example/commercialarea/store/StoreRepository.java` 상단 import 블록에 추가:
```java
import java.util.stream.Collectors;
```
(`java.util.List`, `java.util.Map`, `java.util.HashMap`은 이미 존재.)

- [ ] **Step 2: Add the four methods**

`StoreRepository` 클래스 안(예: `countFiltered` 아래)에 추가:
```java
    /**
     * 화면 필터를 적용해 지정한 업종 레벨 컬럼으로 그룹 카운트한다.
     * codeColumn/nameColumn 은 AnalysisService 가 넘기는 내부 상수(large_code 등)이지
     * 사용자 입력이 아니므로 SQL 주입 위험이 없다.
     */
    public List<CategoryCount> groupCount(StoreFilter filter, String codeColumn, String nameColumn) {
        StringBuilder sql = new StringBuilder("SELECT " + codeColumn + " AS code, "
                + nameColumn + " AS name, COUNT(*) AS cnt FROM store");
        Map<String, Object> params = new HashMap<>();
        StoreFilterSql.appendWhere(sql, params, filter);
        sql.append(" GROUP BY ").append(codeColumn).append(", ").append(nameColumn)
           .append(" ORDER BY cnt DESC");

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new CategoryCount(
                        rs.getString("code"), rs.getString("name"), rs.getLong("cnt")))
                .list();
    }

    /** 화면 필터를 적용해 소분류별 건수 상위 limit 개를 건수 내림차순으로 조회한다. */
    public List<SmallCount> rankingSmall(StoreFilter filter, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT small_code, small_name, COUNT(*) AS cnt FROM store");
        Map<String, Object> params = new HashMap<>();
        StoreFilterSql.appendWhere(sql, params, filter);
        sql.append(" GROUP BY small_code, small_name ORDER BY cnt DESC LIMIT :limit");
        params.put("limit", limit);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new SmallCount(
                        rs.getString("small_code"), rs.getString("small_name"), rs.getLong("cnt")))
                .list();
    }

    /** 주어진 소분류 코드들의 전역 점포수(industry 집계)를 소분류 코드→건수 맵으로 반환한다. */
    public Map<String, Long> industryCountsForSmall(List<String> smallCodes) {
        if (smallCodes.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT small_code, SUM(store_count) AS c
                        FROM industry
                        WHERE small_code IN (:codes)
                        GROUP BY small_code
                        """)
                .param("codes", smallCodes)
                .query((rs, n) -> Map.entry(rs.getString("small_code"), rs.getLong("c")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** industry 테이블 전체 점포수 합(특화도 분모). 비어 있으면 0. */
    public long industryTotal() {
        return jdbc.sql("SELECT COALESCE(SUM(store_count), 0) FROM industry")
                .query(Long.class).single();
    }
```

- [ ] **Step 3: Run compile + existing repository tests**

Run: `./gradlew test --tests '*StoreAggregateTest' --tests '*StoreRepositoryInsertTest'`
Expected: PASS (기존 리포지토리 동작 무회귀).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/commercialarea/store/StoreRepository.java
git commit -m "feat: 분석용 그룹 집계·소분류 순위·industry 전역 집계 쿼리 추가"
```

---

## Task 6: AnalysisService

집계 결과를 조립해 `AnalysisResponse`를 만든다. 적응형 구성 레벨 선택, 밀도 계산, 특화도(LQ) 계산을 담는다.

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/AnalysisService.java`
- Test: `src/test/java/com/example/commercialarea/store/AnalysisServiceTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/commercialarea/store/AnalysisServiceTest.java`:
```java
package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.assertj.core.data.Offset;
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
class AnalysisServiceTest {

    @Autowired AnalysisService service;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private int seq = 0;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
        jdbc.sql("DELETE FROM industry").update();
        seq = 0;
    }

    /** bbox 안(37.5x/127.0x)에 지정 업종의 상가 n건을 심는다. */
    private void addStores(int n, String large, String largeN,
                           String medium, String medN, String small, String smallN) {
        List<Store> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = "A" + (seq++);
            batch.add(new Store(id, "가게" + id, null,
                    large, largeN, medium, medN, small, smallN,
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "지번", null, "도로명", null,
                    127.0 + (seq % 40) * 0.0001, 37.5 + (seq % 40) * 0.0001));
        }
        repository.insertBatch(batch);
    }

    private void addIndustry(String large, String largeN, String medium, String medN,
                             String small, String smallN, int count) {
        jdbc.sql("""
                INSERT INTO industry
                  (large_code, large_name, medium_code, medium_name, small_code, small_name, store_count)
                VALUES (:lc, :ln, :mc, :mn, :sc, :sn, :cnt)
                """)
                .param("lc", large).param("ln", largeN)
                .param("mc", medium).param("mn", medN)
                .param("sc", small).param("sn", smallN)
                .param("cnt", count)
                .update();
    }

    private static AnalysisQuery view(String large, String medium, String small) {
        return new AnalysisQuery(37.5, 37.6, 127.0, 127.1,
                null, null, null, large, medium, small, null);
    }

    @Test
    void 총건수와_밀도를_계산한다() {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(5, "I2", "음식", "I202", "한식", "H1", "한식");

        AnalysisResponse r = service.analyze(view(null, null, null));

        assertThat(r.total()).isEqualTo(15);
        assertThat(r.areaKm2()).isCloseTo(97.6, Offset.offset(1.0));
        // 밀도 = 총건수 / 면적 (서비스가 두 값을 일관되게 나눴는지 확인)
        assertThat(r.densityPerKm2()).isCloseTo(15.0 / r.areaKm2(), Offset.offset(1e-6));
    }

    @Test
    void 업종_구성은_필터가_없으면_대분류_레벨이다() {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(4, "G2", "소매", "G201", "편의점", "R1", "편의점");

        AnalysisResponse r = service.analyze(view(null, null, null));

        assertThat(r.composition()).allMatch(c -> c.level().equals("large"));
        assertThat(r.composition()).extracting(CompositionItem::code)
                .containsExactly("I2", "G2");   // 건수 내림차순
        CompositionItem top = r.composition().get(0);
        assertThat(top.count()).isEqualTo(10);
        assertThat(top.share()).isCloseTo(10.0 / 14.0, Offset.offset(1e-9));
    }

    @Test
    void 대분류_필터를_걸면_구성이_중분류_레벨로_내려간다() {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(5, "I2", "음식", "I202", "한식", "H1", "한식");

        AnalysisResponse r = service.analyze(view("I2", null, null));

        assertThat(r.composition()).allMatch(c -> c.level().equals("medium"));
        assertThat(r.composition()).extracting(CompositionItem::code)
                .containsExactly("I201", "I202");
    }

    @Test
    void 중분류_필터를_걸면_구성이_소분류_레벨로_내려간다() {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");

        AnalysisResponse r = service.analyze(view("I2", "I201", null));

        assertThat(r.composition()).allMatch(c -> c.level().equals("small"));
        assertThat(r.composition()).hasSize(1);
        assertThat(r.composition().get(0).share()).isCloseTo(1.0, Offset.offset(1e-9));
    }

    @Test
    void 특화도는_지역_비중을_전체_비중으로_나눈_값이다() {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(5, "I2", "음식", "I202", "한식", "H1", "한식");
        // 전역: 카페 100 / 한식 900 → globalTotal 1000
        addIndustry("I2", "음식", "I201", "커피", "C1", "카페", 100);
        addIndustry("I2", "음식", "I202", "한식", "H1", "한식", 900);

        AnalysisResponse r = service.analyze(view(null, null, null));

        RankingItem cafe = r.ranking().stream()
                .filter(x -> x.smallCode().equals("C1")).findFirst().orElseThrow();
        RankingItem hansik = r.ranking().stream()
                .filter(x -> x.smallCode().equals("H1")).findFirst().orElseThrow();

        // 카페: (10/15)/(100/1000) = 6.667, 한식: (5/15)/(900/1000) = 0.370
        assertThat(cafe.lq()).isCloseTo(6.667, Offset.offset(0.01));
        assertThat(hansik.lq()).isCloseTo(0.370, Offset.offset(0.01));
        assertThat(r.ranking().get(0).smallCode()).isEqualTo("C1"); // 건수 내림차순
    }

    @Test
    void 전역_집계가_없는_소분류의_특화도는_null이다() {
        addStores(3, "I2", "음식", "I201", "커피", "C1", "카페");
        // industry 테이블 비움 → globalCount 없음

        AnalysisResponse r = service.analyze(view(null, null, null));

        assertThat(r.ranking()).hasSize(1);
        assertThat(r.ranking().get(0).lq()).isNull();
    }

    @Test
    void 빈_화면은_0값과_빈_리스트를_반환한다() {
        AnalysisResponse r = service.analyze(
                new AnalysisQuery(33.0, 33.1, 126.0, 126.1,
                        null, null, null, null, null, null, null));

        assertThat(r.total()).isZero();
        assertThat(r.densityPerKm2()).isZero();
        assertThat(r.composition()).isEmpty();
        assertThat(r.ranking()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*AnalysisServiceTest'`
Expected: 컴파일 실패(`AnalysisService` 심볼 없음).

- [ ] **Step 3: Write the implementation**

`src/main/java/com/example/commercialarea/store/AnalysisService.java`:
```java
package com.example.commercialarea.store;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnalysisService {

    /** 소분류 순위 상위 N. */
    static final int RANKING_LIMIT = 10;

    private final StoreRepository repository;

    public AnalysisService(StoreRepository repository) {
        this.repository = repository;
    }

    public AnalysisResponse analyze(AnalysisQuery query) {
        String level = compositionLevel(query);
        List<CategoryCount> groups =
                repository.groupCount(query, codeColumn(level), nameColumn(level));
        long total = groups.stream().mapToLong(CategoryCount::count).sum();

        double areaKm2 = GeoArea.km2(query.minLat(), query.maxLat(), query.minLon(), query.maxLon());

        if (total == 0) {
            return new AnalysisResponse(0, areaKm2, 0.0, List.of(), List.of());
        }

        double density = areaKm2 > 0 ? total / areaKm2 : 0.0;
        final long denom = total;

        List<CompositionItem> composition = groups.stream()
                .map(g -> new CompositionItem(level, g.code(), g.name(),
                        g.count(), (double) g.count() / denom))
                .toList();

        List<SmallCount> rows = repository.rankingSmall(query, RANKING_LIMIT);
        List<String> smallCodes = rows.stream().map(SmallCount::smallCode).toList();
        Map<String, Long> globals = repository.industryCountsForSmall(smallCodes);
        long globalTotal = repository.industryTotal();

        List<RankingItem> ranking = rows.stream()
                .map(r -> new RankingItem(r.smallCode(), r.smallName(), r.count(),
                        locationQuotient(r.count(), denom, globals.get(r.smallCode()), globalTotal)))
                .toList();

        return new AnalysisResponse(total, areaKm2, density, composition, ranking);
    }

    /** LQ = (지역 비중) / (전역 비중). 전역 집계가 없거나 0이면 null. */
    private static Double locationQuotient(long localCount, long localTotal,
                                           Long globalCount, long globalTotal) {
        if (globalCount == null || globalCount == 0 || globalTotal == 0) {
            return null;
        }
        double localShare = (double) localCount / localTotal;
        double globalShare = (double) globalCount / globalTotal;
        return localShare / globalShare;
    }

    /** 가장 깊은 활성 업종 필터의 한 단계 아래 레벨로 구성을 집계한다. */
    static String compositionLevel(StoreFilter q) {
        if (hasText(q.medium()) || hasText(q.small())) {
            return "small";
        }
        if (hasText(q.large())) {
            return "medium";
        }
        return "large";
    }

    private static String codeColumn(String level) {
        return switch (level) {
            case "large" -> "large_code";
            case "medium" -> "medium_code";
            default -> "small_code";
        };
    }

    private static String nameColumn(String level) {
        return switch (level) {
            case "large" -> "large_name";
            case "medium" -> "medium_name";
            default -> "small_name";
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*AnalysisServiceTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/store/AnalysisService.java \
        src/test/java/com/example/commercialarea/store/AnalysisServiceTest.java
git commit -m "feat: 밀도·업종구성·특화도를 조립하는 AnalysisService 추가"
```

---

## Task 7: AnalysisController + API 테스트

**Files:**
- Create: `src/main/java/com/example/commercialarea/store/AnalysisController.java`
- Test: `src/test/java/com/example/commercialarea/store/AnalysisApiTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/commercialarea/store/AnalysisApiTest.java`:
```java
package com.example.commercialarea.store;

import com.example.commercialarea.support.MySqlTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class AnalysisApiTest {

    @Autowired MockMvc mvc;
    @Autowired StoreRepository repository;
    @Autowired JdbcClient jdbc;

    private int seq = 0;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM store").update();
        jdbc.sql("DELETE FROM industry").update();
        seq = 0;
    }

    private void addStores(int n, String large, String largeN,
                           String medium, String medN, String small, String smallN) {
        List<Store> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = "A" + (seq++);
            batch.add(new Store(id, "가게" + id, null,
                    large, largeN, medium, medN, small, smallN,
                    "11", "서울특별시", "11680", "강남구", "11680510", "역삼1동",
                    "지번", null, "도로명", null,
                    127.0 + (seq % 40) * 0.0001, 37.5 + (seq % 40) * 0.0001));
        }
        repository.insertBatch(batch);
    }

    private static String bbox() {
        return "?minLat=37.5&maxLat=37.6&minLon=127.0&maxLon=127.1";
    }

    @Test
    void 분석_응답의_구조를_반환한다() throws Exception {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(5, "I2", "음식", "I202", "한식", "H1", "한식");

        mvc.perform(get("/api/analysis" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(15))
                .andExpect(jsonPath("$.areaKm2").isNumber())
                .andExpect(jsonPath("$.densityPerKm2").isNumber())
                .andExpect(jsonPath("$.composition").isArray())
                .andExpect(jsonPath("$.composition[0].level").value("large"))
                .andExpect(jsonPath("$.composition[0].code").value("I2"))
                .andExpect(jsonPath("$.ranking").isArray())
                .andExpect(jsonPath("$.ranking[0].smallCode").value("C1"));
    }

    @Test
    void 업종_필터를_존중해_구성_레벨이_내려간다() throws Exception {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(5, "I2", "음식", "I202", "한식", "H1", "한식");

        mvc.perform(get("/api/analysis" + bbox() + "&large=I2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.composition[0].level").value("medium"));
    }

    @Test
    void 순위는_최대_10개까지만_반환한다() throws Exception {
        for (int i = 0; i < 12; i++) {
            addStores(12 - i, "I2", "음식", "I2" + i, "중" + i, "S" + i, "소" + i);
        }
        mvc.perform(get("/api/analysis" + bbox()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranking.length()").value(10));
    }

    @Test
    void 빈_화면은_0값과_빈_배열을_반환한다() throws Exception {
        mvc.perform(get("/api/analysis?minLat=33.0&maxLat=33.1&minLon=126.0&maxLon=126.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.densityPerKm2").value(0.0))
                .andExpect(jsonPath("$.composition.length()").value(0))
                .andExpect(jsonPath("$.ranking.length()").value(0));
    }

    @Test
    void bbox가_없으면_MISSING_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/analysis?maxLat=37.6&minLon=127.0&maxLon=127.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_BBOX"));
    }

    @Test
    void bbox가_뒤집히면_INVALID_BBOX_400이다() throws Exception {
        mvc.perform(get("/api/analysis?minLat=37.6&maxLat=37.5&minLon=127.0&maxLon=127.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BBOX"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*AnalysisApiTest'`
Expected: 컴파일 실패(`AnalysisController` 없음) 또는 404.

- [ ] **Step 3: Write the controller**

`src/main/java/com/example/commercialarea/store/AnalysisController.java`:
```java
package com.example.commercialarea.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @GetMapping("/api/analysis")
    public AnalysisResponse analyze(
            @RequestParam double minLat,
            @RequestParam double maxLat,
            @RequestParam double minLon,
            @RequestParam double maxLon,
            @RequestParam(required = false) String sido,
            @RequestParam(required = false) String sgg,
            @RequestParam(required = false) String dong,
            @RequestParam(required = false) String large,
            @RequestParam(required = false) String medium,
            @RequestParam(required = false) String small,
            @RequestParam(required = false) String q) {

        AnalysisQuery query = new AnalysisQuery(minLat, maxLat, minLon, maxLon,
                sido, sgg, dong, large, medium, small, q);
        query.validate();
        return service.analyze(query);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*AnalysisApiTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/commercialarea/store/AnalysisController.java \
        src/test/java/com/example/commercialarea/store/AnalysisApiTest.java
git commit -m "feat: GET /api/analysis 엔드포인트 추가"
```

---

## Task 8: UI — 분석 버튼과 결과 패널

필터 패널 하단에 "이 화면 분석" 버튼을 두고, 클릭 시 현재 bbox+필터로 `/api/analysis`를 호출해 요약·업종 구성·업종 순위를 렌더링한다.

**Files:**
- Modify: `src/main/resources/templates/index.html`
- Modify: `src/main/resources/static/app.js`
- Modify: `src/main/resources/static/app.css`
- Test: 수동 확인(백엔드 테스트로 계약은 이미 검증됨)

- [ ] **Step 1: Add markup**

`src/main/resources/templates/index.html` — `<button id="reset" ...>` 다음 줄에, `</aside>` 앞에 추가:
```html
      <div class="group analysis">
        <button id="analyze" type="button" class="analyze">이 화면 분석</button>
        <div id="analysis-result" class="analysis-result" hidden>
          <div class="analysis-summary" id="analysis-summary"></div>
          <h3>업종 구성 <span id="composition-level" class="level-badge"></span></h3>
          <ul class="bars" id="composition-bars"></ul>
          <h3>업종 순위 (특화도)</h3>
          <ul class="ranking" id="ranking-list"></ul>
        </div>
      </div>
```

- [ ] **Step 2: Add analysis logic to app.js**

`src/main/resources/static/app.js` 맨 끝(init IIFE 뒤)에 추가. `currentParams()`·`el()`·`escapeHtml()`는 이미 정의되어 있으므로 재사용한다. `/api/analysis`는 `zoom` 파라미터를 선언하지 않으므로 `currentParams()`가 포함하는 `zoom`은 무시된다:
```javascript
/* ---------- 화면 분석 ---------- */

const LEVEL_LABEL = { large: '대분류', medium: '중분류', small: '소분류' };

function renderAnalysis(data) {
  el('analysis-result').hidden = false;

  el('analysis-summary').innerHTML =
    `총 <strong>${data.total.toLocaleString()}</strong>건 · `
    + `면적 <strong>${data.areaKm2.toFixed(2)}</strong> km² · `
    + `밀도 <strong>${Math.round(data.densityPerKm2).toLocaleString()}</strong> 건/km²`;

  const levelBadge = el('composition-level');
  levelBadge.textContent = data.composition.length ? (LEVEL_LABEL[data.composition[0].level] || '') : '';

  const maxShare = data.composition.reduce((m, c) => Math.max(m, c.share), 0) || 1;
  el('composition-bars').innerHTML = data.composition.map(c => `
    <li>
      <span class="bar-label">${escapeHtml(c.name)}</span>
      <span class="bar-track"><span class="bar-fill" style="width:${(c.share / maxShare * 100).toFixed(1)}%"></span></span>
      <span class="bar-value">${(c.share * 100).toFixed(1)}% (${c.count.toLocaleString()})</span>
    </li>
  `).join('') || '<li class="empty">표시할 업종이 없습니다.</li>';

  el('ranking-list').innerHTML = data.ranking.map(r => {
    const lq = r.lq == null ? '—' : r.lq.toFixed(2);
    const hot = r.lq != null && r.lq >= 1.5 ? ' hot' : '';
    return `<li>
      <span class="rank-name">${escapeHtml(r.smallName)}</span>
      <span class="rank-count">${r.count.toLocaleString()}건</span>
      <span class="rank-lq${hot}">LQ ${lq}</span>
    </li>`;
  }).join('') || '<li class="empty">표시할 업종이 없습니다.</li>';
}

async function analyzeCurrentView() {
  const button = el('analyze');
  button.disabled = true;
  button.textContent = '분석 중…';
  try {
    const data = await fetch('/api/analysis?' + currentParams()).then(r => r.json());
    if (data.error) {
      console.warn('분석 실패', data);
      return;
    }
    renderAnalysis(data);
  } catch (e) {
    console.error('분석 실패', e);
  } finally {
    button.disabled = false;
    button.textContent = '이 화면 분석';
  }
}

el('analyze').addEventListener('click', analyzeCurrentView);
```

- [ ] **Step 3: Add styles to app.css**

`src/main/resources/static/app.css` 맨 끝에 추가:
```css
/* ---------- 화면 분석 ---------- */
.analyze {
  width: 100%;
  padding: 0.5rem;
  background: #1c5ed6;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.9rem;
}
.analyze:disabled { opacity: 0.6; cursor: default; }

.analysis-result { margin-top: 0.75rem; font-size: 0.85rem; }
.analysis-summary { margin-bottom: 0.75rem; line-height: 1.5; }
.analysis-result h3 { margin: 0.75rem 0 0.35rem; font-size: 0.85rem; }
.level-badge {
  font-size: 0.7rem; font-weight: normal; color: #666;
  background: #eef; border-radius: 3px; padding: 0 0.3rem;
}

.bars { list-style: none; margin: 0; padding: 0; }
.bars li { display: grid; grid-template-columns: 5.5rem 1fr auto; gap: 0.4rem; align-items: center; margin-bottom: 0.25rem; }
.bar-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar-track { background: #eee; border-radius: 3px; height: 0.7rem; overflow: hidden; }
.bar-fill { display: block; height: 100%; background: #3b82f6; }
.bar-value { color: #555; font-size: 0.75rem; white-space: nowrap; }

.ranking { list-style: none; margin: 0; padding: 0; }
.ranking li { display: flex; justify-content: space-between; gap: 0.4rem; padding: 0.2rem 0; border-bottom: 1px solid #f0f0f0; }
.rank-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-count { color: #555; }
.rank-lq { color: #888; font-variant-numeric: tabular-nums; }
.rank-lq.hot { color: #c2410c; font-weight: bold; }

.bars .empty, .ranking .empty { color: #999; }
```

- [ ] **Step 4: Manual verification**

빌드 후 앱을 띄우고 브라우저에서 확인한다(데이터가 적재된 환경).
```bash
docker compose up -d --build
```
`http://localhost:8080`에서:
1. 지도를 임의 영역으로 이동 → "이 화면 분석" 클릭 → 요약(총건수·면적·밀도), 업종 구성 막대, 업종 순위+LQ가 뜬다.
2. 업종 대분류를 선택하고 다시 분석 → 구성 레벨 배지가 "중분류"로 바뀐다.
3. LQ ≥ 1.5인 업종은 주황색 굵게 강조된다.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/index.html \
        src/main/resources/static/app.js \
        src/main/resources/static/app.css
git commit -m "feat: 화면 분석 버튼과 결과 패널 UI 추가"
```

---

## Task 9: README 갱신

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update 기능 목록**

`README.md`의 `## 기능` 목록 마지막 항목 뒤에 추가:
```markdown
- 화면 분석 — "이 화면 분석" 버튼으로 현재 지도 영역의 밀도(건/km²), 업종 구성 비중,
  소분류 순위와 특화도(LQ)를 계산. 업종 필터 깊이에 따라 구성 집계 레벨이 자동으로 내려간다.
```

- [ ] **Step 2: Update API 표**

`README.md`의 `## API` 표에 행 추가(`GET /api/map` 아래):
```markdown
| `GET /api/analysis` | bbox + 필터 → 밀도·업종 구성·소분류 순위(특화도 LQ) |
```

- [ ] **Step 3: Add 알려진 제약**

`README.md`의 `## 알려진 제약` 목록에 추가:
```markdown
- 화면 분석의 밀도는 bbox 사각형 **전체** 면적(비상업 공간 포함) 기준 근사치다.
  절대값이 아니라 화면 간 상대 비교용으로 본다.
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: README에 화면 분석 기능·API·제약 반영"
```

---

## Final Verification

- [ ] **전체 테스트 실행**

Run: `./gradlew test`
Expected: 모든 테스트 PASS(신규 GeoAreaTest 3 + AnalysisQueryTest 3 + AnalysisServiceTest 7 + AnalysisApiTest 6, 기존 테스트 무회귀).

- [ ] **전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.
