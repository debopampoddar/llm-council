package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.application.InMemoryEventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.model.UnavailableModelClient;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationStageExecutorTest {

    @Test
    void marksContextFailedWhenDraftQuorumIsNotMet() {
        ModelRegistry registry = new ModelRegistry();
        registry.register(
                Map.of("missing", new ModelProfile("missing", "openai", "missing-model",
                                                    100, 0.2, Duration.ofSeconds(1), ModelRole.MEMBER)),
                Map.of("missing", new UnavailableModelClient("missing", "test unavailable")));

        GenerationStageExecutor executor = new GenerationStageExecutor(
                registry, new PromptBuilder(), new InMemoryEventPublisher(), new NoopArtifactStore());

        CouncilContext context = contextWithPolicy(new CouncilPolicy(
                "test-policy", "quick", List.of("missing"), "missing", null,
                1, 0, false, true));

        executor.execute(context, ProtocolStageOptions.empty());

        assertTrue(context.isTerminal());
        assertTrue(context.failureMessage().orElse("").contains("Draft quorum not met"));
        assertTrue(context.modelFailures().getFirst().category().equals(ModelFailureCategory.CONFIGURATION_ERROR.name()));

        CouncilRunResponse response = CouncilRunResponse.from("session-1", context);

        assertTrue(response.failureCategory().equals(ModelFailureCategory.CONFIGURATION_ERROR.name()));
        assertTrue(response.modelFailures().getFirst().message().contains("unavailable"));
    }

    private CouncilContext contextWithPolicy(CouncilPolicy policy) {
        CouncilSession session = CouncilSession.create("session-1", "question", null,
                                                       DepthMode.QUICK, "mock");
        CouncilProfile profile = new CouncilProfile("mock", "Mock", true, DepthMode.QUICK,
                                                    Map.of(DepthMode.QUICK, policy.id()));
        ProtocolDefinition protocol = new ProtocolDefinition("quick", "quick",
                                                             List.of(StageType.GENERATE), Map.of());
        return new CouncilContext(session, profile, policy, protocol);
    }

    private static class NoopArtifactStore implements ArtifactStore {
        @Override public void writeText(String sessionId, String relativePath, String text) {}
        @Override public void writeJson(String sessionId, String relativePath, Object value) {}
        @Override public List<String> listArtifacts(String sessionId) { return List.of(); }
    }
}
