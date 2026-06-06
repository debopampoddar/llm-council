/**
 * Auto-generated documentation for CouncilProperties.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.config;

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
        Map<String, ProfileConfig> profiles,
        Map<String, ProtocolConfig> protocols) {

    public String runsDir() {
        return runsDir == null || runsDir.isBlank() ? "runs" : runsDir;
    }

    public String defaultProfileId() {
        return defaultProfileId == null || defaultProfileId.isBlank() ? "local-mock" : defaultProfileId;
    }

    public Map<String, ProtocolConfig> protocols() {
        return protocols == null ? Map.of() : protocols;
    }

    public record ProviderConfig(
            String kind,
            URI baseUrl,
            String apiKeyEnv,
            Integer maxConcurrentRequests,
            Duration timeout
    ) {}

    public record ModelConfig(
            String providerId,
            String providerModelId,
            Boolean local,
            Boolean supportsJsonMode,
            Integer defaultOutputTokens,
            Double temperature,
            java.time.Duration timeout
    ) {}

    public record ProfileConfig(
            List<String> memberModelIds,
            String chairModelId,
            String freshEyesModelId,
            String protocolId
    ) {}

    public record ProtocolConfig(
            String description,
            List<String> orderedStages,
            Map<String, StageOptionsConfig> stageOptions
    ) {}

    public record StageOptionsConfig(
            String reviewMode,
            Integer maxRounds,
            Double debateTriggerScoreVariance,
            Integer debateTriggerDissentCount,
            Boolean forceRun,
            Boolean preserveDissent,
            Boolean exportRawArtifacts,
            String artifactLabel
    ) {}
}
