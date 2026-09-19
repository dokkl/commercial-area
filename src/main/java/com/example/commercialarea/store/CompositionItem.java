package com.example.commercialarea.store;

/**
 * 업종 구성 항목. level 은 집계 레벨("large"|"medium"|"small"),
 * share 는 화면 내 비중(0~1).
 */
public record CompositionItem(String level, String code, String name, long count, double share) {
}
