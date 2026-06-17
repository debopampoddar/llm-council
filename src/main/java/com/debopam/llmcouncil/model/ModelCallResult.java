package com.debopam.llmcouncil.model;

import java.time.Duration;

/**
 * Normalized model response plus lightweight operational metadata.
 *
 * <p>Provider adapters may not always expose token usage, so token fields are
 * nullable. Latency is populated by the adapter when available.
 */
public record ModelCallResult(
        String text,
        Long promptTokens,
        Long completionTokens,
        Duration latency
) {
    public ModelCallResult(String text) {
        this(text, null, null, null);
    }
}
