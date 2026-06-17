package com.debopam.llmcouncil.model;

/**
 * Stable failure categories exposed through API responses and events.
 *
 * <p>These categories separate provider/runtime failures from council protocol
 * failures so callers can distinguish "Ollama is down" from "the council did
 * not reach quorum".
 */
public enum ModelFailureCategory {
    PROVIDER_UNAVAILABLE,
    MODEL_NOT_FOUND,
    MODEL_TIMEOUT,
    MODEL_CALL_FAILED,
    QUORUM_NOT_MET,
    INVALID_MODEL_OUTPUT,
    VALIDATION_FAILED,
    CONFIGURATION_ERROR,
    UNKNOWN
}
