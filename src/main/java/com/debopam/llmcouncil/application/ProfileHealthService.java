package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.ModelHealthResponse;
import com.debopam.llmcouncil.api.dto.ProfileHealthResponse;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ProviderHealth;
import com.debopam.llmcouncil.model.ProviderHealthChecker;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class ProfileHealthService {

    private final Map<String, CouncilProfile> profiles;
    private final Map<String, CouncilPolicy> policies;
    private final ModelRegistry modelRegistry;
    private final List<ProviderHealthChecker> healthCheckers;

    public ProfileHealthService(Map<String, CouncilProfile> profiles,
                                Map<String, CouncilPolicy> policies,
                                ModelRegistry modelRegistry,
                                List<ProviderHealthChecker> healthCheckers) {
        this.profiles = profiles;
        this.policies = policies;
        this.modelRegistry = modelRegistry;
        this.healthCheckers = healthCheckers;
    }

    public ProfileHealthResponse health(String profileId, DepthMode requestedDepthMode) {
        CouncilProfile profile = profiles.get(profileId);
        if (profile == null) {
            throw new NoSuchElementException("Profile not found: " + profileId);
        }

        DepthMode depthMode = requestedDepthMode == null ? profile.defaultDepthMode() : requestedDepthMode;
        String policyId = profile.policyIdFor(depthMode);
        CouncilPolicy policy = policies.get(policyId);
        if (policy == null) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }

        List<ModelHealthResponse> modelHealth = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String modelId : modelIds(policy)) {
            modelHealth.add(checkModel(modelId, warnings));
        }

        boolean runnable = modelHealth.stream().allMatch(ModelHealthResponse::available);
        return new ProfileHealthResponse(profileId, depthMode, policy.id(), policy.protocolId(),
                                         runnable, modelHealth, warnings);
    }

    private ModelHealthResponse checkModel(String modelId, List<String> warnings) {
        ModelProfile model;
        try {
            model = modelRegistry.model(modelId);
        } catch (NoSuchElementException ex) {
            return new ModelHealthResponse(modelId, null, null, false,
                                           "MODEL_CONFIG_MISSING", ex.getMessage(), List.of());
        }

        ProviderHealthChecker checker = checkerFor(model.provider());
        ProviderHealth health = checker.check(model);
        if ("NOT_CHECKED".equals(health.status())) {
            warnings.add(model.id() + ": " + health.detail());
        }
        return new ModelHealthResponse(model.id(), model.provider(), model.providerModelId(),
                                       health.available(), health.status(), health.detail(),
                                       health.knownProviderModels());
    }

    private ProviderHealthChecker checkerFor(String provider) {
        return healthCheckers.stream()
                .filter(checker -> checker.supports(provider))
                .findFirst()
                .orElse(new UnsupportedProviderHealthChecker(provider));
    }

    private Set<String> modelIds(CouncilPolicy policy) {
        Set<String> modelIds = new LinkedHashSet<>(policy.memberModelIds());
        modelIds.add(policy.chairModelId());
        if (policy.validatorModelId() != null && !policy.validatorModelId().isBlank()) {
            modelIds.add(policy.validatorModelId());
        }
        return modelIds;
    }

    private static class UnsupportedProviderHealthChecker implements ProviderHealthChecker {
        private final String provider;

        private UnsupportedProviderHealthChecker(String provider) {
            this.provider = provider;
        }

        @Override
        public boolean supports(String provider) {
            return true;
        }

        @Override
        public ProviderHealth check(ModelProfile modelProfile) {
            return ProviderHealth.unavailable(
                    "HEALTH_CHECK_UNSUPPORTED",
                    "No provider health checker registered for provider '" + provider + "'");
        }
    }
}
