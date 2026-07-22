package com.debopam.llmcouncil.model;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.List;

/**
 * Health checker for a configured Ollama runtime.
 *
 * <p>It validates both provider reachability and model tag availability through
 * Ollama's lightweight {@code /api/tags} endpoint, delegating the HTTP call to
 * {@link OllamaModelDiscoveryService}.
 *
 * <p>Unlike {@link OllamaModelDiscoveryService#installedModels()}, which
 * deliberately swallows failures, this checker needs to distinguish
 * "unreachable" from "reachable but the model is not installed" — those lead a
 * user to entirely different fixes — so it calls the throwing fetch directly.
 */
@Component
public class OllamaProviderHealthChecker implements ProviderHealthChecker {

    private final OllamaModelDiscoveryService discovery;

    /**
     * @param discovery the shared Ollama discovery service
     */
    @Autowired
    public OllamaProviderHealthChecker(OllamaModelDiscoveryService discovery) {
        this.discovery = discovery;
    }

    /**
     * Convenience constructor that builds its own discovery service.
     *
     * @param baseUrl        Ollama base URL
     * @param timeoutSeconds connect and read timeout for the health call
     */
    public OllamaProviderHealthChecker(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${council.health.provider-timeout-seconds:5}") long timeoutSeconds) {
        this(new OllamaModelDiscoveryService(baseUrl, timeoutSeconds));
    }

    @Override
    public boolean supports(String provider) {
        return "ollama".equalsIgnoreCase(provider);
    }

    @Override
    public ProviderHealth check(ModelProfile modelProfile) {
        try {
            List<String> modelNames = OllamaModelDiscoveryService.parseModelNames(discovery.fetchTags());
            if (!modelNames.contains(modelProfile.providerModelId())) {
                return new ProviderHealth(
                        false,
                        ModelFailureCategory.MODEL_NOT_FOUND.name(),
                        "Ollama is reachable at " + discovery.tagsUri()
                        + " but model '" + modelProfile.providerModelId() + "' is not installed",
                        modelNames);
            }
            return ProviderHealth.available(modelNames);
        } catch (SocketTimeoutException ex) {
            return ProviderHealth.unavailable(ModelFailureCategory.MODEL_TIMEOUT.name(),
                                              "Timed out checking " + discovery.tagsUri() + ": " + ex.getMessage());
        } catch (Exception ex) {
            return ProviderHealth.unavailable(ModelFailureCategory.PROVIDER_UNAVAILABLE.name(),
                                              ex.getClass().getSimpleName() + ": " + nullSafeMessage(ex));
        }
    }

    private String nullSafeMessage(Exception ex) {
        return ex.getMessage() == null ? "" : ex.getMessage();
    }
}
