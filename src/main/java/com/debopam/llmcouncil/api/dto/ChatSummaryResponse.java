package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatTurn;

import java.time.Instant;

/**
 * Lightweight chat listing entry.
 *
 * <p>Deliberately excludes turn bodies: a chat list renders dozens of entries
 * and does not need every answer the council has ever produced.
 *
 * @param chatId           the chat id
 * @param profileId        profile this chat runs against
 * @param depthMode        depth this chat runs at
 * @param status           {@code RUNNING} when a turn is in flight, else {@code IDLE}
 * @param turnCount        number of turns so far
 * @param firstUserMessage opening message, truncated for display
 * @param createdAt        when the chat was created
 * @param updatedAt        when the chat last changed
 */
public record ChatSummaryResponse(
        String chatId,
        String profileId,
        String depthMode,
        String status,
        int turnCount,
        String firstUserMessage,
        Instant createdAt,
        Instant updatedAt
) {
    private static final int PREVIEW_CHARS = 120;

    /**
     * Summarise a chat for listing.
     *
     * @param chat the chat to summarise
     * @return the summary
     */
    public static ChatSummaryResponse from(ChatSession chat) {
        String preview = chat.turns().stream()
                             .map(ChatTurn::userMessage)
                             .filter(message -> message != null && !message.isBlank())
                             .findFirst()
                             .map(ChatSummaryResponse::truncate)
                             .orElse("");
        return new ChatSummaryResponse(
                chat.id(),
                chat.profileId(),
                chat.depthMode().name(),
                chat.hasRunningTurn() ? "RUNNING" : "IDLE",
                chat.turns().size(),
                preview,
                chat.createdAt(),
                chat.updatedAt());
    }

    private static String truncate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= PREVIEW_CHARS ? oneLine : oneLine.substring(0, PREVIEW_CHARS) + "...";
    }
}
