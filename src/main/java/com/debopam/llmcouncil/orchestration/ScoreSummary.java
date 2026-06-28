package com.debopam.llmcouncil.orchestration;

import java.util.List;

/**
 * Aggregate scoring view used by debate triggers and final synthesis.
 *
 * <p><b>Gap 2.3 (Disagreement Escalation):</b> the {@code escalated} and
 * {@code escalationPolicy} fields indicate whether the score variance exceeds
 * the post-debate threshold and what action should be taken. Downstream
 * stages (SYNTHESIZE, VALIDATE) can check these fields to add warnings or
 * halt the pipeline.
 *
 * @param scores           Per-draft weighted scores.
 * @param variance         Variance across weighted totals; high variance
 *                         indicates disagreement between reviewers.
 * @param winningDraftId   Highest-scoring draft, if any.
 * @param escalated        True if variance exceeded the escalation threshold
 *                         after debate has concluded.
 * @param escalationPolicy The configured escalation action for this protocol;
 *                         null if escalation was not triggered.
 */
public record ScoreSummary(
        List<ScoreArtifact> scores,
        double variance,
        String winningDraftId,
        boolean escalated,
        EscalationPolicy escalationPolicy
) {
    /** Compact constructor: makes scores defensively immutable. */
    public ScoreSummary {
        scores = List.copyOf(scores);
    }

    /**
     * Convenience constructor for the non-escalated case (backwards-compatible
     * with existing code that doesn't set escalation fields).
     */
    public ScoreSummary(List<ScoreArtifact> scores, double variance, String winningDraftId) {
        this(scores, variance, winningDraftId, false, null);
    }
}
