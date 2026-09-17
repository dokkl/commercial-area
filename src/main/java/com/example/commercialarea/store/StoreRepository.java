package com.example.commercialarea.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     *
     * 반환값은 처리를 시도한 행 수(stores.size())이지 실제로 저장된 행 수가 아니다.
     * INSERT IGNORE로 무시된 중복 행도 그대로 포함되며, rewriteBatchedStatements=true
     * (docker-compose.yml/application.yml/MySqlTestContainer 참고) 때문에 MySQL 드라이버가
     * 배치를 다중행 INSERT로 재작성해 요소별로 Statement.SUCCESS_NO_INFO를 반환하므로
     * 애초에 행 단위 저장 성공 여부를 알 수 없다. 실제 저장 건수를 얻어내려 하지 말 것 —
     * 호출하는 쪽은 이 값을 "저장 성공"이 아니라 "처리 시도"로만 다뤄야 한다.
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
}
