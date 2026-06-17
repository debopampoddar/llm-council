package com.debopam.llmcouncil.orchestration;

import java.util.List;
import java.util.Map;

/**
 * Fresh Eyes validation result for the final answer.
 *
 * <p>The validator is expected to see only the original request and final
 * answer, not the full debate transcript, to avoid anchoring on council history.
 */
public record ValidationArtifact(
        String validatorId,
        boolean approved,
        double confidence,
        List<String> issues,
        List<String> recommendedFixes,
        Map<String, String> criteria,
        boolean requiresHumanReview,
        String rawText
) {
    public ValidationArtifact {
        issues = List.copyOf(issues);
        recommendedFixes = List.copyOf(recommendedFixes);
        criteria = Map.copyOf(criteria);
    }
}
