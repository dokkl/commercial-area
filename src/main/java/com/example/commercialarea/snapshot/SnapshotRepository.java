package com.example.commercialarea.snapshot;

import com.example.commercialarea.store.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 적재된 모든 스냅샷(import_log)에 대해 필터에 맞는 점포수를 분기순으로 반환한다.
     * 필터를 LEFT JOIN의 ON 조건에 넣어, 해당 분기에 매칭 0건이어도 count 0으로 계열에 남긴다.
     */
    public List<SnapshotCount> countBySnapshot(TrendQuery filter) {
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
}
