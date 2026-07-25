package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.application.DefaultEventPublisher;
import com.debopam.llmcouncil.application.RunRegistry;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.*;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that runs a full mock protocol end-to-end through the
 * {@link ProtocolOrchestrator}. Uses mock model clients so no external
 * services are needed.
 *
 * <p>Verifies that the orchestrator:
 * <ul>
 *   <li>Runs all stages in order without exceptions</li>
 *   <li>Produces drafts from the GENERATE stage</li>
 *   <li>Produces a non-terminal context on success</li>
 *   <li>Handles the rigorous protocol path including REVISE and REVIEW_POST_DEBATE</li>
 * </ul>
 */
class ProtocolOrchestratorIntegrationTest {

    @Test
    void quickProtocolRunsEndToEndWithMocks() {
        // Build registry with mock clients
        ModelRegistry registry = buildMockRegistry();
        DefaultEventPublisher events = new DefaultEventPublisher();
        ArtifactStore artifacts = new NoopArtifactStore();
        PromptBuilder promptBuilder = new PromptBuilder();
        StructuredOutputParser parser = new StructuredOutputParser(
                new com.fasterxml.jackson.databind.ObjectMapper());

        // Build executor registry with all stages the quick protocol needs
        StageExecutorRegistry executorRegistry = new StageExecutorRegistry(List.of(
                new GenerationStageExecutor(registry, promptBuilder, events, artifacts),
                new AnonymizeStageExecutor(artifacts),
                new ReviewStageExecutor(registry, promptBuilder, parser, events, artifacts),
                new ScoreStageExecutor(events, artifacts),
                new SynthesisStageExecutor(registry, promptBuilder, events, artifacts)));

        ProtocolDefinition quickProtocol = new ProtocolDefinition("quick", "Quick protocol",
                List.of(StageType.GENERATE, StageType.ANONYMIZE, StageType.REVIEW,
                        StageType.SCORE, StageType.SYNTHESIZE),
                Map.of());

        ProtocolDefinitionRegistry protocolRegistry = new ProtocolDefinitionRegistry();
        protocolRegistry.register(Map.of("quick", quickProtocol));

        ProtocolOrchestrator orchestrator = new ProtocolOrchestrator(
                protocolRegistry, executorRegistry, events, new RunRegistry());

        // Run the protocol
        CouncilSession session = CouncilSession.create("integration-1",
                "What is the meaning of life?", null, DepthMode.QUICK, "mock");
        CouncilProfile profile = TestModels.profile("mock").displayName("Mock").testOnly(true)
                .defaultDepth(DepthMode.QUICK).depth(DepthMode.QUICK, "mock-quick").build();
        CouncilPolicy policy = TestModels.policy("mock-quick").protocol("quick")
                .members("mock-member-1", "mock-member-2").chair("mock-chair").build();

        CouncilContext result = orchestrator.run(session, profile, policy);

        // Verify: should not be terminal (no failures)
        assertFalse(result.isTerminal(),
                    "Quick protocol with mocks should complete successfully. Failure: "
                    + result.failureMessage().orElse("none"));

        // Verify: drafts were produced
        assertFalse(result.drafts().isEmpty(), "Should have produced drafts");

        // Verify: synthesis produced a result
        assertNotNull(result.synthesisResult(), "Should have a synthesis result");
    }

    @Test
    void rigorousProtocolRunsReviseAndPostDebateReview() {
        ModelRegistry registry = buildMockRegistry();
        DefaultEventPublisher events = new DefaultEventPublisher();
        ArtifactStore artifacts = new NoopArtifactStore();
        PromptBuilder promptBuilder = new PromptBuilder();
        StructuredOutputParser parser = new StructuredOutputParser(
                new com.fasterxml.jackson.databind.ObjectMapper());

        // Build all executors including the new REVISE and REVIEW_POST_DEBATE stages
        StageExecutorRegistry executorRegistry = new StageExecutorRegistry(List.of(
                new GenerationStageExecutor(registry, promptBuilder, events, artifacts),
                new AnonymizeStageExecutor(artifacts),
                new ReviewStageExecutor(registry, promptBuilder, parser, events, artifacts),
                new ScoreStageExecutor(events, artifacts),
                new DebateStageExecutor(registry, promptBuilder, events),
                new RevisionStageExecutor(registry, promptBuilder, events, artifacts),
                new ReviewPostDebateStageExecutor(registry, promptBuilder, parser, events, artifacts),
                new SynthesisStageExecutor(registry, promptBuilder, events, artifacts)));

        // Rigorous protocol with REVISE and REVIEW_POST_DEBATE stages
        ProtocolDefinition rigorousProtocol = new ProtocolDefinition("rigorous",
                "Rigorous protocol with revision and post-debate review",
                List.of(StageType.GENERATE, StageType.ANONYMIZE, StageType.REVIEW,
                        StageType.SCORE, StageType.DEBATE, StageType.REVISE,
                        StageType.REVIEW_POST_DEBATE, StageType.SCORE,
                        StageType.SYNTHESIZE),
                Map.of(StageType.DEBATE, new ProtocolStageOptions(
                        Map.of("min-rounds", "1", "max-rounds", "2",
                               "force-run", "true",
                               "ks-convergence-threshold", "0.10",
                               "sycophancy-threshold", "0.70"))));

        ProtocolDefinitionRegistry protocolRegistry = new ProtocolDefinitionRegistry();
        protocolRegistry.register(Map.of("rigorous", rigorousProtocol));

        ProtocolOrchestrator orchestrator = new ProtocolOrchestrator(
                protocolRegistry, executorRegistry, events, new RunRegistry());

        CouncilSession session = CouncilSession.create("integration-2",
                "Should we use microservices or monolith?", null,
                DepthMode.RIGOROUS, "mock");
        CouncilProfile profile = TestModels.profile("mock").displayName("Mock").testOnly(true)
                .defaultDepth(DepthMode.RIGOROUS)
                .depth(DepthMode.RIGOROUS, "mock-rigorous").build();
        CouncilPolicy policy = TestModels.policy("mock-rigorous").protocol("rigorous")
                .members("mock-member-1", "mock-member-2").chair("mock-chair").build();

        CouncilContext result = orchestrator.run(session, profile, policy);

        // Verify: should complete without terminal failure
        assertFalse(result.isTerminal(),
                    "Rigorous protocol with mocks should complete. Failure: "
                    + result.failureMessage().orElse("none"));

        // Verify: debate rounds exist
        assertFalse(result.debateRounds().isEmpty(), "Should have debate rounds");

        // Verify: drafts exist (revised or original)
        assertFalse(result.drafts().isEmpty(), "Should have drafts after revision");

        // Verify: reviews exist
        assertFalse(result.reviews().isEmpty(), "Should have reviews");

        // Verify: synthesis exists
        assertNotNull(result.synthesisResult(), "Should have synthesis result");
    }

    @Test
    void orchestratorHandlesMissingExecutorGracefully() {
        ModelRegistry registry = buildMockRegistry();
        DefaultEventPublisher events = new DefaultEventPublisher();
        ArtifactStore artifacts = new NoopArtifactStore();
        PromptBuilder promptBuilder = new PromptBuilder();

        // Only register GENERATE executor — SCORE/SYNTHESIZE will be missing
        StageExecutorRegistry executorRegistry = new StageExecutorRegistry(List.of(
                new GenerationStageExecutor(registry, promptBuilder, events, artifacts)));

        ProtocolDefinition protocol = new ProtocolDefinition("partial", "Partial",
                List.of(StageType.GENERATE, StageType.SCORE, StageType.SYNTHESIZE),
                Map.of());

        ProtocolDefinitionRegistry protocolRegistry = new ProtocolDefinitionRegistry();
        protocolRegistry.register(Map.of("partial", protocol));

        ProtocolOrchestrator orchestrator = new ProtocolOrchestrator(
                protocolRegistry, executorRegistry, events, new RunRegistry());

        CouncilSession session = CouncilSession.create("integration-3",
                "Test question", null, DepthMode.QUICK, "mock");
        CouncilProfile profile = TestModels.profile("mock").displayName("Mock").testOnly(true)
                .defaultDepth(DepthMode.QUICK).depth(DepthMode.QUICK, "mock-partial").build();
        CouncilPolicy policy = TestModels.policy("mock-partial").protocol("partial")
                .members("mock-member-1").chair("mock-chair").build();

        // Should not throw — missing executors are skipped with a warning
        CouncilContext result = orchestrator.run(session, profile, policy);
        assertNotNull(result, "Should return a context even with missing executors");
    }

    // ── Helpers 

    private ModelRegistry buildMockRegistry() {
        return TestModels.registry(
                List.of(TestModels.model("mock-member-1")
                                  .provider("mock").providerModelId("mock")
                                  .outputTokens(800).temperature(0.1)
                                  .timeout(Duration.ofSeconds(30)).role(ModelRole.MEMBER).councilRole(CouncilRole.PROPOSER).family("mock").build(),
                        TestModels.model("mock-member-2")
                                  .provider("mock").providerModelId("mock")
                                  .outputTokens(800).temperature(0.1)
                                  .timeout(Duration.ofSeconds(30)).role(ModelRole.MEMBER).councilRole(CouncilRole.CRITIC).family("mock").build(),
                        TestModels.model("mock-chair")
                                  .provider("mock").providerModelId("mock")
                                  .outputTokens(800).temperature(0.1)
                                  .timeout(Duration.ofSeconds(30)).role(ModelRole.CHAIR).councilRole(CouncilRole.SYNTHESIZER).family("mock").build()),
                Map.of("mock-member-1", new MockModelClient("mock-member-1"),
                       "mock-member-2", new MockModelClient("mock-member-2"),
                       "mock-chair", new MockModelClient("mock-chair")));
    }

    private static class NoopArtifactStore implements ArtifactStore {
        @Override
        public java.util.Optional<String> readArtifact(String sessionId, String relativePath) {
            return java.util.Optional.empty();
        }

        @Override public void writeText(String sessionId, String relativePath, String text) {}
        @Override public void writeJson(String sessionId, String relativePath, Object value) {}
        @Override public List<String> listArtifacts(String sessionId) { return List.of(); }
        @Override public boolean deleteSession(String sessionId) { return false; }
    }
}
