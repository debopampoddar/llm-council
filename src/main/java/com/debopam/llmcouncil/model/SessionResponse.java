/**
 * Auto-generated documentation for SessionResponse.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.domain.CouncilStatus;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID sessionId,
        CouncilStatus status,
        String profileId,
        String depthMode,
        Instant createdAt,
        Instant updatedAt,
        String finalAnswer,
        String failureReason
) {}
