package com.debopam.llmcouncil.model;

public record ModelProfile(
        String id,
        String providerId,
        String providerModelId,
        boolean local,
        boolean supportsJsonMode,
        int defaultOutputTokens) {
}
