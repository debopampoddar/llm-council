/**
 * Auto-generated documentation for DebateTriggerPolicy.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import org.springframework.stereotype.Component;

@Component
public class DebateTriggerPolicy {
    public DebateDecision shouldRun(CouncilContext context, ProtocolStageOptions options) {
        if (context.scoreSummary() == null) {
            return DebateDecision.skip("No score summary is available.");
        }
        if (context.drafts().size() < 2) {
            return DebateDecision.skip("At least two drafts are required for debate.");
        }
        if (options.forceRunOrDefault(false)) {
            return DebateDecision.run("Protocol forces debate.");
        }
        double varianceThreshold = options.debateTriggerScoreVarianceOrDefault(120.0);
        if (context.scoreSummary().variance() >= varianceThreshold) {
            return DebateDecision.run("Score variance " + context.scoreSummary().variance() + " exceeds threshold " + varianceThreshold + ".");
        }
        int dissentThreshold = options.debateTriggerDissentCountOrDefault(2);
        int dissentCount = countDissent(context);
        if (dissentCount >= dissentThreshold) {
            return DebateDecision.run("Dissent count " + dissentCount + " exceeds threshold " + dissentThreshold + ".");
        }
        return DebateDecision.skip("Score variance and dissent count are below debate thresholds.");
    }

    private int countDissent(CouncilContext context) {
        int count = 0;
        for (PeerReviewOutput review : context.reviews()) {
            for (DraftEvaluation evaluation : review.evaluations()) {
                if (evaluation.whatWouldChangeMyMind() != null && !evaluation.whatWouldChangeMyMind().isBlank()) {
                    count++;
                }
            }
        }
        return count;
    }

    public record DebateDecision(boolean run, String reason) {
        public static DebateDecision run(String reason) {
            return new DebateDecision(true, reason);
        }

        public static DebateDecision skip(String reason) {
            return new DebateDecision(false, reason);
        }
    }
}
