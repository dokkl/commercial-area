package com.example.commercialarea.lookup;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class IndustryRepository {

    private final JdbcClient jdbc;

    public IndustryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LookupItem> findLarge() {
        return jdbc.sql("""
                        SELECT large_code AS code, large_name AS name, SUM(store_count) AS cnt
                        FROM industry
                        GROUP BY large_code, large_name
                        ORDER BY cnt DESC
                        """)
                .query(IndustryRepository::toItem)
                .list();
    }

    public List<LookupItem> findMedium(String largeCode) {
        return jdbc.sql("""
                        SELECT medium_code AS code, medium_name AS name, SUM(store_count) AS cnt
                        FROM industry
                        WHERE large_code = :large
                        GROUP BY medium_code, medium_name
                        ORDER BY cnt DESC
                        """)
                .param("large", largeCode)
                .query(IndustryRepository::toItem)
                .list();
    }

    public List<LookupItem> findSmall(String mediumCode) {
        return jdbc.sql("""
                        SELECT small_code AS code, small_name AS name, SUM(store_count) AS cnt
                        FROM industry
                        WHERE medium_code = :medium
                        GROUP BY small_code, small_name
                        ORDER BY cnt DESC
                        """)
                .param("medium", mediumCode)
                .query(IndustryRepository::toItem)
                .list();
    }

    private static LookupItem toItem(ResultSet rs, int rowNum) throws SQLException {
        return LookupItem.of(rs.getString("code"), rs.getString("name"), rs.getLong("cnt"));
    }
}
