// ── ScoreStageExecutor.java ───────────────────────────────────────────────
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SCORE stage: assigns numeric scores to each draft.
 * Uses the LLM-as-Judge scoring pattern with dimensions: accuracy,
 * completeness, clarity, reasoning.
 */
@Component
public class ScoreStageExecutor implements StageExecutor {
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public ScoreStageExecutor(EventPublisher events, ArtifactStore artifactStore) {
        this.events = events; this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.SCORE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        String label = opts.getString("artifact-label",
                                      ctx.debateRounds().isEmpty() ? "initial" : "post-debate");
        List<ScoreArtifact> stageScores = new java.util.ArrayList<>();
        for (Draft draft : ctx.drafts()) {
            List<ReviewArtifact> reviews = ctx.reviews().stream()
                                             .filter(r -> r.draftId().equals(draft.draftId()))
                                             .toList();
            if (reviews.size() < ctx.policy().minimumReviewsPerDraft()) {
                String warning = "Review quorum not met for " + draft.draftId() + ": "
                                 + reviews.size() + "/" + ctx.policy().minimumReviewsPerDraft();
                ctx.addWarning(warning);
                events.publish(ctx.session().id(), stage().name(), "SCORE_SKIPPED", null,
                               Map.of("draftId", draft.draftId(), "reason", warning));
                continue;
            }

            Map<String, Double> dimensions = averageDimensions(reviews);
            double weightedTotal = reviews.stream().mapToInt(ReviewArtifact::overallScore).average().orElse(0.0);
            ScoreArtifact score = new ScoreArtifact("aggregated", draft.draftId(),
                                                    dimensions, weightedTotal, label);
            ctx.addScore(score);
            stageScores.add(score);
        }
        ScoreSummary summary = summarize(stageScores);
        ctx.setScoreSummary(summary);
        artifactStore.writeJson(ctx.session().id(), "normalized/scores-" + label + ".json", summary);

        if (stageScores.isEmpty() && ctx.policy().minimumReviewsPerDraft() > 0) {
            ctx.markFailed(stage(), new IllegalStateException("No draft met review quorum for scoring"));
        }

        events.publish(ctx.session().id(), stage().name(), "SCORE_COMPLETED", null,
                       Map.of("label", label, "scoreCount", stageScores.size(),
                              "variance", summary.variance()));
        return ctx;
    }

    private Map<String, Double> averageDimensions(List<ReviewArtifact> reviews) {
        Map<String, List<CriterionScore>> grouped = reviews.stream()
                .flatMap(r -> r.criteria().stream())
                .collect(Collectors.groupingBy(CriterionScore::name, LinkedHashMap::new, Collectors.toList()));
        Map<String, Double> dimensions = new LinkedHashMap<>();
        grouped.forEach((name, criteria) ->
                dimensions.put(name, criteria.stream().mapToInt(CriterionScore::score).average().orElse(0.0)));
        if (dimensions.isEmpty()) {
            dimensions.put("overall", reviews.stream().mapToInt(ReviewArtifact::overallScore).average().orElse(0.0));
        }
        return dimensions;
    }

    private ScoreSummary summarize(List<ScoreArtifact> scores) {
        String winner = scores.stream()
                              .max(java.util.Comparator.comparingDouble(ScoreArtifact::weightedTotal))
                              .map(ScoreArtifact::draftId)
                              .orElse(null);
        double avg = scores.stream().mapToDouble(ScoreArtifact::weightedTotal).average().orElse(0.0);
        double variance = scores.stream()
                                .mapToDouble(s -> Math.pow(s.weightedTotal() - avg, 2))
                                .average().orElse(0.0);
        return new ScoreSummary(scores, variance, winner);
    }
}
