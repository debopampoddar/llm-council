package com.debopam.llmcouncil.orchestration;

import java.util.List;

/**
 * Aggregate scoring view used by debate triggers and final synthesis.
 *
 * <p><b>(Disagreement Escalation):</b> the {@code escalated} and
 * {@code escalationPolicy} fields indicate whether the score variance exceeds
 * the post-debate threshold and what action should be taken. Downstream
 * stages (SYNTHESIZE, VALIDATE) can check these fields to add warnings or
 * halt the pipeline.
 *
 * <p><b>{@code variance} and {@code reviewerDisagreement} answer different
 * questions, and only one of them is about disagreement.</b> {@code variance}
 * spreads the <em>drafts</em> apart: it is high exactly when the reviewers agree
 * which draft is best — 90 against 40 is a decisive ranking, the situation least
 * in need of a debate — and low when the drafts are indistinguishable. Debate
 * and escalation were both keyed to it, so they fired on consensus and stood
 * down on conflict. {@code reviewerDisagreement} is the quantity that was
 * wanted: the largest disagreement <em>between reviewers about the same
 * draft</em>, where one reviewer says 90 and another says 40.
 *
 * <p>Measuring it needs at least two reviewers on one draft. Self-review is
 * excluded, so a two-member council produces exactly one review per draft and
 * the signal does not exist at all. {@code disagreementMeasurable} records that,
 * because a council that could not detect disagreement must not report the same
 * zero as a council that looked and found none.
 *
 * @param scores                 Per-draft weighted scores.
 * @param variance               Variance across weighted totals — how decisively
 *                               the drafts were ranked, not whether reviewers agreed.
 * @param winningDraftId         Highest-scoring draft, if any.
 * @param escalated              True if reviewer disagreement exceeded the
 *                               escalation threshold after debate has concluded.
 * @param escalationPolicy       The configured escalation action for this protocol;
 *                               null if escalation was not triggered.
 * @param reviewerDisagreement   Largest inter-rater variance on any single draft;
 *                               0.0 when not measurable.
 * @param disagreementMeasurable Whether any draft carried enough reviewers for
 *                               inter-rater variance to exist.
 */
public record ScoreSummary(
        List<ScoreArtifact> scores,
        double variance,
        String winningDraftId,
        boolean escalated,
        EscalationPolicy escalationPolicy,
        double reviewerDisagreement,
        boolean disagreementMeasurable
) {
    /** Compact constructor: makes scores defensively immutable. */
    public ScoreSummary {
        scores = List.copyOf(scores);
    }

    /**
     * Convenience constructor for callers that do not measure inter-rater
     * disagreement. Reports it as unmeasurable rather than as zero.
     */
    public ScoreSummary(List<ScoreArtifact> scores, double variance, String winningDraftId,
                        boolean escalated, EscalationPolicy escalationPolicy) {
        this(scores, variance, winningDraftId, escalated, escalationPolicy, 0.0, false);
    }

    /**
     * Convenience constructor for the non-escalated case (backwards-compatible
     * with existing code that doesn't set escalation fields).
     */
    public ScoreSummary(List<ScoreArtifact> scores, double variance, String winningDraftId) {
        this(scores, variance, winningDraftId, false, null);
    }
}
