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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires all council components from configuration at startup.
 *
 * <p>Model profiles and clients are constructed inside the {@link #modelRegistry()}
 * bean method, which produces an immutable {@link ModelRegistry}. Protocol
 * registration, cross-cutting validation, and assembly of the immutable
 * {@link CouncilCatalog} happen in {@link #councilCatalogHolder(ModelRegistry)}.
 *
 * <h3>Provider Auto-Detection</h3>
 * <p>Cloud providers (OpenAI, Anthropic, Gemini) are auto-detected by inspecting
 * their configured API keys or GCP project ID. If the value is a known
 * placeholder (e.g. {@code "unused-development-placeholder"}) or blank, the
 * provider is considered inactive and models using it fall through to
 * {@link UnavailableModelClient}. No explicit "enabled" flags are needed —
 * just set a real API key and the provider activates automatically.
 */
@Configuration
public class CouncilConfig {

    private static final Logger log = LoggerFactory.getLogger(CouncilConfig.class);

    // ── Known placeholder strings that indicate a provider is NOT configured.
    // Spring AI auto-config requires these to boot without errors, but they
    // must never be treated as real credentials.
    private static final Set<String> PLACEHOLDER_KEYS = Set.of(
            "unused-development-placeholder",
            "placeholder",
            "your-api-key-here",
            "changeme",
            "CHANGEME",
            "none",
            "test"
    );

    private final CouncilProperties props;
    private final ProtocolDefinitionRegistry protocolRegistry;
    private final CouncilConfigurationValidator configurationValidator;
    private final String ollamaBaseUrl;
    private final Integer ollamaNumCtx;
    private final Integer ollamaNumThread;
    private final String ollamaKeepAlive;

    // ── API keys / credentials injected from configuration.
    // These are inspected at startup to auto-detect which providers are available.
    private final String openAiApiKey;
    private final String anthropicApiKey;
    private final String geminiProjectId;

    // ── Provider ChatModel beans — injected optionally by Spring AI auto-config.
    // Each starter creates its bean when on the classpath; we only USE the bean
    // if the corresponding credential passes the placeholder check.
    @Autowired(required = false) private OpenAiChatModel openAiChatModel;
    @Autowired(required = false) private AnthropicChatModel anthropicChatModel;
    @Autowired(required = false) private VertexAiGeminiChatModel geminiChatModel;

    public CouncilConfig(CouncilProperties props,
                         ProtocolDefinitionRegistry protocolRegistry,
                         CouncilConfigurationValidator configurationValidator,
                         @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                         @Value("${spring.ai.ollama.chat.options.num-ctx:4096}") Integer ollamaNumCtx,
                         @Value("${spring.ai.ollama.chat.options.num-thread:0}") Integer ollamaNumThread,
                         @Value("${spring.ai.ollama.chat.options.keep_alive:10m}") String ollamaKeepAlive,
                         @Value("${spring.ai.openai.api-key:}") String openAiApiKey,
                         @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
                         @Value("${spring.ai.vertex.ai.gemini.project-id:}") String geminiProjectId) {
        this.props = props;
        this.protocolRegistry = protocolRegistry;
        this.configurationValidator = configurationValidator;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.ollamaNumCtx = ollamaNumCtx;
        this.ollamaNumThread = ollamaNumThread;
        this.ollamaKeepAlive = ollamaKeepAlive;
        this.openAiApiKey = openAiApiKey;
        this.anthropicApiKey = anthropicApiKey;
        this.geminiProjectId = geminiProjectId;
    }

    /**
     * Build the profile map for the catalog.
     *
     * <p>Not a {@code @Bean}: profiles used to be injected by raw generic type
     * ({@code Map<String, CouncilProfile>}), which cannot be swapped and blocks
     * a second map of the same type from existing. They now reach consumers
     * through {@link CouncilCatalog} instead.
     *
     * @return profile id to profile, in declaration order
     */
    private Map<String, CouncilProfile> buildProfiles() {
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

    /**
     * Build the policy map for the catalog.
     *
     * @return policy id to policy, in declaration order
     */
    private Map<String, CouncilPolicy> buildPolicies() {
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
     * Validates configuration, builds the {@link CouncilCatalog}, and returns
     * the holder every consumer reads through.
     *
     * <p>This is a {@code @Bean} rather than a {@code @PostConstruct} method
     * because {@code @PostConstruct} on a {@code @Configuration} class runs when
     * the configuration object itself is initialised — which is <em>before</em>
     * its {@code @Bean} methods are invoked. Declaring {@link ModelRegistry} as
     * a parameter is what guarantees the registry exists before the catalog is
     * assembled, and makes anything injecting the holder transitively depend on
     * a fully-built catalog.
     *
     * <p>Built-in configuration is validated fail-fast: an unknown cross
     * reference throws and the application does not start. That is deliberate —
     * shipped configuration is the control plane and a broken one must not
     * degrade quietly.
     *
     * @param modelRegistry the fully-constructed model registry
     * @return a holder already populated with generation 1 of the catalog
     */
    @Bean
    public CouncilCatalogHolder councilCatalogHolder(ModelRegistry modelRegistry) {
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

        Map<String, CouncilProfile> profiles = buildProfiles();
        Map<String, CouncilPolicy> policies = buildPolicies();
        CouncilCatalog catalog = new CouncilCatalog(
                modelRegistry,
                profiles,
                policies,
                protocols,
                builtInOrigins(profiles.keySet(), policies.keySet(), protocols.keySet()),
                List.of(),
                Instant.now(),
                1L);

        log.info("CouncilConfig: catalog generation 1 built with {} models, {} profiles, "
                 + "{} policies, {} protocols",
                 profilesForValidation.size(), profiles.size(), policies.size(), protocols.size());
        return new CouncilCatalogHolder(catalog);
    }

    /**
     * Stamp every entity in this catalog as {@link ConfigOrigin#BUILT_IN}.
     *
     * <p>Only built-in configuration exists in this phase. The map is populated
     * anyway so the catalog contract does not change when the user overlay is
     * introduced.
     *
     * @param profileIds  configured profile ids
     * @param policyIds   configured policy ids
     * @param protocolIds configured protocol ids
     * @return entity key to origin
     */
    private Map<String, ConfigOrigin> builtInOrigins(Set<String> profileIds,
                                                     Set<String> policyIds,
                                                     Set<String> protocolIds) {
        Map<String, ConfigOrigin> origins = new LinkedHashMap<>();
        props.getModels().forEach(mp ->
                origins.put(CouncilCatalog.key("model", mp.getId()), ConfigOrigin.BUILT_IN));
        profileIds.forEach(id -> origins.put(CouncilCatalog.key("profile", id), ConfigOrigin.BUILT_IN));
        policyIds.forEach(id -> origins.put(CouncilCatalog.key("policy", id), ConfigOrigin.BUILT_IN));
        protocolIds.forEach(id -> origins.put(CouncilCatalog.key("protocol", id), ConfigOrigin.BUILT_IN));
        return origins;
    }

    /**
     * Builds a {@link ModelClient} for the given model configuration.
     *
     * <p>Mock and unavailable clients are returned as-is. All other (real)
     * clients are wrapped in a {@link RetryableModelClient} using the per-model
     * retry configuration from {@link CouncilProperties.ModelProps}.
     *
     * <p>Cloud providers are auto-detected: the provider's API key or GCP
     * project ID is checked against known placeholder values. If it looks
     * real and the ChatModel bean exists, the provider is active.
     */
    private ModelClient buildClient(CouncilProperties.ModelProps mp) {
        // Mock clients never need retry wrapping.
        if ("mock".equalsIgnoreCase(mp.getProvider())) {
            return new MockModelClient(mp.getId());
        }

        // Build the raw provider-specific client.
        // Each cloud provider is auto-detected by inspecting its API key or
        // project ID. If the credential is a placeholder, the model falls
        // through to UnavailableModelClient. Ollama is always available.
        ModelClient raw = switch (mp.getProvider().toLowerCase()) {
            case "openai" -> hasRealCredential(openAiApiKey) && openAiChatModel != null
                             ? new SpringAiModelClient(mp.getId(), ChatClient.create(openAiChatModel))
                             : fallbackClient(mp, "OpenAI not available — provide a real "
                                 + "SPRING_AI_OPENAI_API_KEY (current key is a placeholder or missing).");
            case "anthropic" -> hasRealCredential(anthropicApiKey) && anthropicChatModel != null
                                ? new SpringAiModelClient(mp.getId(), ChatClient.create(anthropicChatModel))
                                : fallbackClient(mp, "Anthropic not available — provide a real "
                                    + "SPRING_AI_ANTHROPIC_API_KEY (current key is a placeholder or missing).");
            case "gemini", "vertex-ai", "google" -> hasRealCredential(geminiProjectId) && geminiChatModel != null
                                ? new SpringAiModelClient(mp.getId(), ChatClient.create(geminiChatModel))
                                : fallbackClient(mp, "Gemini/Vertex AI not available — provide "
                                    + "GOOGLE_CLOUD_PROJECT (for Vertex AI with ADC) or configure "
                                    + "Gemini API key access. Current project ID is blank or missing.");
            case "ollama" -> new OllamaDirectModelClient(mp.getId(), ollamaBaseUrl,
                                                          ollamaNumCtx, ollamaNumThread, ollamaKeepAlive);
            case "oci", "oci-openai", "openai-compatible" -> hasRealCredential(openAiApiKey) && openAiChatModel != null
                             ? new SpringAiModelClient(mp.getId(), ChatClient.create(openAiChatModel))
                             : fallbackClient(mp, "OpenAI-compatible/OCI not available — provide a real "
                                 + "SPRING_AI_OPENAI_API_KEY and SPRING_AI_OPENAI_BASE_URL.");
            default -> fallbackClient(mp, "unsupported provider '" + mp.getProvider() + "'. "
                           + "Supported: openai, anthropic, gemini, ollama, oci, openai-compatible, mock");
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

    /**
     * Checks whether a credential value is a real, usable credential.
     *
     * <p>Returns {@code false} if the value is null, blank, or matches any
     * known placeholder string used in development configuration. This
     * approach auto-detects provider availability without requiring explicit
     * "enabled" flags — just set a real API key and the provider activates.
     *
     * @param credential the API key or project ID to inspect
     * @return true if the value looks like a real credential
     */
    static boolean hasRealCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            return false;
        }
        String trimmed = credential.trim();
        // Reject known placeholder strings (case-insensitive for safety).
        for (String placeholder : PLACEHOLDER_KEYS) {
            if (trimmed.equalsIgnoreCase(placeholder)) {
                return false;
            }
        }
        return true;
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
