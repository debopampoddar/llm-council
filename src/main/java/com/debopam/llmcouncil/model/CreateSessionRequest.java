/**
 * Auto-generated documentation for CreateSessionRequest.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String question,
        String context,
        String profileId,
        String depthMode
) {}
