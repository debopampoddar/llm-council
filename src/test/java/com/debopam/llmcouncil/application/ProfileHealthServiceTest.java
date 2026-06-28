package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.ProfileHealthResponse;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.model.ProviderHealth;
import com.debopam.llmcouncil.model.ProviderHealthChecker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileHealthServiceTest {

    @Test
    void reportsProfileRunnableWhenRequiredModelsAreHealthy() {
        ModelRegistry registry = new ModelRegistry(
                Map.of(
                        "member", model("member", ModelRole.MEMBER),
                        "chair", model("chair", ModelRole.CHAIR)),
                Map.of());
        CouncilProfile profile = new CouncilProfile(
                "local", "Local", false, DepthMode.QUICK,
                Map.of(DepthMode.QUICK, "policy"));
        CouncilPolicy policy = new CouncilPolicy(
                "policy", "quick", List.of("member"), "chair", null,
                1, 0, false, true);
        ProfileHealthService service = new ProfileHealthService(
                Map.of("local", profile),
                Map.of("policy", policy),
                registry,
                List.of(new AlwaysHealthyChecker()));

        ProfileHealthResponse response = service.health("local", DepthMode.QUICK);

        assertTrue(response.runnable());
        assertEquals("policy", response.policyId());
        assertEquals(2, response.models().size());
    }

    private ModelProfile model(String id, ModelRole role) {
        return new ModelProfile(id, "mock", id, 100, 0.1, Duration.ofSeconds(1), role);
    }

    private static class AlwaysHealthyChecker implements ProviderHealthChecker {
        @Override
        public boolean supports(String provider) {
            return true;
        }

        @Override
        public ProviderHealth check(ModelProfile modelProfile) {
            return ProviderHealth.available(List.of(modelProfile.providerModelId()));
        }
    }
}
