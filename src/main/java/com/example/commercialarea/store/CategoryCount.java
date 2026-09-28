package com.example.commercialarea.store;

/** 업종 구성 집계 행(레벨 무관). code/name은 집계 레벨의 코드·명칭. */
record CategoryCount(String code, String name, long count) {
}
