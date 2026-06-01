package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.config.CouncilProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class ModelRegistry {
    private final Map<String, ProviderProfile> providers;
    private final Map<String, ModelProfile> models;
    private final Map<String, CouncilProfile> profiles;
    private final Map<String, CouncilModelClient> clientsByProviderId;

    public ModelRegistry(CouncilProperties properties,
                         @Qualifier("councilModelClients") Map<String, CouncilModelClient> clientsByProviderId) {
        this.providers = loadProviders(properties);
        this.models = loadModels(properties);
        this.profiles = loadProfiles(properties);
        this.clientsByProviderId = Map.copyOf(clientsByProviderId);
    }

    public CouncilProfile profile(String profileId) {
        CouncilProfile profile = profiles.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown council profile " + profileId);
        }
        return profile;
    }

    public ModelProfile model(String modelId) {
        ModelProfile model = models.get(modelId);
        if (model == null) {
            throw new IllegalArgumentException("Unknown model " + modelId);
        }
        return model;
    }

    public ProviderProfile provider(String providerId) {
        ProviderProfile provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown provider " + providerId);
        }
        return provider;
    }

    public CouncilModelClient clientForModel(String modelId) {
        ModelProfile model = model(modelId);
        CouncilModelClient client = clientsByProviderId.get(model.providerId());
        if (client == null) {
            throw new IllegalArgumentException("No model client for provider " + model.providerId());
        }
        return client;
    }

    private Map<String, ProviderProfile> loadProviders(CouncilProperties properties) {
        Map<String, ProviderProfile> loaded = new HashMap<>();
        properties.providers().forEach((id, cfg) -> loaded.put(id, new ProviderProfile(
                id,
                ProviderKind.valueOf(cfg.kind().replace("-", "_").toUpperCase(Locale.ROOT)),
                cfg.baseUrl(),
                cfg.apiKeyEnv(),
                cfg.maxConcurrentRequests() == null ? 1 : cfg.maxConcurrentRequests(),
                cfg.timeout() == null ? java.time.Duration.ofSeconds(120) : cfg.timeout()
        )));
        return Map.copyOf(loaded);
    }

    private Map<String, ModelProfile> loadModels(CouncilProperties properties) {
        Map<String, ModelProfile> loaded = new HashMap<>();
        properties.models().forEach((id, cfg) -> loaded.put(id, new ModelProfile(
                id,
                cfg.providerId(),
                cfg.providerModelId(),
                Boolean.TRUE.equals(cfg.local()),
                Boolean.TRUE.equals(cfg.supportsJsonMode()),
                cfg.defaultOutputTokens() == null ? 1200 : cfg.defaultOutputTokens()
        )));
        return Map.copyOf(loaded);
    }

    private Map<String, CouncilProfile> loadProfiles(CouncilProperties properties) {
        Map<String, CouncilProfile> loaded = new HashMap<>();
        properties.profiles().forEach((id, cfg) -> loaded.put(id, new CouncilProfile(
                id,
                cfg.memberModelIds(),
                cfg.chairModelId(),
                cfg.freshEyesModelId(),
                cfg.protocolId()
        )));
        return Map.copyOf(loaded);
    }
}
