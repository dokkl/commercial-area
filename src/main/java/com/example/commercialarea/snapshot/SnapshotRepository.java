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
