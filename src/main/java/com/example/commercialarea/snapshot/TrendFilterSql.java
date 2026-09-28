package com.example.commercialarea.snapshot;

import java.util.Map;

/**
 * 추이 쿼리의 지역·업종 동등조건을 조립한다. alias는 컬럼 접두어("s." 등, 없으면 "").
 * paramSuffix는 한 SQL 안에서 같은 필터를 두 번 쓸 때 파라미터 이름 충돌을 막는 접미어다.
 */
final class TrendFilterSql {

    private TrendFilterSql() {
    }

    static void appendConditions(StringBuilder sql, Map<String, Object> params,
                                 TrendQuery q, String alias, String paramSuffix) {
        eq(sql, params, alias, "sido_code", "sido" + paramSuffix, q.sido());
        eq(sql, params, alias, "sgg_code", "sgg" + paramSuffix, q.sgg());
        eq(sql, params, alias, "dong_code", "dong" + paramSuffix, q.dong());
        eq(sql, params, alias, "large_code", "large" + paramSuffix, q.large());
        eq(sql, params, alias, "medium_code", "medium" + paramSuffix, q.medium());
        eq(sql, params, alias, "small_code", "small" + paramSuffix, q.small());
    }

    private static void eq(StringBuilder sql, Map<String, Object> params,
                           String alias, String column, String param, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(alias).append(column).append(" = :").append(param);
            params.put(param, value.trim());
        }
    }
}
