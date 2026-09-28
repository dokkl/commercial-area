package com.example.commercialarea.store;

import java.util.List;

public record AnalysisResponse(
        long total,
        double areaKm2,
        double densityPerKm2,
        List<CompositionItem> composition,
        List<RankingItem> ranking
) {
}
