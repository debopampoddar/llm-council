/**
 * Auto-generated documentation for ModelProfile.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import java.time.Duration;

/**
 * Static configuration for a single logical model in the council.
 *
 * <p>Most fields map directly from {@code council.models[ID]} properties,
 * with sensible defaults for optional values.</p>
 */
public record ModelProfile(
        String id,
        String providerId,
        String providerModelId,
        boolean local,
        boolean supportsJsonMode,
        int defaultOutputTokens,
        double temperature,
        Duration defaultTimeout
) {
}
