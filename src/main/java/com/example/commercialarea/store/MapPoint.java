package com.example.commercialarea.store;

/** 지도 마커용 최소 payload. 주소는 담지 않는다 — 2,000개에 주소까지 실으면 응답이 몇 배가 된다. */
public record MapPoint(String id, String name, double lat, double lon, String large) {
}
