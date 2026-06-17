package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.orchestration.ModelFailure;

public record ModelFailureResponse(
        String modelId,
        String provider,
        String providerModelId,
        String category,
        String message
) {
    public static ModelFailureResponse from(ModelFailure failure) {
        return new ModelFailureResponse(
                failure.modelId(),
                failure.provider(),
                failure.providerModelId(),
                failure.category(),
                failure.message());
    }
}
