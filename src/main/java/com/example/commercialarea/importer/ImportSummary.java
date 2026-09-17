package com.example.commercialarea.importer;

/**
 * processed: CSV에서 정상 파싱되어 DB에 삽입을 시도한 행 수다. 실제로 저장된 행 수가
 * 아니다 — INSERT IGNORE 때문에 중복 행은 조용히 무시되고, rewriteBatchedStatements
 * 설정 때문에 행 단위 삽입 성공 여부 자체를 알 수 없다({@link com.example.commercialarea.store.StoreRepository#insertBatch}
 * 참고). failed: 파싱에 실패해 건너뛴 행 수.
 */
public record ImportSummary(long processed, long failed) {
    public long total() {
        return processed + failed;
    }

    public double failureRate() {
        return total() == 0 ? 0.0 : (double) failed / total();
    }
}
