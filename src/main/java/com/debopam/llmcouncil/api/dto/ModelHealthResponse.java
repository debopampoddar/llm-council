package com.debopam.llmcouncil.api.dto;

import java.util.List;

public record ModelHealthResponse(
        String modelId,
        String provider,
        String providerModelId,
        boolean available,
        String status,
        String detail,
        List<String> knownProviderModels
) {}
