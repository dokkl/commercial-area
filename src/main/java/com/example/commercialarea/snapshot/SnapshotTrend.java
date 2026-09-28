package com.example.commercialarea.snapshot;

/** 응답 항목. opened/closed는 시계열 첫 분기에서 null. */
public record SnapshotTrend(String ym, long count, Long opened, Long closed) {
}
