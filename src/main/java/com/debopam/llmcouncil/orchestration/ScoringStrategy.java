package com.debopam.llmcouncil.orchestration;

import java.util.List;
import java.util.Map;

/**
 * Strategy interface for aggregating peer-review scores into a single
 * composite score per draft.
 *
 * <p>Different scoring strategies suit different council use cases:
 * <ul>
 *   <li><b>Average</b> — simple, but vulnerable to outlier manipulation</li>
 *   <li><b>Confidence-Weighted Average</b> — weights reviews by reviewer
 *       confidence, giving more influence to confident reviewers (recommended
 *       default per LLM council best practices)</li>
 *   <li><b>Median</b> — robust to outliers but loses score nuance</li>
 *   <li><b>Trimmed Mean</b> — drops highest and lowest review, robust
 *       compromise between average and median</li>
 * </ul>
 *
 * <p>Implementations are selected via the {@code scoring-strategy} stage
 * option in protocol configuration.
 *
 * @see ScoreStageExecutor
 */
public interface ScoringStrategy {

    /**
     * Aggregate per-dimension scores from multiple reviews into a single
     * dimension-score map.
     *
     * @param reviews All reviews for a single draft.
     * @return Map of dimension name → aggregated score (0–100).
     */
    Map<String, Double> aggregateDimensions(List<ReviewArtifact> reviews);

    /**
     * Compute the overall weighted total score from multiple reviews.
     *
     * @param reviews All reviews for a single draft.
     * @return Aggregated overall score (0–100).
     */
    double aggregateOverallScore(List<ReviewArtifact> reviews);

    /**
     * @return Human-readable name of this strategy for event/artifact metadata.
     */
    String name();
}
