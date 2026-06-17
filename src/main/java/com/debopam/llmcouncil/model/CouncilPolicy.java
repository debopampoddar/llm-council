package com.debopam.llmcouncil.model;

import java.util.List;

/**
 * Fully resolved execution policy selected from profile plus depth mode.
 *
 * <p>The policy is the business contract used by stage executors: which member
 * models participate, which chair and validator are used, which protocol runs,
 * and what quorum is required before downstream stages can trust the evidence.
 */
public record CouncilPolicy(
        String id,
        String protocolId,
        List<String> memberModelIds,
        String chairModelId,
        String validatorModelId,
        int minimumSuccessfulDrafts,
        int minimumReviewsPerDraft,
        boolean validationRequired,
        boolean allowPartial
) {
    public CouncilPolicy {
        memberModelIds = List.copyOf(memberModelIds);
        if (minimumSuccessfulDrafts < 1) {
            throw new IllegalArgumentException("minimumSuccessfulDrafts must be at least 1");
        }
        if (minimumReviewsPerDraft < 0) {
            throw new IllegalArgumentException("minimumReviewsPerDraft must not be negative");
        }
    }
}
