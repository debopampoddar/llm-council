/**
 * Auto-generated documentation for ModelCallException.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

public class ModelCallException extends RuntimeException {
    private final String modelId;
    private final String providerId;

    public ModelCallException(String modelId, String providerId, String message, Throwable cause) {
        super(message, cause);
        this.modelId = modelId;
        this.providerId = providerId;
    }

    public String modelId() {
        return modelId;
    }

    public String providerId() {
        return providerId;
    }
}
