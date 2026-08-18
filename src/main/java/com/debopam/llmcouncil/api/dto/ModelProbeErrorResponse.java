package com.debopam.llmcouncil.api.dto;

/** Actionable refusal from the model-probe boundary. */
public record ModelProbeErrorResponse(String message, String remediation) {
}
