package com.debopam.llmcouncil.api.dto;
import com.debopam.llmcouncil.domain.CouncilSession;

import java.time.Instant;

public record SessionResponse(
        String sessionId,
        String status,
        String question,
        String profileId,
        String depthMode,
        String policyId,
        String protocolId,
        String finalAnswer,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static SessionResponse from(CouncilSession s) {
        return new SessionResponse(s.id(), s.status().name(), s.question(), s.profileId(),
                                   s.depthMode().name(), s.policyId(), s.protocolId(),
                                   s.finalAnswer(), s.failureReason(), s.createdAt(), s.updatedAt());
    }
}
