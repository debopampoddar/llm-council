// CouncilConfig.java
package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.MockModelClient;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.OllamaDirectModelClient;
import com.debopam.llmcouncil.model.SpringAiModelClient;
import com.debopam.llmcouncil.model.UnavailableModelClient;
import com.debopam.llmcouncil.orchestration.ProtocolDefinition;
import com.debopam.llmcouncil.orchestration.ProtocolDefinitionRegistry;
import com.debopam.llmcouncil.orchestration.ProtocolStageOptions;
import com.debopam.llmcouncil.orchestration.StageType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Wires all council components from configuration at startup.
 * Validates that referenced model IDs and protocol IDs exist.
 */
@Configuration
public class CouncilConfig {

    private static final Logger log = LoggerFactory.getLogger(CouncilConfig.class);

    private final CouncilProperties props;
    private final ModelRegistry modelRegistry;
    private final ProtocolDefinitionRegistry protocolRegistry;
    private final CouncilConfigurationValidator configurationValidator;
    private final String ollamaBaseUrl;
    private final Integer ollamaNumCtx;
    private final Integer ollamaNumThread;
    private final String ollamaKeepAlive;

    @Autowired(required = false) private OpenAiChatModel openAiChatModel;
    @Autowired(required = false) private AnthropicChatModel anthropicChatModel;

    public CouncilConfig(CouncilProperties props,
                         ModelRegistry modelRegistry,
                         ProtocolDefinitionRegistry protocolRegistry,
                         CouncilConfigurationValidator configurationValidator,
                         @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                         @Value("${spring.ai.ollama.chat.options.num-ctx:4096}") Integer ollamaNumCtx,
                         @Value("${spring.ai.ollama.chat.options.num-thread:0}") Integer ollamaNumThread,
                         @Value("${spring.ai.ollama.chat.options.keep_alive:10m}") String ollamaKeepAlive) {
        this.props = props;
        this.modelRegistry = modelRegistry;
        this.protocolRegistry = protocolRegistry;
        this.configurationValidator = configurationValidator;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaNumCtx = ollamaNumCtx;
        this.ollamaNumThread = ollamaNumThread;
        this.ollamaKeepAlive = ollamaKeepAlive;
    }

    @Bean
    public Map<String, CouncilProfile> councilProfiles() {
        Map<String, CouncilProfile> result = new LinkedHashMap<>();
        props.getProfiles().forEach((id, p) -> {
            Map<DepthMode, String> depthPolicies = new LinkedHashMap<>();
            p.getDepthPolicies().forEach((depth, policyId) ->
                    depthPolicies.put(DepthMode.valueOf(depth.toUpperCase()), policyId));
            result.put(id, new CouncilProfile(id,
                                              p.getDisplayName() != null ? p.getDisplayName() : id,
                                              p.isTestOnly(),
                                              p.getDefaultDepth(),
                                              depthPolicies));
        });
        return Collections.unmodifiableMap(result);
    }

    @Bean
    public Map<String, CouncilPolicy> councilPolicies() {
        Map<String, CouncilPolicy> result = new LinkedHashMap<>();
        props.getPolicies().forEach((id, p) ->
                result.put(id, new CouncilPolicy(id, p.getProtocolId(), p.getMemberModelIds(),
                                                 p.getChairModelId(), p.getValidatorModelId(),
                                                 p.getMinimumSuccessfulDrafts(),
                                                 p.getMinimumReviewsPerDraft(),
                                                 p.isValidationRequired(), p.isAllowPartial())));
        return Collections.unmodifiableMap(result);
    }

    @PostConstruct
    public void initRegistries() {
        configurationValidator.validate(props);

        // Build model profiles and clients
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        Map<String, ModelClient> clients = new LinkedHashMap<>();

        for (CouncilProperties.ModelProps mp : props.getModels()) {
            ModelProfile profile = new ModelProfile(
                    mp.getId(), mp.getProvider(), mp.getProviderModelId(),
                    mp.getDefaultOutputTokens(), mp.getTemperature(),
                    Duration.ofSeconds(mp.getTimeoutSeconds()), mp.getRole());
            profiles.put(mp.getId(), profile);
            clients.put(mp.getId(), buildClient(mp));
        }
        modelRegistry.register(profiles, clients);

        // Build protocol definitions
        Map<String, ProtocolDefinition> protocols = new LinkedHashMap<>();
        props.getProtocols().forEach((id, pp) -> {
            List<StageType> stages = pp.getOrderedStages().stream()
                                       .map(StageType::valueOf).collect(Collectors.toList());
            Map<StageType, ProtocolStageOptions> stageOpts = new LinkedHashMap<>();
            pp.getStageOptions().forEach((stageStr, rawOpts) ->
                                                 stageOpts.put(StageType.valueOf(stageStr), new ProtocolStageOptions(rawOpts)));
            protocols.put(id, new ProtocolDefinition(id, pp.getDescription(), stages, stageOpts));
        });
        protocolRegistry.register(protocols);
        validateConfiguration(profiles, protocols);

        log.info("CouncilConfig: {} models, {} protocols registered",
                 profiles.size(), protocols.size());
    }

    private ModelClient buildClient(CouncilProperties.ModelProps mp) {
        if ("mock".equalsIgnoreCase(mp.getProvider())) {
            return new MockModelClient(mp.getId());
        }
        return switch (mp.getProvider().toLowerCase()) {
            case "openai" -> openAiChatModel != null
                             ? new SpringAiModelClient(mp.getId(), ChatClient.create(openAiChatModel))
                             : fallbackClient(mp, "OpenAI ChatModel bean is not configured");
            case "anthropic" -> anthropicChatModel != null
                                ? new SpringAiModelClient(mp.getId(), ChatClient.create(anthropicChatModel))
                                : fallbackClient(mp, "Anthropic ChatModel bean is not configured");
            case "ollama" -> new OllamaDirectModelClient(mp.getId(), ollamaBaseUrl,
                                                          ollamaNumCtx, ollamaNumThread, ollamaKeepAlive);
            case "oci", "oci-openai", "openai-compatible" -> openAiChatModel != null
                             ? new SpringAiModelClient(mp.getId(), ChatClient.create(openAiChatModel))
                             : fallbackClient(mp, "OpenAI-compatible/OCI ChatModel bean is not configured");
            default -> fallbackClient(mp, "unsupported provider '" + mp.getProvider() + "'");
        };
    }

    private ModelClient fallbackClient(CouncilProperties.ModelProps mp, String reason) {
        if (props.isAllowMockFallback() || mp.isTestOnly()) {
            log.warn("Using mock fallback for model {}: {}", mp.getId(), reason);
            return new MockModelClient(mp.getId());
        }
        log.warn("Configured model {} is unavailable: {}", mp.getId(), reason);
        return new UnavailableModelClient(mp.getId(), reason);
    }

    private void validateConfiguration(Map<String, ModelProfile> models,
                                       Map<String, ProtocolDefinition> protocols) {
        props.getPolicies().forEach((policyId, policy) -> {
            require(protocols.containsKey(policy.getProtocolId()),
                    "Policy " + policyId + " references unknown protocol " + policy.getProtocolId());
            policy.getMemberModelIds().forEach(modelId ->
                    require(models.containsKey(modelId),
                            "Policy " + policyId + " references unknown member model " + modelId));
            require(models.containsKey(policy.getChairModelId()),
                    "Policy " + policyId + " references unknown chair model " + policy.getChairModelId());
            if (policy.getValidatorModelId() != null && !policy.getValidatorModelId().isBlank()) {
                require(models.containsKey(policy.getValidatorModelId()),
                        "Policy " + policyId + " references unknown validator model " + policy.getValidatorModelId());
            }
        });
        props.getProfiles().forEach((profileId, profile) ->
                profile.getDepthPolicies().forEach((depth, policyId) -> {
                    require(DepthMode.valueOf(depth.toUpperCase()) != null,
                            "Profile " + profileId + " has invalid depth " + depth);
                    require(props.getPolicies().containsKey(policyId),
                            "Profile " + profileId + " references unknown policy " + policyId);
                }));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(Objects.requireNonNull(message));
        }
    }
}
