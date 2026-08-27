package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.api.dto.ProfileHealthResponse;
import com.debopam.llmcouncil.config.TestCatalogs;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.MockModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.model.ProviderHealth;
import com.debopam.llmcouncil.model.ProviderHealthChecker;
import com.debopam.llmcouncil.model.UnavailableModelClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileHealthServiceTest {

    @Test
    void reportsProfileRunnableWhenRequiredModelsAreHealthy() {
        ModelRegistry registry = TestModels.registry(
                List.of(model("member", ModelRole.MEMBER), model("chair", ModelRole.CHAIR)),
                Map.of("member", new MockModelClient("member"),
                       "chair", new MockModelClient("chair")));
        CouncilProfile profile = TestModels.profile("local").displayName("Local")
                .defaultDepth(DepthMode.QUICK).depth(DepthMode.QUICK, "policy").build();
        CouncilPolicy policy = TestModels.policy("policy").protocol("quick").build();
        ProfileHealthService service = new ProfileHealthService(
                TestCatalogs.holder(registry, Map.of("local", profile), Map.of("policy", policy)),
                List.of(new AlwaysHealthyChecker()));

        ProfileHealthResponse response = service.health("local", DepthMode.QUICK);

        assertTrue(response.runnable());
        assertEquals("policy", response.policyId());
        assertEquals(2, response.models().size());
    }

    @Test
    void blocksAProfileBeforeRunWhenARequiredCloudClientWasNotConfigured() {
        ModelProfile local = model("local", ModelRole.MEMBER);
        ModelProfile cloud = TestModels.model("openai-chair").provider("openai")
                .providerModelId("gpt-4.1").outputTokens(100).temperature(0.1)
                .timeout(Duration.ofSeconds(1)).role(ModelRole.CHAIR).build();
        ModelRegistry registry = TestModels.registry(
                List.of(local, cloud),
                Map.of("local", new MockModelClient("local"),
                       "openai-chair", new UnavailableModelClient("openai-chair",
                        "OpenAI not available — provide a real SPRING_AI_OPENAI_API_KEY.")));
        CouncilProfile profile = TestModels.profile("hybrid-openai").displayName("Local + OpenAI")
                .defaultDepth(DepthMode.QUICK).depth(DepthMode.QUICK, "policy").build();
        CouncilPolicy policy = TestModels.policy("policy").protocol("quick")
                .members("local").chair("openai-chair").build();
        ProfileHealthService service = new ProfileHealthService(
                TestCatalogs.holder(registry, Map.of("hybrid-openai", profile), Map.of("policy", policy)),
                List.of(new AlwaysHealthyChecker()));

        ProfileHealthResponse response = service.health("hybrid-openai", DepthMode.QUICK);

        assertTrue(!response.runnable());
        assertEquals("CONFIGURATION_ERROR", response.models().get(1).status());
        assertTrue(response.models().get(1).detail().contains("SPRING_AI_OPENAI_API_KEY"));
    }

    private ModelProfile model(String id, ModelRole role) {
        return TestModels.model(id).provider("mock").providerModelId(id).outputTokens(100)
                         .temperature(0.1).timeout(Duration.ofSeconds(1)).role(role).build();
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
