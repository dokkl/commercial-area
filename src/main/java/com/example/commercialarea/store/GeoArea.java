package com.example.commercialarea.store;

/**
 * bbox(위경도 도 단위)의 근사 면적(km²)을 등장방형(equirectangular) 근사로 구한다.
 * 사각형 전체 면적이므로 비상업 공간(강·산·도로)을 포함한다 — 밀도는 화면 간 상대
 * 비교용 근사치다. 역전·0넓이 bbox는 0을 반환한다.
 */
public final class GeoArea {

    private static final double KM_PER_LAT_DEGREE = 110.574;
    private static final double KM_PER_LON_DEGREE = 111.320;

    private GeoArea() {
    }

    public static double km2(double minLat, double maxLat, double minLon, double maxLon) {
        double latKm = (maxLat - minLat) * KM_PER_LAT_DEGREE;
        double midLat = (minLat + maxLat) / 2.0;
        double lonKm = (maxLon - minLon) * KM_PER_LON_DEGREE * Math.cos(Math.toRadians(midLat));
        double area = latKm * lonKm;
        return area > 0 ? area : 0.0;
    }
}