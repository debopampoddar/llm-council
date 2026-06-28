package com.debopam.llmcouncil.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Simple arithmetic-mean scoring strategy.
 *
 * <p>Computes unweighted averages of both per-dimension criterion scores and
 * overall review scores. This was the original (pre-Gap-2.2) behaviour.
 *
 * <p><b>Trade-offs:</b> easy to understand and debug, but vulnerable to
 * outlier manipulation — a single low-quality reviewer with extreme scores
 * skews the aggregate disproportionately. All reviewers have equal influence
 * regardless of how confident they are in their assessment.
 *
 * @see ConfidenceWeightedScoringStrategy for the recommended default
 */
public class AverageScoringStrategy implements ScoringStrategy {

    @Override
    public Map<String, Double> aggregateDimensions(List<ReviewArtifact> reviews) {
        // Group all CriterionScore objects by dimension name, preserving insertion order.
        Map<String, List<CriterionScore>> grouped = reviews.stream()
                .flatMap(r -> r.criteria().stream())
                .collect(Collectors.groupingBy(CriterionScore::name,
                                              LinkedHashMap::new,
                                              Collectors.toList()));

        Map<String, Double> dimensions = new LinkedHashMap<>();
        grouped.forEach((name, criteria) ->
                // Arithmetic mean: sum(scores) / count
                dimensions.put(name, criteria.stream()
                        .mapToInt(CriterionScore::score)
                        .average()
                        .orElse(0.0)));

        // Fallback: if no per-criterion scores exist, use overallScore as a
        // single "overall" dimension so downstream stages still have data.
        if (dimensions.isEmpty()) {
            dimensions.put("overall", reviews.stream()
                    .mapToInt(ReviewArtifact::overallScore)
                    .average()
                    .orElse(0.0));
        }
        return dimensions;
    }

    @Override
    public double aggregateOverallScore(List<ReviewArtifact> reviews) {
        // Simple arithmetic mean of all reviewer overall scores.
        return reviews.stream()
                .mapToInt(ReviewArtifact::overallScore)
                .average()
                .orElse(0.0);
    }

    @Override
    public String name() {
        return "average";
    }
}
