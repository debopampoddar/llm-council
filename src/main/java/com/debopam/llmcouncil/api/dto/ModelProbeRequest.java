package com.debopam.llmcouncil.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A credential-free request for one bounded provider-model connectivity call. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ModelProbeRequest(
        String provider,
        String providerModelId,
        Boolean acknowledgeCloudCall
) {
    public boolean cloudCallAcknowledged() {
        return Boolean.TRUE.equals(acknowledgeCloudCall);
    }
}
