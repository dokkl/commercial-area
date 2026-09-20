package com.example.commercialarea.snapshot;

/** 추이 요청 필터. 지역(시도/시군구/행정동) + 업종(대/중/소). bbox·상호명 없음. */
public record TrendQuery(String sido, String sgg, String dong,
                         String large, String medium, String small) {
}
