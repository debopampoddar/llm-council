package com.debopam.llmcouncil.chat;

import java.time.Instant;

public record ChatTurn(
        String id,
        String userMessage,
        String assistantAnswer,
        String councilSessionId,
        ChatTurnStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static ChatTurn running(String id, String userMessage, String councilSessionId) {
        Instant now = Instant.now();
        return new ChatTurn(id, userMessage, null, councilSessionId,
                            ChatTurnStatus.RUNNING, null, now, now);
    }

    public ChatTurn completed(String answer) {
        return new ChatTurn(id, userMessage, answer, councilSessionId,
                            ChatTurnStatus.COMPLETED, null, createdAt, Instant.now());
    }

    public ChatTurn partial(String answer, String reason) {
        return new ChatTurn(id, userMessage, answer, councilSessionId,
                            ChatTurnStatus.PARTIAL, reason, createdAt, Instant.now());
    }

    public ChatTurn failed(String reason) {
        return new ChatTurn(id, userMessage, assistantAnswer, councilSessionId,
                            ChatTurnStatus.FAILED, reason, createdAt, Instant.now());
    }

    public ChatTurn rejected(String reason) {
        return new ChatTurn(id, userMessage, assistantAnswer, councilSessionId,
                            ChatTurnStatus.REJECTED, reason, createdAt, Instant.now());
    }
}
