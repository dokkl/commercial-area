package com.example.commercialarea.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * INSERT IGNORE 이므로 재실행해도 중복이 생기지 않는다.
     *
     * JdbcClient는 배치 API를 제공하지 않으므로, 122만 행 적재처럼 배치가 필요한 곳에서는
     * 의도적으로 JdbcTemplate.batchUpdate를 사용한다. 이 메서드는 내부적으로 청크를 나누지
     * 않으므로 호출하는 쪽에서 미리 적절한 크기로 나눠 전달해야 한다.
     */
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
}
