package com.debopam.llmcouncil.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Median scoring strategy — robust to outlier manipulation.
 *
 * <p>Uses the median instead of the mean for both per-dimension criterion
 * scores and overall scores. This prevents a single extreme reviewer
 * from disproportionately skewing the aggregate.
 *
 * <p><b>Trade-off:</b> more robust than average, but loses the nuance of
 * confidence-based weighting. Best suited for councils where reviewer
 * confidence calibration is unreliable.
 */
public class MedianScoringStrategy implements ScoringStrategy {

    @Override
    public Map<String, Double> aggregateDimensions(List<ReviewArtifact> reviews) {
        Map<String, List<CriterionScore>> grouped = reviews.stream()
                .flatMap(r -> r.criteria().stream())
                .collect(Collectors.groupingBy(CriterionScore::name,
                                              LinkedHashMap::new,
                                              Collectors.toList()));

        Map<String, Double> dimensions = new LinkedHashMap<>();
        grouped.forEach((name, criteria) -> {
            List<Integer> sorted = criteria.stream()
                    .map(CriterionScore::score)
                    .sorted()
                    .toList();
            dimensions.put(name, median(sorted));
        });

        if (dimensions.isEmpty()) {
            List<Integer> sorted = reviews.stream()
                    .map(ReviewArtifact::overallScore)
                    .sorted()
                    .toList();
            dimensions.put("overall", median(sorted));
        }
        return dimensions;
    }

    @Override
    public double aggregateOverallScore(List<ReviewArtifact> reviews) {
        List<Integer> sorted = reviews.stream()
                .map(ReviewArtifact::overallScore)
                .sorted()
                .toList();
        return median(sorted);
    }

    /**
     * Compute median of a sorted list of integers.
     * For even-length lists, returns the average of the two middle values.
     */
    private double median(List<Integer> sorted) {
        if (sorted.isEmpty()) return 0.0;
        int mid = sorted.size() / 2;
        // Even count: average the two middle values for a fair median.
        if (sorted.size() % 2 == 0) {
            return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
        }
        // Odd count: the middle value is the median.
        return sorted.get(mid);
    }

    @Override
    public String name() {
        return "median";
    }
}
