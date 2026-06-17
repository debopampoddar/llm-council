package com.debopam.llmcouncil.chat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ChatEvent(
        String id,
        String chatId,
        Instant occurredAt,
        String type,
        Map<String, Object> payload
) {
    public static ChatEvent of(String chatId, String type, Map<String, Object> payload) {
        return new ChatEvent(UUID.randomUUID().toString(), chatId, Instant.now(), type, Map.copyOf(payload));
    }
}
