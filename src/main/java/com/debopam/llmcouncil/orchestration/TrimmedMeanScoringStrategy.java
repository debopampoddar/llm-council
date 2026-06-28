package com.debopam.llmcouncil.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trimmed-mean scoring strategy — a robust compromise.
 *
 * <p>Drops the single highest and single lowest review score before computing
 * the arithmetic mean. This provides outlier resistance (like median) while
 * retaining sensitivity to score distribution (like average).
 *
 * <p><b>Requires at least 3 reviews per draft</b> to be effective. With fewer
 * than 3 reviews, falls back to simple averaging since there is nothing to
 * trim meaningfully.
 *
 * <p><b>Best for:</b> councils with 4+ reviewers where occasional reviewer
 * failures or extreme scores are expected.
 */
public class TrimmedMeanScoringStrategy implements ScoringStrategy {

    @Override
    public Map<String, Double> aggregateDimensions(List<ReviewArtifact> reviews) {
        Map<String, List<CriterionScore>> grouped = reviews.stream()
                .flatMap(r -> r.criteria().stream())
                .collect(Collectors.groupingBy(CriterionScore::name,
                                              LinkedHashMap::new,
                                              Collectors.toList()));

        Map<String, Double> dimensions = new LinkedHashMap<>();
        grouped.forEach((name, criteria) -> {
            List<Integer> scores = criteria.stream()
                    .map(CriterionScore::score)
                    .sorted()
                    .toList();
            dimensions.put(name, trimmedMean(scores));
        });

        if (dimensions.isEmpty()) {
            List<Integer> scores = reviews.stream()
                    .map(ReviewArtifact::overallScore)
                    .sorted()
                    .toList();
            dimensions.put("overall", trimmedMean(scores));
        }
        return dimensions;
    }

    @Override
    public double aggregateOverallScore(List<ReviewArtifact> reviews) {
        List<Integer> scores = reviews.stream()
                .map(ReviewArtifact::overallScore)
                .sorted()
                .toList();
        return trimmedMean(scores);
    }

    /**
     * Compute trimmed mean: drop the lowest and highest values, then average
     * the remaining. Falls back to regular average for lists with fewer than
     * 3 elements (nothing to trim).
     */
    private double trimmedMean(List<Integer> sorted) {
        if (sorted.isEmpty()) return 0.0;

        // Need at least 3 values to meaningfully trim.
        if (sorted.size() < 3) {
            return sorted.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        }

        // Skip the first (lowest) and last (highest) values.
        return sorted.subList(1, sorted.size() - 1)
                .stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
    }

    @Override
    public String name() {
        return "trimmed-mean";
    }
}
