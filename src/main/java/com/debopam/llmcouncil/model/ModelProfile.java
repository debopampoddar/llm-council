/**
 * Auto-generated documentation for ModelProfile.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

public record ModelProfile(
        String id,
        String providerId,
        String providerModelId,
        boolean local,
        boolean supportsJsonMode,
        int defaultOutputTokens) {
}
