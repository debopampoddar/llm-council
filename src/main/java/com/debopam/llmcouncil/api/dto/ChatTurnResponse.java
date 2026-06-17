package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.chat.ChatTurn;

import java.time.Instant;

public record ChatTurnResponse(
        String turnId,
        String userMessage,
        String assistantAnswer,
        String councilSessionId,
        String status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static ChatTurnResponse from(ChatTurn turn) {
        return new ChatTurnResponse(
                turn.id(),
                turn.userMessage(),
                turn.assistantAnswer(),
                turn.councilSessionId(),
                turn.status().name(),
                turn.failureReason(),
                turn.createdAt(),
                turn.updatedAt());
    }
}
