package com.debopam.llmcouncil.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Confidence-weighted scoring strategy — the recommended default.
 *
 * <p>Weights each reviewer's scores by their self-reported confidence (0.0–1.0).
 * Reviewers who are more confident in their assessment have proportionally
 * more influence on the aggregate score:
 *
 * <pre>
 *   weightedScore = Σ(score_i × confidence_i) / Σ(confidence_i)
 * </pre>
 *
 * <p><b>Rationale (Gap 2.1):</b> research on LLM-as-Judge panels shows that
 * confidence-weighted aggregation outperforms simple averaging because:
 * <ul>
 *   <li>Reviewers with domain expertise tend to report higher confidence</li>
 *   <li>Uncertain reviewers self-attenuate their influence</li>
 *   <li>Reduces impact of low-quality reviews that are uncertain themselves</li>
 * </ul>
 *
 * <p><b>Edge case:</b> if all confidence values are 0.0 (degenerate case),
 * falls back to equal-weight averaging to avoid division by zero.
 *
 * @see AverageScoringStrategy for the simpler unweighted alternative
 */
public class ConfidenceWeightedScoringStrategy implements ScoringStrategy {

    @Override
    public Map<String, Double> aggregateDimensions(List<ReviewArtifact> reviews) {
        // Group criterion scores by dimension name, preserving insertion order.
        Map<String, List<CriterionScoreWithConfidence>> grouped = reviews.stream()
                .flatMap(review -> review.criteria().stream()
                        // Pair each criterion score with its parent review's confidence
                        // so we can weight by confidence during aggregation.
                        .map(cs -> new CriterionScoreWithConfidence(cs, review.confidence())))
                .collect(Collectors.groupingBy(csc -> csc.criterion().name(),
                                              LinkedHashMap::new,
                                              Collectors.toList()));

        Map<String, Double> dimensions = new LinkedHashMap<>();
        grouped.forEach((name, criteria) -> {
            double totalConfidence = criteria.stream()
                    .mapToDouble(CriterionScoreWithConfidence::confidence)
                    .sum();

            if (totalConfidence <= 0.0) {
                // Degenerate case: all confidence values are 0.0.
                // Fall back to arithmetic mean to avoid NaN.
                dimensions.put(name, criteria.stream()
                        .mapToInt(c -> c.criterion().score())
                        .average()
                        .orElse(0.0));
            } else {
                // Confidence-weighted average:
                // Σ(score_i × confidence_i) / Σ(confidence_i)
                double weightedSum = criteria.stream()
                        .mapToDouble(c -> c.criterion().score() * c.confidence())
                        .sum();
                dimensions.put(name, weightedSum / totalConfidence);
            }
        });

        // Fallback: if no per-criterion scores exist, use overallScore weighted
        // by confidence as a single "overall" dimension.
        if (dimensions.isEmpty()) {
            dimensions.put("overall", computeWeightedOverall(reviews));
        }
        return dimensions;
    }

    @Override
    public double aggregateOverallScore(List<ReviewArtifact> reviews) {
        return computeWeightedOverall(reviews);
    }

    /**
     * Computes confidence-weighted average of overall scores.
     *
     * @param reviews Reviews to aggregate.
     * @return Weighted overall score, or simple average if all confidence is 0.
     */
    private double computeWeightedOverall(List<ReviewArtifact> reviews) {
        double totalConfidence = reviews.stream()
                .mapToDouble(ReviewArtifact::confidence)
                .sum();

        if (totalConfidence <= 0.0) {
            // Fallback to simple average for the degenerate case.
            return reviews.stream()
                    .mapToInt(ReviewArtifact::overallScore)
                    .average()
                    .orElse(0.0);
        }

        // Confidence-weighted average of overall scores.
        double weightedSum = reviews.stream()
                .mapToDouble(r -> r.overallScore() * r.confidence())
                .sum();
        return weightedSum / totalConfidence;
    }

    @Override
    public String name() {
        return "confidence-weighted";
    }

    /**
     * Internal pairing of a criterion score with its parent review's confidence.
     * Used during per-dimension aggregation to weight individual criterion scores.
     */
    private record CriterionScoreWithConfidence(CriterionScore criterion, double confidence) {}
}
