package com.debopam.llmcouncil.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "council")
public record CouncilProperties(
        String runsDir,
        String defaultProfileId,
        Map<String, ProviderConfig> providers,
        Map<String, ModelConfig> models,
        Map<String, ProfileConfig> profiles
) {
    public String runsDir() {
        return runsDir == null || runsDir.isBlank() ? "./runs" : runsDir;
    }

    public String defaultProfileId() {
        return defaultProfileId == null || defaultProfileId.isBlank() ? "local-mock" : defaultProfileId;
    }

    public record ProviderConfig(
            @NotBlank String kind,
            URI baseUrl,
            String apiKeyEnv,
            Integer maxConcurrentRequests,
            Duration timeout
    ) {}

    public record ModelConfig(
            @NotBlank String providerId,
            @NotBlank String providerModelId,
            Boolean local,
            Boolean supportsJsonMode,
            Integer defaultOutputTokens
    ) {}

    public record ProfileConfig(
            List<String> memberModelIds,
            String chairModelId,
            String freshEyesModelId,
            String protocolId
    ) {}
}

