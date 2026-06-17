package com.debopam.llmcouncil.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Replayable event emitted by the council engine.
 *
 * <p>The first implementation stores events in memory, but the record shape is
 * deliberately durable-store friendly: immutable ID, timestamp, stage, type,
 * optional model ID, and structured payload.
 */
public record CouncilEvent(
        String id,
        String sessionId,
        Instant occurredAt,
        String stage,
        String type,
        String modelId,
        Map<String, Object> payload
) {
    public static CouncilEvent of(String sessionId, String stage, String type,
                                  String modelId, Map<String, Object> payload) {
        return new CouncilEvent(UUID.randomUUID().toString(), sessionId, Instant.now(),
                                stage, type, modelId, Map.copyOf(payload));
    }
}
