package com.debopam.llmcouncil.application;

/** A safe, actionable refusal at the live model-probe boundary. */
public class ModelProbeRequestException extends RuntimeException {
    private final String remediation;

    public ModelProbeRequestException(String message, String remediation) {
        super(message);
        this.remediation = remediation;
    }

    public String remediation() {
        return remediation;
    }
}
