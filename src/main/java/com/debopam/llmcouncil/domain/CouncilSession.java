/**
 * Auto-generated documentation for CouncilSession.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.domain;

import java.time.Instant;
import java.util.UUID;

public record CouncilSession(
        UUID id,
        CouncilStatus status,
        String question,
        String context,
        String profileId,
        DepthMode depthMode,
        Instant createdAt,
        Instant updatedAt,
        String finalAnswer,
        String failureReason
) {
    public static CouncilSession created(UUID id, String question, String context, String profileId, DepthMode depthMode) {
        Instant now = Instant.now();
        return new CouncilSession(id, CouncilStatus.CREATED, question, context, profileId, depthMode, now, now, null, null);
    }

    public CouncilSession running() {
        return new CouncilSession(id, CouncilStatus.RUNNING, question, context, profileId, depthMode, createdAt, Instant.now(), finalAnswer, null);
    }

    public CouncilSession completed(String answer) {
        return new CouncilSession(id, CouncilStatus.COMPLETED, question, context, profileId, depthMode, createdAt, Instant.now(), answer, null);
    }

    public CouncilSession failed(String reason) {
        return new CouncilSession(id, CouncilStatus.FAILED, question, context, profileId, depthMode, createdAt, Instant.now(), finalAnswer, reason);
    }
}

