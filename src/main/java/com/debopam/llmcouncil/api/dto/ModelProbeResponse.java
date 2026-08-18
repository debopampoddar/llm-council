package com.debopam.llmcouncil.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Stable, non-sensitive result of a bounded provider-model probe. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelProbeResponse(
        String provider,
        String providerModelId,
        boolean reachable,
        String status,
        String detail,
        Long latencyMs,
        Long promptTokens,
        Long completionTokens
) {
}
