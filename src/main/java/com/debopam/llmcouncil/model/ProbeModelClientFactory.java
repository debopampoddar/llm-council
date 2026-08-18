package com.debopam.llmcouncil.model;

/** Builds a real, non-retrying client for an explicitly requested live probe. */
@FunctionalInterface
public interface ProbeModelClientFactory {
    ModelClient create(String provider, String providerModelId);
}
