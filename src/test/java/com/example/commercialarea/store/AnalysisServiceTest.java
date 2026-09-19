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
        assertThat(r.densityPerKm2()).isCloseTo(15.0 / r.areaKm2(), Offset.offset(1e-6));
    }

    @Test
    void 업종_구성은_필터가_없으면_대분류_레벨이다() {
        addStores(10, "I2", "음식", "I201", "커피", "C1", "카페");
        addStores(4, "G2", "소매", "G201", "편의점", "R1", "편의점");

        AnalysisResponse r = service.analyze(view(null, null, null));

        assertThat(r.composition()).allMatch(c -> c.level().equals("large"));
        assertThat(r.composition()).extracting(CompositionItem::code)
                .containsExactly("I2", "G2");
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
        addIndustry("I2", "음식", "I201", "커피", "C1", "카페", 100);
        addIndustry("I2", "음식", "I202", "한식", "H1", "한식", 900);

        AnalysisResponse r = service.analyze(view(null, null, null));

        RankingItem cafe = r.ranking().stream()
                .filter(x -> x.smallCode().equals("C1")).findFirst().orElseThrow();
        RankingItem hansik = r.ranking().stream()
                .filter(x -> x.smallCode().equals("H1")).findFirst().orElseThrow();

        assertThat(cafe.lq()).isCloseTo(6.667, Offset.offset(0.01));
        assertThat(hansik.lq()).isCloseTo(0.370, Offset.offset(0.01));
        assertThat(r.ranking().get(0).smallCode()).isEqualTo("C1");
    }

    @Test
    void 전역_집계가_없는_소분류의_특화도는_null이다() {
        addStores(3, "I2", "음식", "I201", "커피", "C1", "카페");

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
