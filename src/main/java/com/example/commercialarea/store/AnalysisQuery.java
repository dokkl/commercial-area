package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;

/**
 * 화면(bbox) 분석 요청. 지도 필터와 동일하되 zoom이 없다.
 */
public record AnalysisQuery(
        double minLat, double maxLat,
        double minLon, double maxLon,
        String sido, String sgg, String dong,
        String large, String medium, String small,
        String q
) implements StoreFilter {

    public void validate() {
        if (minLat > maxLat || minLon > maxLon) {
            throw ApiException.badRequest("INVALID_BBOX",
                    "minLat/minLon은 maxLat/maxLon보다 클 수 없습니다.");
        }
    }
}
