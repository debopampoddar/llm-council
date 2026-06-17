package com.debopam.llmcouncil.orchestration;

import java.util.List;

/**
 * Aggregate scoring view used by debate triggers and final synthesis.
 *
 * @param scores         Per-draft weighted scores.
 * @param variance       Variance across weighted totals.
 * @param winningDraftId Highest-scoring draft, if any.
 */
public record ScoreSummary(List<ScoreArtifact> scores, double variance, String winningDraftId) {
    public ScoreSummary {
        scores = List.copyOf(scores);
    }
}
