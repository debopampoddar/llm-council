package com.debopam.llmcouncil.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CouncilEventResponse(
        UUID id,
        UUID sessionId,
        Instant occurredAt,
        String stage,
        String type,
        String modelId,
        Map<String, Object> payload
) {}
