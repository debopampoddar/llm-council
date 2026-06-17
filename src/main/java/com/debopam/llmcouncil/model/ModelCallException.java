package com.debopam.llmcouncil.model;

/**
 * Runtime exception for model-provider failures.
 *
 * <p>The category is intentionally stable and API-friendly. The message remains
 * human-readable for logs and existing response fields.
 */
public class ModelCallException extends RuntimeException {

    private final ModelFailureCategory category;
    private final String provider;
    private final String providerModelId;

    public ModelCallException(String message) {
        this(ModelFailureCategory.MODEL_CALL_FAILED, null, null, message, null);
    }

    public ModelCallException(String message, Throwable cause) {
        this(ModelFailureCategory.MODEL_CALL_FAILED, null, null, message, cause);
    }

    public ModelCallException(ModelFailureCategory category,
                              String provider,
                              String providerModelId,
                              String message) {
        this(category, provider, providerModelId, message, null);
    }

    public ModelCallException(ModelFailureCategory category,
                              String provider,
                              String providerModelId,
                              String message,
                              Throwable cause) {
        super(message, cause);
        this.category = category == null ? ModelFailureCategory.UNKNOWN : category;
        this.provider = provider;
        this.providerModelId = providerModelId;
    }

    public ModelFailureCategory category() {
        return category;
    }

    public String provider() {
        return provider;
    }

    public String providerModelId() {
        return providerModelId;
    }
}
