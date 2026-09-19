package com.example.commercialarea.store;

/** 소분류 순위 항목. lq(특화지수)는 전역 집계가 없으면 null. */
public record RankingItem(String smallCode, String smallName, long count, Double lq) {
}
