package com.example.commercialarea.store;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnalysisService {

    /** 소분류 순위 상위 N. */
    static final int RANKING_LIMIT = 10;

    private final StoreRepository repository;

    public AnalysisService(StoreRepository repository) {
        this.repository = repository;
    }

    public AnalysisResponse analyze(AnalysisQuery query) {
        String level = compositionLevel(query);
        List<CategoryCount> groups =
                repository.groupCount(query, codeColumn(level), nameColumn(level));
        long total = groups.stream().mapToLong(CategoryCount::count).sum();

        double areaKm2 = GeoArea.km2(query.minLat(), query.maxLat(), query.minLon(), query.maxLon());

        if (total == 0) {
            return new AnalysisResponse(0, areaKm2, 0.0, List.of(), List.of());
        }

        double density = areaKm2 > 0 ? total / areaKm2 : 0.0;
        final long denom = total;

        List<CompositionItem> composition = groups.stream()
                .map(g -> new CompositionItem(level, g.code(), g.name(),
                        g.count(), (double) g.count() / denom))
                .toList();

        List<SmallCount> rows = repository.rankingSmall(query, RANKING_LIMIT);
        List<String> smallCodes = rows.stream().map(SmallCount::smallCode).toList();
        Map<String, Long> globals = repository.industryCountsForSmall(smallCodes);
        long globalTotal = repository.industryTotal();

        List<RankingItem> ranking = rows.stream()
                .map(r -> new RankingItem(r.smallCode(), r.smallName(), r.count(),
                        locationQuotient(r.count(), denom, globals.get(r.smallCode()), globalTotal)))
                .toList();

        return new AnalysisResponse(total, areaKm2, density, composition, ranking);
    }

    /** LQ = (지역 비중) / (전역 비중). 전역 집계가 없거나 0이면 null. */
    private static Double locationQuotient(long localCount, long localTotal,
                                           Long globalCount, long globalTotal) {
        if (globalCount == null || globalCount == 0 || globalTotal == 0) {
            return null;
        }
        double localShare = (double) localCount / localTotal;
        double globalShare = (double) globalCount / globalTotal;
        return localShare / globalShare;
    }

    /** 가장 깊은 활성 업종 필터의 한 단계 아래 레벨로 구성을 집계한다. */
    static String compositionLevel(StoreFilter q) {
        if (hasText(q.medium()) || hasText(q.small())) {
            return "small";
        }
        if (hasText(q.large())) {
            return "medium";
        }
        return "large";
    }

    private static String codeColumn(String level) {
        return switch (level) {
            case "large" -> "large_code";
            case "medium" -> "medium_code";
            default -> "small_code";
        };
    }

    private static String nameColumn(String level) {
        return switch (level) {
            case "large" -> "large_name";
            case "medium" -> "medium_name";
            default -> "small_name";
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
