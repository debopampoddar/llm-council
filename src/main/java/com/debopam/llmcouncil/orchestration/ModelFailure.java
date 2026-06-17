package com.debopam.llmcouncil.orchestration;

/**
 * Structured model failure captured during a council run.
 */
public record ModelFailure(
        String modelId,
        String provider,
        String providerModelId,
        String category,
        String message
) {}
