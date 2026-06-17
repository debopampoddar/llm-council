package com.debopam.llmcouncil.orchestration;

import java.util.Map;

/**
 * Numeric scores assigned to one draft by a judge model.
 * Produced by the {@link StageType#SCORE} stage.
 *
 * @param scorerId        Model ID that assigned the scores.
 * @param draftId         ID of the draft being scored.
 * @param dimensionScores Map of dimension name → score (0–100).
 *                        Common dimensions: accuracy, completeness, clarity, reasoning.
 * @param weightedTotal   Weighted composite score derived from dimensionScores.
 * @param label           Optional label (e.g. "initial", "post-debate") set via stage options.
 */
public record ScoreArtifact(
        String scorerId,
        String draftId,
        Map<String, Double> dimensionScores,
        double weightedTotal,
        String label
) {}
