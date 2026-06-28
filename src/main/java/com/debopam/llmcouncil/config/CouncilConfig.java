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
import com.debopam.llmcouncil.model.RetryableModelClient;
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
 *
 * <p>Model profiles and clients are constructed inside the {@link #modelRegistry()}
 * bean method, which produces an immutable {@link ModelRegistry}. Protocol
 * registration and cross-cutting validation remain in {@link #initRegistries()}.
 */
@Configuration
public class CouncilConfig {

    private static final Logger log = LoggerFactory.getLogger(CouncilConfig.class);

    private final CouncilProperties props;
    private final ProtocolDefinitionRegistry protocolRegistry;
    private final CouncilConfigurationValidator configurationValidator;
    private final String ollamaBaseUrl;
    private final Integer ollamaNumCtx;
    private final Integer ollamaNumThread;
    private final String ollamaKeepAlive;

    @Autowired(required = false) private OpenAiChatModel openAiChatModel;
    @Autowired(required = false) private AnthropicChatModel anthropicChatModel;

    public CouncilConfig(CouncilProperties props,
                         ProtocolDefinitionRegistry protocolRegistry,
                         CouncilConfigurationValidator configurationValidator,
                         @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                         @Value("${spring.ai.ollama.chat.options.num-ctx:4096}") Integer ollamaNumCtx,
                         @Value("${spring.ai.ollama.chat.options.num-thread:0}") Integer ollamaNumThread,
                         @Value("${spring.ai.ollama.chat.options.keep_alive:10m}") String ollamaKeepAlive) {
        this.props = props;
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

    /**
     * Constructs an immutable {@link ModelRegistry} containing every configured
     * model profile and its associated client.
     *
     * <p>Real (non-mock, non-unavailable) clients are wrapped in a
     * {@link RetryableModelClient} so transient provider failures are retried
     * with exponential backoff before propagating to the orchestration layer.
     *
     * @return a fully-populated, immutable {@link ModelRegistry}
     */
    @Bean
    public ModelRegistry modelRegistry() {
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        Map<String, ModelClient> clients = new LinkedHashMap<>();

        for (CouncilProperties.ModelProps mp : props.getModels()) {
            // Build the model profile from configuration properties.
            // councilRole determines the debate persona (PROPOSER/CRITIC/SYNTHESIZER).
            // modelFamily identifies the architecture for diversity validation.
            ModelProfile profile = new ModelProfile(
                    mp.getId(), mp.getProvider(), mp.getProviderModelId(),
                    mp.getDefaultOutputTokens(), mp.getTemperature(),
                    Duration.ofSeconds(mp.getTimeoutSeconds()), mp.getRole(),
                    mp.getCouncilRole(), mp.getModelFamily());
            profiles.put(mp.getId(), profile);

            // Build the client and optionally wrap with retry logic.
            ModelClient client = buildClient(mp);
            clients.put(mp.getId(), client);
        }

        log.info("ModelRegistry @Bean: {} model profiles constructed", profiles.size());
        return new ModelRegistry(profiles, clients);
    }

    /**
     * Registers protocol definitions and performs cross-cutting validation.
     *
     * <p>This runs after all {@code @Bean} methods, so the {@link ModelRegistry}
     * bean is already available in the application context.
     */
    @PostConstruct
    public void initRegistries() {
        configurationValidator.validate(props);

        // Build protocol definitions.
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

        // Validate references across models, protocols, and policies.
        // Rebuild the profiles map for validation (lightweight — just string keys).
        Map<String, ModelProfile> profilesForValidation = new LinkedHashMap<>();
        for (CouncilProperties.ModelProps mp : props.getModels()) {
            profilesForValidation.put(mp.getId(), new ModelProfile(
                    mp.getId(), mp.getProvider(), mp.getProviderModelId(),
                    mp.getDefaultOutputTokens(), mp.getTemperature(),
                    Duration.ofSeconds(mp.getTimeoutSeconds()), mp.getRole()));
        }
        validateConfiguration(profilesForValidation, protocols);

        log.info("CouncilConfig: {} models, {} protocols registered",
                 profilesForValidation.size(), protocols.size());
    }

    /**
     * Builds a {@link ModelClient} for the given model configuration.
     *
     * <p>Mock and unavailable clients are returned as-is. All other (real)
     * clients are wrapped in a {@link RetryableModelClient} using the per-model
     * retry configuration from {@link CouncilProperties.ModelProps}.
     */
    private ModelClient buildClient(CouncilProperties.ModelProps mp) {
        // Mock clients never need retry wrapping.
        if ("mock".equalsIgnoreCase(mp.getProvider())) {
            return new MockModelClient(mp.getId());
        }

        // Build the raw provider-specific client.
        ModelClient raw = switch (mp.getProvider().toLowerCase()) {
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

        // Wrap with retry logic unless the client is a mock or unavailable
        // fallback (retrying those would be pointless).
        return wrapWithRetry(raw, mp);
    }

    /**
     * Wraps a client in a {@link RetryableModelClient} unless it is a mock or
     * unavailable sentinel, in which case it is returned unchanged.
     */
    private ModelClient wrapWithRetry(ModelClient client, CouncilProperties.ModelProps mp) {
        if (client instanceof MockModelClient || client instanceof UnavailableModelClient) {
            return client;
        }
        int maxRetries = mp.getRetryMaxAttempts();
        Duration baseDelay = Duration.ofMillis(mp.getRetryBaseDelayMs());
        log.debug("Wrapping model {} with RetryableModelClient (maxRetries={}, baseDelay={}ms)",
                  mp.getId(), maxRetries, baseDelay.toMillis());
        return new RetryableModelClient(client, maxRetries, baseDelay);
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
