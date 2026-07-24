package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.api.dto.UsageSummary;
import com.debopam.llmcouncil.application.InMemoryEventPublisher;
import com.debopam.llmcouncil.application.RunRegistry;
import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.config.TestCatalogs;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.MockModelClient;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token and cost accounting (F3).
 *
 * <p>Both model clients have always parsed token usage; until this landed,
 * every executor threw it away. The tests that matter here are therefore the
 * structural ones — that no {@code .call(} site is left uninstrumented — rather
 * than the arithmetic, which is one multiplication.
 *
 * <p>Each guard below is falsifiable, and was falsified: deleting the
 * {@code recordUsage} line from any one executor fails
 * {@link #everyStageThatCallsAModelRecordsUsage} naming that stage, and coercing
 * a null token count to zero in {@link UsageRecord} fails
 * {@link #missingTokenCountsMarkTheTotalEstimated}. The positive controls next
 * to the "absent" assertions exist because an assertion that something is not
 * reported proves nothing until the detector has been seen to fire.
 */
class UsageAccountingTest {

    /**
     * Every stage in the protocol that issues a model call.
     *
     * <p>This is the list the eight {@code .call(} sites map onto. A new
     * model-calling stage added without accounting shows up here as a stage the
     * run never billed.
     */
    private static final Set<StageType> MODEL_CALLING_STAGES = new LinkedHashSet<>(List.of(
            StageType.GENERATE, StageType.AGGREGATE, StageType.REVIEW, StageType.DEBATE,
            StageType.REVISE, StageType.REVIEW_POST_DEBATE, StageType.SYNTHESIZE,
            StageType.VALIDATE));

    // ── Instrumentation coverage

    @Test
    void everyStageThatCallsAModelRecordsUsage() {
        CouncilContext result = runFullProtocol(mockRegistry(0.0, 0.0));

        assertFalse(result.isTerminal(),
                    "Protocol should complete with mocks. Failure: "
                    + result.failureMessage().orElse("none"));

        Set<StageType> billed = new LinkedHashSet<>(
                result.usage().stream().map(UsageRecord::stage).toList());

        for (StageType stage : MODEL_CALLING_STAGES) {
            assertTrue(billed.contains(stage),
                       "Stage " + stage + " called a model but recorded no usage, so its tokens "
                       + "are invisible to the run's cost report. Billed stages: " + billed);
        }
    }

    @Test
    void usageAccumulatesAcrossStagesRatherThanBeingOverwritten() {
        CouncilContext result = runFullProtocol(mockRegistry(0.0, 0.0));
        UsageSummary usage = UsageSummary.from(result);

        // MockModelClient reports a fixed 50 in / 100 out per call, so the
        // totals are the call count times those figures. Anything less means
        // records were replaced rather than appended.
        assertEquals(usage.calls() * 50L, usage.promptTokens(),
                     "Prompt tokens should be 50 per call across " + usage.calls() + " calls");
        assertEquals(usage.calls() * 100L, usage.completionTokens(),
                     "Completion tokens should be 100 per call across " + usage.calls() + " calls");
        assertEquals(usage.promptTokens() + usage.completionTokens(), usage.totalTokens());

        assertTrue(usage.calls() > MODEL_CALLING_STAGES.size(),
                   "A two-member council fans out, so a full protocol makes more calls than it "
                   + "has model-calling stages; got " + usage.calls());

        // Per-stage rows must sum to the total, or one of the two is wrong.
        long stageTotal = usage.byStage().stream().mapToLong(UsageSummary.StageUsage::totalTokens).sum();
        long modelTotal = usage.byModel().stream().mapToLong(UsageSummary.ModelUsage::totalTokens).sum();
        assertEquals(usage.totalTokens(), stageTotal, "Per-stage rows must sum to the total");
        assertEquals(usage.totalTokens(), modelTotal, "Per-model rows must sum to the total");
    }

    @Test
    void perStageBreakdownFollowsProtocolOrder() {
        CouncilContext result = runFullProtocol(mockRegistry(0.0, 0.0));
        List<String> stages = UsageSummary.from(result).byStage().stream()
                .map(UsageSummary.StageUsage::stage).toList();

        assertEquals(List.of("GENERATE", "AGGREGATE", "REVIEW", "DEBATE", "REVISE",
                             "REVIEW_POST_DEBATE", "SYNTHESIZE", "VALIDATE"), stages,
                     "Stage rows should follow the protocol so two runs of the same protocol "
                     + "put the same stage in the same place");
    }

    // ── Nullable token contract

    @Test
    void missingTokenCountsMarkTheTotalEstimated() {
        CouncilContext ctx = bareContext();
        ctx.recordUsage("m1", StageType.GENERATE, 100L, 200L, Duration.ofMillis(5));
        ctx.recordUsage("m1", StageType.SYNTHESIZE, null, null, null);

        UsageSummary usage = UsageSummary.from(ctx);

        assertTrue(usage.estimated(),
                   "A provider that reported no usage must mark the total an estimate; "
                   + "otherwise a floor is presented as a measurement");
        // The null contributes zero rather than being dropped or guessed.
        assertEquals(100L, usage.promptTokens());
        assertEquals(200L, usage.completionTokens());
        assertEquals(2, usage.calls(), "The unreported call still happened and still counts");
    }

    @Test
    void aPartiallyReportedCallStillMarksTheTotalEstimated() {
        CouncilContext ctx = bareContext();
        ctx.recordUsage("m1", StageType.GENERATE, 100L, null, null);

        UsageSummary usage = UsageSummary.from(ctx);

        assertTrue(usage.estimated(),
                   "One missing half of a call's usage is still missing usage");
        assertEquals(100L, usage.promptTokens());
        assertEquals(0L, usage.completionTokens());
    }

    @Test
    void fullyReportedUsageIsNotMarkedEstimated() {
        // Positive control for the two tests above: without this, "estimated is
        // true" would pass even if the field were hardcoded true.
        CouncilContext ctx = bareContext();
        ctx.recordUsage("m1", StageType.GENERATE, 100L, 200L, Duration.ofMillis(5));

        assertFalse(UsageSummary.from(ctx).estimated(),
                    "A run where every provider reported usage is a measurement, not an estimate");
    }

    // ── Cost arithmetic

    @Test
    void costIsPricePerThousandTokensTimesTokens() {
        // 1000 prompt tokens at $0.003/1k = $0.003; 2000 completion at $0.015/1k = $0.030.
        CouncilContext ctx = pricedContext(0.003, 0.015);
        ctx.recordUsage("priced", StageType.GENERATE, 1000L, 2000L, null);

        UsageSummary usage = UsageSummary.from(ctx);

        assertNotNull(usage.estimatedCostUsd(), "A priced model must produce a cost");
        assertEquals(0.033, usage.estimatedCostUsd(), 1e-9);
        assertEquals(0.033, usage.byModel().getFirst().estimatedCostUsd(), 1e-9);
        assertEquals(0.033, usage.byStage().getFirst().estimatedCostUsd(), 1e-9);
    }

    @Test
    void costSumsAcrossCallsAndModels() {
        CouncilContext ctx = pricedContext(0.001, 0.002);
        ctx.recordUsage("priced", StageType.GENERATE, 1000L, 1000L, null);
        ctx.recordUsage("priced", StageType.SYNTHESIZE, 1000L, 1000L, null);

        // Each call: 1000/1000*0.001 + 1000/1000*0.002 = 0.003.
        assertEquals(0.006, UsageSummary.from(ctx).estimatedCostUsd(), 1e-9);
    }

    // ── Unpriced is not free

    @Test
    void anUnpricedModelReportsNoCostRatherThanZero() {
        CouncilContext ctx = pricedContext(0.0, 0.0);
        ctx.recordUsage("priced", StageType.GENERATE, 1000L, 2000L, null);

        UsageSummary usage = UsageSummary.from(ctx);

        assertNull(usage.estimatedCostUsd(),
                   "Zero price means the price is unknown, not that the call was free. Reporting "
                   + "0.0 here renders as $0.00 and makes an unpriced cloud model look free.");
        assertNull(usage.byModel().getFirst().estimatedCostUsd(),
                   "The same rule applies to every breakdown row");
        // Tokens are still real even when the price is not known.
        assertEquals(3000L, usage.totalTokens());
        assertFalse(usage.partiallyPriced(),
                    "Nothing was priced, so there is no partial coverage to warn about");
    }

    @Test
    void aMixOfPricedAndUnpricedModelsIsFlaggedAsPartial() {
        ModelRegistry registry = new ModelRegistry(
                Map.of("priced", pricedModel("priced", 0.01, 0.02),
                       "free", pricedModel("free", 0.0, 0.0)),
                Map.of("priced", new MockModelClient("priced"),
                       "free", new MockModelClient("free")));
        CouncilContext ctx = contextWith(registry);
        ctx.recordUsage("priced", StageType.GENERATE, 1000L, 1000L, null);
        ctx.recordUsage("free", StageType.GENERATE, 1000L, 1000L, null);

        UsageSummary usage = UsageSummary.from(ctx);

        assertTrue(usage.partiallyPriced(),
                   "Half the run is unpriced, so the cost covers only half the run and must say so");
        assertEquals(0.03, usage.estimatedCostUsd(), 1e-9,
                     "The cost is what the priced calls cost, not an extrapolation");
        assertNull(usage.byModel().stream()
                        .filter(row -> row.modelId().equals("free")).findFirst().orElseThrow()
                        .estimatedCostUsd(),
                   "The unpriced model's own row still reports no cost");
    }

    @Test
    void aFullyPricedRunIsNotFlaggedAsPartial() {
        // Positive control for the assertion above.
        CouncilContext ctx = pricedContext(0.01, 0.02);
        ctx.recordUsage("priced", StageType.GENERATE, 1000L, 1000L, null);

        assertFalse(UsageSummary.from(ctx).partiallyPriced(),
                    "Every call was priced, so nothing is missing from the cost");
    }

    // ── Response wiring

    @Test
    void theRunResponseCarriesTheUsageSummary() {
        CouncilContext result = runFullProtocol(mockRegistry(0.002, 0.004));
        CouncilRunResponse response = CouncilRunResponse.from("usage-session", result);

        assertNotNull(response.usage(), "A finished run must report what it consumed");
        assertTrue(response.usage().totalTokens() > 0,
                   "The mock reports usage on every call, so the total cannot be zero");
        assertNotNull(response.usage().estimatedCostUsd(),
                      "Every model in this run is priced, so a cost is available");
    }

    @Test
    void aRunThatDiedBeforeProducingAContextReportsNoUsage() {
        CouncilSession session = CouncilSession.create("dead-session", "q", null,
                                                       DepthMode.QUICK, "mock");
        assertNull(CouncilRunResponse.failed(session, "died").usage(),
                   "There is no record of what ran, so there is nothing honest to report — "
                   + "not even zero");
    }

    // ── Fixtures

    /**
     * Run a protocol covering all eight model-calling stages with mock clients.
     *
     * @param registry the model registry, which fixes the prices in play
     * @return the completed context
     */
    private CouncilContext runFullProtocol(ModelRegistry registry) {
        InMemoryEventPublisher events = new InMemoryEventPublisher();
        ArtifactStore artifacts = new NoopArtifactStore();
        PromptBuilder promptBuilder = new PromptBuilder();
        StructuredOutputParser parser = new StructuredOutputParser(new ObjectMapper());

        StageExecutorRegistry executors = new StageExecutorRegistry(List.of(
                new GenerationStageExecutor(registry, promptBuilder, events, artifacts),
                new AggregationStageExecutor(registry, promptBuilder, events),
                new AnonymizeStageExecutor(artifacts),
                new ReviewStageExecutor(registry, promptBuilder, parser, events, artifacts),
                new ScoreStageExecutor(events, artifacts),
                new DebateStageExecutor(registry, promptBuilder, events),
                new RevisionStageExecutor(registry, promptBuilder, events, artifacts),
                new ReviewPostDebateStageExecutor(registry, promptBuilder, parser, events, artifacts),
                new SynthesisStageExecutor(registry, promptBuilder, events, artifacts),
                new ValidateStageExecutor(registry, promptBuilder, parser, events, artifacts)));

        ProtocolDefinition protocol = new ProtocolDefinition("full",
                "Every stage that calls a model",
                List.of(StageType.GENERATE, StageType.AGGREGATE, StageType.ANONYMIZE,
                        StageType.REVIEW, StageType.SCORE, StageType.DEBATE, StageType.REVISE,
                        StageType.REVIEW_POST_DEBATE, StageType.SCORE, StageType.SYNTHESIZE,
                        StageType.VALIDATE),
                Map.of(StageType.DEBATE, new ProtocolStageOptions(
                        Map.of("min-rounds", "1", "max-rounds", "1", "force-run", "true"))));

        ProtocolDefinitionRegistry protocols = new ProtocolDefinitionRegistry();
        protocols.register(Map.of("full", protocol));

        CouncilSession session = CouncilSession.create("usage-session",
                "How should we account for tokens?", null, DepthMode.RIGOROUS, "mock");
        CouncilProfile profile = new CouncilProfile("mock", "Mock", true,
                DepthMode.RIGOROUS, Map.of(DepthMode.RIGOROUS, "usage-policy"));
        CouncilPolicy policy = new CouncilPolicy("usage-policy", "full",
                List.of("mock-member-1", "mock-member-2"), "mock-chair",
                "mock-validator", 1, 0, false, true);

        return new ProtocolOrchestrator(protocols, executors, events, new RunRegistry())
                .run(session, profile, policy, catalogFor(registry));
    }

    private ModelRegistry mockRegistry(double inputCost, double outputCost) {
        Map<String, ModelProfile> models = new LinkedHashMap<>();
        Map<String, ModelClient> clients = new LinkedHashMap<>();
        for (String id : List.of("mock-member-1", "mock-member-2", "mock-chair", "mock-validator")) {
            models.put(id, pricedModel(id, inputCost, outputCost));
            clients.put(id, new MockModelClient(id));
        }
        return new ModelRegistry(models, clients);
    }

    private ModelProfile pricedModel(String id, double inputCost, double outputCost) {
        return new ModelProfile(id, "mock", "mock", 800, 0.1, Duration.ofSeconds(30),
                                ModelRole.MEMBER, CouncilRole.PROPOSER, "mock", 8192,
                                inputCost, outputCost);
    }

    /** A context with one priced model and no run behind it, for arithmetic only. */
    private CouncilContext pricedContext(double inputCost, double outputCost) {
        return contextWith(new ModelRegistry(
                Map.of("priced", pricedModel("priced", inputCost, outputCost)),
                Map.of("priced", new MockModelClient("priced"))));
    }

    /** A context with no catalog binding at all: tokens are known, prices are not. */
    private CouncilContext bareContext() {
        return new CouncilContext(
                CouncilSession.create("s", "q", null, DepthMode.QUICK, "mock"),
                new CouncilProfile("mock", "Mock", true, DepthMode.QUICK, Map.of()),
                new CouncilPolicy("p", "proto", List.of("m1"), "m1", null, 1, 0, false, true),
                new ProtocolDefinition("proto", "proto",
                                       List.of(StageType.GENERATE, StageType.SYNTHESIZE), Map.of()));
    }

    private CouncilContext contextWith(ModelRegistry registry) {
        return new CouncilContext(
                CouncilSession.create("s", "q", null, DepthMode.QUICK, "mock"),
                new CouncilProfile("mock", "Mock", true, DepthMode.QUICK, Map.of()),
                new CouncilPolicy("p", "proto", List.of("priced"), "priced", null, 1, 0, false, true),
                new ProtocolDefinition("proto", "proto",
                                       List.of(StageType.GENERATE, StageType.SYNTHESIZE), Map.of()),
                catalogFor(registry));
    }

    private CouncilCatalog catalogFor(ModelRegistry registry) {
        return TestCatalogs.catalog(registry, Map.of(), Map.of(), Map.of());
    }

    private static class NoopArtifactStore implements ArtifactStore {
        @Override public Optional<String> readArtifact(String sessionId, String relativePath) {
            return Optional.empty();
        }

        @Override public void writeText(String sessionId, String relativePath, String text) {}
        @Override public void writeJson(String sessionId, String relativePath, Object value) {}
        @Override public List<String> listArtifacts(String sessionId) { return List.of(); }
    }
}
