package com.debopam.llmcouncil.application;

/**
 * Immediate result of asking the async executor to start a council run.
 */
public record CouncilRunSubmission(
        String sessionId,
        boolean accepted,
        String status,
        String message
) {
    public static CouncilRunSubmission accepted(String sessionId) {
        return new CouncilRunSubmission(sessionId, true, "ACCEPTED", "Council run accepted");
    }

    public static CouncilRunSubmission rejected(String sessionId, String message) {
        return new CouncilRunSubmission(sessionId, false, "REJECTED", message);
    }
}
