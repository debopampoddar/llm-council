package com.debopam.llmcouncil.model;

import java.net.URI;
import java.time.Duration;

public record ProviderProfile(
        String id,
        ProviderKind kind,
        URI baseUrl,
        String apiKeyEnv,
        int maxConcurrentRequests,
        Duration timeout
) {
    public String resolveApiKey() {
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            return null;
        }
        return System.getenv(apiKeyEnv);
    }
}
