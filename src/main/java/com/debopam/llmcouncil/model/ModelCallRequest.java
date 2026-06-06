/**
 * Auto-generated documentation for ModelCallRequest.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.orchestration.StageType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Request describing a single model call within a council stage.
 *
 * <p>Fields are intentionally explicit so that downstream model clients
 * can log and enforce constraints (timeout, max tokens, temperature,
 * JSON mode) in a consistent way across providers.</p>
 */
public record ModelCallRequest(
        UUID sessionId,
        StageType stage,
        String logicalModelId,
        String providerModelId,
        List<ChatMessage> messages,
        Integer maxOutputTokens,
        Double temperature,
        boolean requestJson,
        Duration timeout
) {
}
