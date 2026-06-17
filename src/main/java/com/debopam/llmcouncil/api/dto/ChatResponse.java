package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.chat.ChatSession;

import java.time.Instant;
import java.util.List;

public record ChatResponse(
        String chatId,
        String profileId,
        String depthMode,
        String status,
        String summary,
        List<ChatTurnResponse> turns,
        Instant createdAt,
        Instant updatedAt
) {
    public static ChatResponse from(ChatSession chat) {
        return new ChatResponse(
                chat.id(),
                chat.profileId(),
                chat.depthMode().name(),
                chat.hasRunningTurn() ? "RUNNING" : "IDLE",
                chat.summary(),
                chat.turns().stream().map(ChatTurnResponse::from).toList(),
                chat.createdAt(),
                chat.updatedAt());
    }
}
