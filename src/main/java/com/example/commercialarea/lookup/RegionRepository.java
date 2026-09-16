package com.example.commercialarea.lookup;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    private static LookupItem withBbox(ResultSet rs, int rowNum) throws SQLException {
        return new LookupItem(
                rs.getString("code"), rs.getString("name"), rs.getLong("cnt"),
                rs.getDouble("min_lat"), rs.getDouble("max_lat"),
                rs.getDouble("min_lon"), rs.getDouble("max_lon"));
    }
}
