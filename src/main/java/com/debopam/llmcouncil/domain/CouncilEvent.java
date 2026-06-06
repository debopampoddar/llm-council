/**
 * Auto-generated documentation for CouncilEvent.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CouncilEvent(
        UUID id,
        UUID sessionId,
        Instant occurredAt,
        String stage,
        String type,
        String modelId,
        Map<String, Object> payload
) {
    public static CouncilEvent of(UUID sessionId, String stage, String type, String modelId, Map<String, Object> payload) {
        return new CouncilEvent(UUID.randomUUID(), sessionId, Instant.now(), stage, type, modelId, payload == null ? Map.of() : payload);
    }
}

