package com.example.commercialarea.importer;

public record ImportSummary(long succeeded, long failed) {
    public long total() {
        return succeeded + failed;
    }

    public double failureRate() {
        return total() == 0 ? 0.0 : (double) failed / total();
    }
}
