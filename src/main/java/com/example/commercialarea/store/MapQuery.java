package com.example.commercialarea.store;

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
}
