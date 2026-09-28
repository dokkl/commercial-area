package com.example.commercialarea.store;

/**
 * bbox + 지역/업종/상호명 필터 조건. MapQuery와 AnalysisQuery가 함께 구현해
 * StoreFilterSql이 두 요청 타입을 모두 받도록 한다.
 */
public interface StoreFilter {
    double minLat();
    double maxLat();
    double minLon();
    double maxLon();
    String sido();
    String sgg();
    String dong();
    String large();
    String medium();
    String small();
    String q();
}
