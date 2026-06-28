// ── ScoreStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * SCORE stage: assigns numeric scores to each draft by aggregating
 * peer-review artifacts using a pluggable {@link ScoringStrategy}.
 *
 * <p><b>Gap 2.1 (Confidence-Weighted Scoring):</b> the default strategy is
 * {@link ConfidenceWeightedScoringStrategy}, which weights each reviewer's
 * scores by their self-reported confidence (0.0–1.0). This gives more
 * influence to reviewers who are confident in their assessment.
 *
 * <p><b>Gap 2.2 (Pluggable Scoring Strategies):</b> the strategy is selected
 * via the {@code scoring-strategy} stage option in protocol configuration.
 * Supported values:
 * <ul>
 *   <li>{@code confidence-weighted} (default) — recommended for most use cases</li>
 *   <li>{@code average} — simple arithmetic mean, original behaviour</li>
 *   <li>{@code median} — robust to outlier reviewers</li>
 *   <li>{@code trimmed-mean} — drops highest/lowest, then averages</li>
 * </ul>
 *
 * <p>Uses the LLM-as-Judge scoring pattern with dimensions: accuracy,
 * completeness, clarity, reasoning.
 */
@Component
public class ScoreStageExecutor implements StageExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScoreStageExecutor.class);

    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public ScoreStageExecutor(EventPublisher events, ArtifactStore artifactStore) {
        this.events = events;
        this.artifactStore = artifactStore;
    }

    @Override
    public StageType stage() {
        return StageType.SCORE;
    }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        // Determine artifact label for distinguishing initial vs post-debate scoring runs.
        String label = opts.getString("artifact-label",
                ctx.debateRounds().isEmpty() ? "initial" : "post-debate");

        // Select the scoring strategy from protocol stage configuration.
        // Default: confidence-weighted (Gap 2.1 recommendation).
        ScoringStrategy strategy = resolveStrategy(opts.getString("scoring-strategy", "confidence-weighted"));
        log.debug("SCORE stage using strategy: {}", strategy.name());

        List<ScoreArtifact> stageScores = new ArrayList<>();

        for (Draft draft : ctx.drafts()) {
            // Collect all reviews that target this specific draft.
            List<ReviewArtifact> reviews = ctx.reviews().stream()
                    .filter(r -> r.draftId().equals(draft.draftId()))
                    .toList();

            // Enforce review quorum: skip drafts without enough reviews.
            if (reviews.size() < ctx.policy().minimumReviewsPerDraft()) {
                String warning = "Review quorum not met for " + draft.draftId() + ": "
                        + reviews.size() + "/" + ctx.policy().minimumReviewsPerDraft();
                ctx.addWarning(warning);
                events.publish(ctx.session().id(), stage().name(), "SCORE_SKIPPED", null,
                        Map.of("draftId", draft.draftId(), "reason", warning));
                continue;
            }

            // Delegate dimension aggregation and overall score calculation
            // to the selected scoring strategy.
            Map<String, Double> dimensions = strategy.aggregateDimensions(reviews);
            double weightedTotal = strategy.aggregateOverallScore(reviews);

            ScoreArtifact score = new ScoreArtifact(
                    strategy.name(),   // scorerId now indicates which strategy produced the score
                    draft.draftId(),
                    dimensions,
                    weightedTotal,
                    label);

            ctx.addScore(score);
            stageScores.add(score);
        }

        // Produce the summary for downstream debate-trigger and synthesis stages.
        // ── Gap 2.3 (Disagreement Escalation): check if post-debate scoring
        // still shows high variance and apply the configured escalation policy.
        double escalationThreshold = opts.getDouble("escalation-variance-threshold", 120.0);
        String policyStr = opts.getString("escalation-policy", "SYNTHESIZE_WITH_DISSENT");
        EscalationPolicy escalationPolicy = parseEscalationPolicy(policyStr);
        boolean isPostDebate = "post-debate".equals(label);

        ScoreSummary summary = summarize(stageScores, isPostDebate, escalationThreshold, escalationPolicy);
        ctx.setScoreSummary(summary);
        artifactStore.writeJson(ctx.session().id(), "normalized/scores-" + label + ".json", summary);

        // If no draft met review quorum and reviews were expected, this is a
        // fatal failure — synthesis cannot produce a meaningful answer.
        if (stageScores.isEmpty() && ctx.policy().minimumReviewsPerDraft() > 0) {
            ctx.markFailed(stage(), new IllegalStateException("No draft met review quorum for scoring"));
        }

        // If escalation was triggered and policy is HALT_AND_ESCALATE,
        // mark the context as failed to prevent synthesis from proceeding.
        if (summary.escalated()) {
            String escalationWarning = "Post-debate score variance " + String.format("%.2f", summary.variance())
                    + " exceeds escalation threshold " + escalationThreshold
                    + "; escalation policy: " + summary.escalationPolicy();
            ctx.addWarning(escalationWarning);
            log.warn("SCORE escalation triggered: {}", escalationWarning);

            if (summary.escalationPolicy() == EscalationPolicy.HALT_AND_ESCALATE) {
                ctx.markFailed(stage(), new IllegalStateException(
                        "Council halted due to persistent disagreement: " + escalationWarning));
            }
        }

        events.publish(ctx.session().id(), stage().name(), "SCORE_COMPLETED", null,
                Map.of("label", label,
                       "scoreCount", stageScores.size(),
                       "strategy", strategy.name(),
                       "variance", summary.variance(),
                       "escalated", summary.escalated(),
                       "escalationPolicy", summary.escalationPolicy() != null
                               ? summary.escalationPolicy().name() : "none"));
        return ctx;
    }

    /**
     * Resolve a {@link ScoringStrategy} implementation from the configuration
     * string. Returns confidence-weighted by default for unknown values.
     *
     * @param strategyName The strategy name from stage options.
     * @return The resolved strategy instance.
     */
    private ScoringStrategy resolveStrategy(String strategyName) {
        return switch (strategyName.toLowerCase()) {
            case "average"           -> new AverageScoringStrategy();
            case "median"            -> new MedianScoringStrategy();
            case "trimmed-mean"      -> new TrimmedMeanScoringStrategy();
            // Default to confidence-weighted (Gap 2.1 recommendation).
            default                  -> new ConfidenceWeightedScoringStrategy();
        };
    }

    /**
     * Parse the escalation policy from the configuration string.
     * Defaults to {@link EscalationPolicy#SYNTHESIZE_WITH_DISSENT} for
     * unrecognised values.
     *
     * @param value The policy string from stage options.
     * @return The resolved escalation policy.
     */
    private EscalationPolicy parseEscalationPolicy(String value) {
        try {
            return EscalationPolicy.valueOf(value.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown escalation policy '{}', defaulting to SYNTHESIZE_WITH_DISSENT", value);
            return EscalationPolicy.SYNTHESIZE_WITH_DISSENT;
        }
    }

    /**
     * Compute aggregate statistics across all scored drafts for this stage run.
     * The variance drives the debate trigger (high variance = high disagreement).
     *
     * <p><b>Gap 2.3:</b> For post-debate scoring runs, if variance still exceeds
     * the escalation threshold, the summary is marked as escalated.
     *
     * @param scores              All score artifacts produced in this stage run.
     * @param isPostDebate        True if this is a scoring run after debate.
     * @param escalationThreshold Variance above which escalation triggers.
     * @param escalationPolicy    The configured escalation action.
     * @return Summary with variance, winning draft ID, and escalation status.
     */
    private ScoreSummary summarize(List<ScoreArtifact> scores, boolean isPostDebate,
                                   double escalationThreshold,
                                   EscalationPolicy escalationPolicy) {
        // The winning draft is the one with the highest weighted total score.
        String winner = scores.stream()
                .max(Comparator.comparingDouble(ScoreArtifact::weightedTotal))
                .map(ScoreArtifact::draftId)
                .orElse(null);

        double avg = scores.stream()
                .mapToDouble(ScoreArtifact::weightedTotal)
                .average()
                .orElse(0.0);

        // Population variance: measures disagreement between drafts.
        // High variance triggers debate in the rigorous protocol.
        double variance = scores.stream()
                .mapToDouble(s -> Math.pow(s.weightedTotal() - avg, 2))
                .average()
                .orElse(0.0);

        // Only trigger escalation on post-debate scoring.
        // Pre-debate high variance is expected and handled by the DEBATE stage.
        boolean escalated = isPostDebate && variance >= escalationThreshold;

        return new ScoreSummary(scores, variance, winner,
                escalated, escalated ? escalationPolicy : null);
    }
}
