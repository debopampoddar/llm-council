package com.debopam.llmcouncil.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts one reviewer's untrusted parsed output into quorum-safe evidence.
 *
 * <p>Review quorum counts reviewer/draft pairs, not JSON objects. Duplicate
 * objects from one model therefore cannot inflate quorum. Unknown draft ids and
 * self-reviews are excluded, and the caller receives the exact missing draft
 * ids so a partial review call cannot be reported as complete.
 */
final class ReviewEvidence {

    private ReviewEvidence() {
    }

    static Batch normalize(CouncilContext ctx, String reviewerId,
                           List<StructuredOutputParser.ReviewJson> parsed,
                           String rawText) {
        return normalize(ctx, reviewerId, parsed, rawText, null);
    }

    /**
     * Normalizes a targeted recovery response.
     *
     * <p>{@code requiredDraftIds} is {@code null} for the initial full review
     * call. Recovery calls pass the exact ids that were missing so a model
     * cannot satisfy recovery by returning a duplicate review that was already
     * accepted from its first response.
     */
    static Batch normalize(CouncilContext ctx, String reviewerId,
                           List<StructuredOutputParser.ReviewJson> parsed,
                           String rawText, List<String> requiredDraftIds) {
        Map<String, Draft> draftsById = new LinkedHashMap<>();
        ctx.drafts().forEach(draft -> draftsById.put(draft.draftId(), draft));

        Set<String> expected = new LinkedHashSet<>();
        draftsById.values().stream()
                .filter(draft -> !reviewerId.equals(draft.modelId()))
                .map(Draft::draftId)
                .forEach(expected::add);
        if (requiredDraftIds != null) {
            expected.retainAll(new LinkedHashSet<>(requiredDraftIds));
        }

        Map<String, ReviewArtifact> accepted = new LinkedHashMap<>();
        int duplicateCount = 0;
        int unknownDraftCount = 0;
        int selfReviewCount = 0;

        for (StructuredOutputParser.ReviewJson review : parsed) {
            Draft draft = draftsById.get(review.draftId());
            if (draft == null) {
                unknownDraftCount++;
                continue;
            }
            if (reviewerId.equals(draft.modelId())) {
                selfReviewCount++;
                continue;
            }
            if (!expected.contains(review.draftId())) {
                unknownDraftCount++;
                continue;
            }
            List<CriterionScore> criteria = safeList(review.criteria());
            List<String> issues = new ArrayList<>(safeList(review.issues()));
            int overallScore = review.overallScore();
            boolean trustBoundaryFailed = criteria.stream()
                    .anyMatch(criterion -> "trust-boundary".equalsIgnoreCase(criterion.name())
                            && criterion.score() < 50);
            if (trustBoundaryFailed) {
                overallScore = Math.min(overallScore, 25);
                issues.add("Draft failed the trust-boundary criterion; its overall score was capped at 25.");
            }
            ReviewArtifact artifact = new ReviewArtifact(
                    reviewerId,
                    review.draftId(),
                    safeList(review.strengths()),
                    List.copyOf(issues),
                    criteria,
                    overallScore,
                    review.confidence(),
                    rawText);
            if (accepted.putIfAbsent(review.draftId(), artifact) != null) {
                duplicateCount++;
            }
        }

        List<String> missing = new ArrayList<>(expected);
        missing.removeAll(accepted.keySet());
        return new Batch(List.copyOf(accepted.values()), List.copyOf(expected),
                         List.copyOf(missing), duplicateCount,
                         unknownDraftCount, selfReviewCount);
    }

    /** Combines an initial batch with one targeted recovery batch. */
    static Batch merge(Batch initial, Batch recovery) {
        Map<String, ReviewArtifact> accepted = new LinkedHashMap<>();
        initial.reviews().forEach(review -> accepted.put(review.draftId(), review));
        recovery.reviews().forEach(review -> accepted.putIfAbsent(review.draftId(), review));
        List<String> missing = new ArrayList<>(initial.expectedDraftIds());
        missing.removeAll(accepted.keySet());
        return new Batch(List.copyOf(accepted.values()), initial.expectedDraftIds(),
                List.copyOf(missing), initial.duplicateCount() + recovery.duplicateCount(),
                initial.unknownDraftCount() + recovery.unknownDraftCount(),
                initial.selfReviewCount() + recovery.selfReviewCount());
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    record Batch(
            List<ReviewArtifact> reviews,
            List<String> expectedDraftIds,
            List<String> missingDraftIds,
            int duplicateCount,
            int unknownDraftCount,
            int selfReviewCount
    ) {
        boolean complete() {
            return missingDraftIds.isEmpty();
        }
    }
}
