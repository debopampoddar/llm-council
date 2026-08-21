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
        ContextPurpose contextPurpose,
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

    /** Older stored sessions and existing callers safely default to evidence mode. */
    public CouncilSession {
        contextPurpose = contextPurpose == null ? ContextPurpose.EVIDENCE : contextPurpose;
    }

    /** Backward-compatible constructor for code that predates context-purpose metadata. */
    public CouncilSession(String id, String question, String context,
                          DepthMode depthMode, String profileId,
                          String policyId, String protocolId, CouncilStatus status,
                          Instant createdAt, Instant updatedAt,
                          String finalAnswer, String failureReason) {
        this(id, question, context, ContextPurpose.EVIDENCE, depthMode, profileId,
                policyId, protocolId, status, createdAt, updatedAt, finalAnswer, failureReason);
    }

    /** Create a new unresolved session. Policy/protocol are filled at run time. */
    public static CouncilSession create(String id, String question, String context,
                                        DepthMode depthMode, String profileId) {
        return create(id, question, context, ContextPurpose.EVIDENCE, depthMode, profileId);
    }

    /** Create a new unresolved session with an explicit supporting-context purpose. */
    public static CouncilSession create(String id, String question, String context,
                                        ContextPurpose contextPurpose,
                                        DepthMode depthMode, String profileId) {
        Instant now = Instant.now();
        return new CouncilSession(id, question, context, contextPurpose, depthMode, profileId,
                                  null, null, CouncilStatus.CREATED, now, now, null, null);
    }

    /** Return a copy with the context prepared for model consumption. */
    public CouncilSession withContext(String preparedContext) {
        return new CouncilSession(id, question, preparedContext, contextPurpose, depthMode, profileId,
                                  policyId, protocolId, status, createdAt, Instant.now(),
                                  finalAnswer, failureReason);
    }

    /** Return a copy with status changed and update timestamp refreshed. */
    public CouncilSession withStatus(CouncilStatus newStatus) {
        return new CouncilSession(id, question, context, contextPurpose, depthMode, profileId,
                                  policyId, protocolId, newStatus, createdAt, Instant.now(),
                                  finalAnswer, failureReason);
    }

    /** Return a copy that records the resolved policy/protocol for auditability. */
    public CouncilSession withResolution(String resolvedPolicyId, String resolvedProtocolId) {
        return new CouncilSession(id, question, context, contextPurpose, depthMode, profileId,
                                  resolvedPolicyId, resolvedProtocolId, status, createdAt,
                                  Instant.now(), finalAnswer, failureReason);
    }

    /** Return a copy with the final answer captured in session state. */
    public CouncilSession withFinalAnswer(String answer) {
        return new CouncilSession(id, question, context, contextPurpose, depthMode, profileId,
                                  policyId, protocolId, status, createdAt, Instant.now(),
                                  answer, failureReason);
    }

    /** Return a copy with a failure reason for user-facing diagnostics. */
    public CouncilSession withFailureReason(String reason) {
        return new CouncilSession(id, question, context, contextPurpose, depthMode, profileId,
                                  policyId, protocolId, status, createdAt, Instant.now(),
                                  finalAnswer, reason);
    }
}
