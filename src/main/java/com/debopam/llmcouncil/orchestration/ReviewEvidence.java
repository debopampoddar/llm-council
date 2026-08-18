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
        Map<String, Draft> draftsById = new LinkedHashMap<>();
        ctx.drafts().forEach(draft -> draftsById.put(draft.draftId(), draft));

        Set<String> expected = new LinkedHashSet<>();
        draftsById.values().stream()
                .filter(draft -> !reviewerId.equals(draft.modelId()))
                .map(Draft::draftId)
                .forEach(expected::add);

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
            ReviewArtifact artifact = new ReviewArtifact(
                    reviewerId,
                    review.draftId(),
                    safeList(review.strengths()),
                    safeList(review.issues()),
                    safeList(review.criteria()),
                    review.overallScore(),
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
