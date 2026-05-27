package com.debopam.llmcouncil.model;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String question,
        String context,
        String profileId,
        String depthMode
) {}
