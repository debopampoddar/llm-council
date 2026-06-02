package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.orchestration.StageType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public record ModelCallRequest(
        UUID sessionId,
        StageType stage,
        String logicalModelId,
        String providerModelId,
        List<ChatMessage> messages,
        Integer maxOutputTokens,
        Double temperature,
        boolean requestJson,
        Duration timeout) {
}
