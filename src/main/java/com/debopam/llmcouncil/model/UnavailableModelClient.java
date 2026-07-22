package com.debopam.llmcouncil.model;

/**
 * Explicit failing client used when a configured real provider is unavailable.
 *
 * <p>This is intentionally different from silently falling back to a mock. It
 * lets the application start for documentation/local setup while ensuring a
 * real profile cannot accidentally produce fake council output.
 */
public class UnavailableModelClient implements ModelClient {
    private final String modelId;
    private final String reason;

    public UnavailableModelClient(String modelId, String reason) {
        this.modelId = modelId;
        this.reason = reason;
    }

    /**
     * @return why this model is unavailable, phrased as an actionable
     *         instruction. Never contains credential material.
     */
    public String reason() {
        return reason;
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) throws ModelCallException {
        throw new ModelCallException(ModelFailureCategory.CONFIGURATION_ERROR,
                                     null,
                                     request.providerModelId(),
                                     "Model '" + modelId + "' is unavailable: " + reason);
    }
}
