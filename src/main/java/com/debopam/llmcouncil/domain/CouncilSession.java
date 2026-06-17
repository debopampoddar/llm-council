package com.debopam.llmcouncil.domain;

import java.time.Instant;

/**
 * Immutable request and status snapshot for one council run.
 *
 * <p>The public request intentionally contains a profile and depth mode rather
 * than a raw protocol ID. Protocol selection is application-owned and resolved
 * through configuration so callers cannot bypass validation or cost controls.
 */
public record CouncilSession(
        String id,
        String question,
        String context,
        DepthMode depthMode,
        String profileId,
        String policyId,
        String protocolId,
        CouncilStatus status,
        Instant createdAt,
        Instant updatedAt,
        String finalAnswer,
        String failureReason
) {

    /** Create a new unresolved session. Policy/protocol are filled at run time. */
    public static CouncilSession create(String id, String question, String context,
                                        DepthMode depthMode, String profileId) {
        Instant now = Instant.now();
        return new CouncilSession(id, question, context, depthMode, profileId,
                                  null, null, CouncilStatus.CREATED, now, now, null, null);
    }

    /** Return a copy with status changed and update timestamp refreshed. */
    public CouncilSession withStatus(CouncilStatus newStatus) {
        return new CouncilSession(id, question, context, depthMode, profileId,
                                  policyId, protocolId, newStatus, createdAt, Instant.now(),
                                  finalAnswer, failureReason);
    }

    /** Return a copy that records the resolved policy/protocol for auditability. */
    public CouncilSession withResolution(String resolvedPolicyId, String resolvedProtocolId) {
        return new CouncilSession(id, question, context, depthMode, profileId,
                                  resolvedPolicyId, resolvedProtocolId, status, createdAt,
                                  Instant.now(), finalAnswer, failureReason);
    }

    /** Return a copy with the final answer captured in session state. */
    public CouncilSession withFinalAnswer(String answer) {
        return new CouncilSession(id, question, context, depthMode, profileId,
                                  policyId, protocolId, status, createdAt, Instant.now(),
                                  answer, failureReason);
    }

    /** Return a copy with a failure reason for user-facing diagnostics. */
    public CouncilSession withFailureReason(String reason) {
        return new CouncilSession(id, question, context, depthMode, profileId,
                                  policyId, protocolId, status, createdAt, Instant.now(),
                                  finalAnswer, reason);
    }
}
