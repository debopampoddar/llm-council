package com.debopam.llmcouncil.model;

import java.time.Duration;
import java.util.Map;

public record ModelCallResult(
        String logicalModelId,
        String providerId,
        String text,
        long promptTokens,
        long completionTokens,
        Duration latency,
        Map<String, Object> raw) {
}
