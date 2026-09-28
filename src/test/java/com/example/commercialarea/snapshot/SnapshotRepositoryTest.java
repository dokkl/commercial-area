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
