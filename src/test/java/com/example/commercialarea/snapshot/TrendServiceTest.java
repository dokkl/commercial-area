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
