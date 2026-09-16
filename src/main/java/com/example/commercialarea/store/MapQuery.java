package com.example.commercialarea.store;

import com.example.commercialarea.config.ApiException;

public record MapQuery(
        double minLat, double maxLat,
        double minLon, double maxLon,
        int zoom,
        String sido, String sgg, String dong,
        String large, String medium, String small,
        String q
) {
    /** 격자 크기는 전역 원점에 고정한다. 뷰포트를 N등분하면 패닝할 때 클러스터가 튄다. */
    public double cellSize() {
        return 360.0 / Math.pow(2, zoom + 3);
    }

    public void validate() {
        if (minLat > maxLat || minLon > maxLon) {
            throw ApiException.badRequest("INVALID_BBOX",
                    "minLat/minLon은 maxLat/maxLon보다 클 수 없습니다.");
        }
        if (zoom < 0 || zoom > 22) {
            throw ApiException.badRequest("INVALID_ZOOM", "zoom은 0~22 범위여야 합니다.");
        }
    }
}
