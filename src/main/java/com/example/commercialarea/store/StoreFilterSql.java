package com.example.commercialarea.store;

import java.util.Map;

/**
 * 집계·점 조회·목록·카운트 네 쿼리가 동일한 필터 조건을 써야 한다.
 * 각자 조립하면 필터를 하나 추가할 때 네 군데를 고쳐야 하고 반드시 어긋난다.
 */
final class StoreFilterSql {

    private StoreFilterSql() {
    }

    static void appendWhere(StringBuilder sql, Map<String, Object> params, MapQuery q) {
        sql.append(" WHERE lat BETWEEN :minLat AND :maxLat")
           .append(" AND lon BETWEEN :minLon AND :maxLon");
        params.put("minLat", q.minLat());
        params.put("maxLat", q.maxLat());
        params.put("minLon", q.minLon());
        params.put("maxLon", q.maxLon());

        eq(sql, params, "sido_code", "sido", q.sido());
        eq(sql, params, "sgg_code", "sgg", q.sgg());
        eq(sql, params, "dong_code", "dong", q.dong());
        eq(sql, params, "large_code", "large", q.large());
        eq(sql, params, "medium_code", "medium", q.medium());
        eq(sql, params, "small_code", "small", q.small());

        if (hasText(q.q())) {
            // idx_name(store_name(20)) 을 쓰려면 앞부분 일치여야 한다.
            sql.append(" AND store_name LIKE :namePrefix");
            params.put("namePrefix", q.q().trim() + "%");
        }
    }

    private static void eq(StringBuilder sql, Map<String, Object> params,
                           String column, String param, String value) {
        if (hasText(value)) {
            sql.append(" AND ").append(column).append(" = :").append(param);
            params.put(param, value.trim());
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
