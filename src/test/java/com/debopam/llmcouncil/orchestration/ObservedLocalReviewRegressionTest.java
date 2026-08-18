package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.DefaultEventPublisher;
import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for the three response shapes observed in the first real local rigorous run. */
class ObservedLocalReviewRegressionTest {

    @Test
    void allThreeLocalResponseShapesProduceCompleteMeasurableEvidence() {
        List<Boolean> jsonModes = new ArrayList<>();
        ModelRegistry registry = registry(jsonModes);
        CouncilContext ctx = context();
        ctx.addDraft(new Draft("draft-A", "local-llama3", "llama draft", true));
        ctx.addDraft(new Draft("draft-B", "local-mistral", "mistral draft", true));
        ctx.addDraft(new Draft("draft-C", "local-qwen", "qwen draft", true));

        DefaultEventPublisher events = new DefaultEventPublisher();
        ReviewStageExecutor reviews = new ReviewStageExecutor(
                registry, new PromptBuilder(), new StructuredOutputParser(new ObjectMapper()),
                events, new NoopArtifacts());

        reviews.execute(ctx, ProtocolStageOptions.empty());

        assertEquals(List.of(true, true, true), jsonModes,
                "Every JSON-producing review call must enable provider JSON mode");
        assertEquals(6, ctx.reviews().size(), "three members must each review the other two drafts");
        assertEquals(Map.of("draft-A", 2L, "draft-B", 2L, "draft-C", 2L),
                ctx.reviews().stream().collect(java.util.stream.Collectors.groupingBy(
                        ReviewArtifact::draftId, java.util.stream.Collectors.counting())));
        assertFalse(ctx.isDegraded(), "fully recovered evidence must remain a clean run");
        assertTrue(ctx.excludedModels().isEmpty());

        new ScoreStageExecutor(events, new NoopArtifacts()).execute(ctx, ProtocolStageOptions.empty());

        assertEquals(3, ctx.scores().size());
        assertTrue(ctx.scoreSummary().orElseThrow().disagreementMeasurable());
        assertTrue(ctx.scoreSummary().orElseThrow().reviewerDisagreement() > 40,
                "the observed scores should trigger the shipped rigorous debate threshold");
    }

    @Test
    void aSuccessfulCallWithMissingNonSelfReviewsMarksTheRunDegraded() {
        ModelProfile model = TestModels.model("member-a").build();
        ModelClient client = request -> new ModelCallResult("""
                {"reviews":[{"draftId":"draft-a","criteria":[],"overallScore":80,"confidence":0.8}]}
                """);
        ModelRegistry registry = TestModels.registry(List.of(model), Map.of("member-a", client));
        CouncilSession session = CouncilSession.create(
                "incomplete-review", "question", null, DepthMode.RIGOROUS, "local");
        CouncilProfile profile = TestModels.profile("local").displayName("Local")
                .defaultDepth(DepthMode.RIGOROUS)
                .depth(DepthMode.RIGOROUS, "policy").build();
        CouncilPolicy policy = TestModels.policy("policy").protocol("rigorous")
                .members("member-a").chair("member-a").quorum(1, 0).build();
        CouncilContext ctx = new CouncilContext(session, profile, policy,
                new ProtocolDefinition("rigorous", "Rigorous", List.of(StageType.REVIEW), Map.of()));
        ctx.addDraft(new Draft("draft-a", "member-a", "own", true));
        ctx.addDraft(new Draft("draft-b", "member-b", "other", true));
        DefaultEventPublisher events = new DefaultEventPublisher();

        new ReviewStageExecutor(registry, new PromptBuilder(),
                new StructuredOutputParser(new ObjectMapper()), events, new NoopArtifacts())
                .execute(ctx, ProtocolStageOptions.empty());

        assertTrue(ctx.isDegraded());
        assertTrue(ctx.degradationMessage().orElseThrow().contains("draft-b"));
        assertTrue(events.history("incomplete-review").stream()
                .anyMatch(event -> "REVIEW_INCOMPLETE".equals(event.type())
                        && List.of("draft-b").equals(event.payload().get("missingDraftIds"))));
    }

    private ModelRegistry registry(List<Boolean> jsonModes) {
        List<ModelProfile> models = List.of(
                TestModels.model("local-llama3").family("llama").build(),
                TestModels.model("local-mistral").family("mistral").build(),
                TestModels.model("local-qwen").family("qwen").build());
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("local-llama3", LLAMA_MULTIPLE_ENVELOPES);
        outputs.put("local-mistral", MISTRAL_COMPACT_CRITERIA);
        outputs.put("local-qwen", QWEN_STANDARD_ENVELOPE);
        Map<String, ModelClient> clients = new LinkedHashMap<>();
        outputs.forEach((id, output) -> clients.put(id, request -> {
            jsonModes.add(request.jsonMode());
            return new ModelCallResult(output);
        }));
        return TestModels.registry(models, clients);
    }

    private CouncilContext context() {
        CouncilSession session = CouncilSession.create(
                "observed-local", "question", null, DepthMode.RIGOROUS, "local");
        CouncilProfile profile = TestModels.profile("local").displayName("Local")
                .defaultDepth(DepthMode.RIGOROUS)
                .depth(DepthMode.RIGOROUS, "local-rigorous").build();
        CouncilPolicy policy = TestModels.policy("local-rigorous").protocol("rigorous")
                .members("local-llama3", "local-mistral", "local-qwen")
                .chair("local-llama3").quorum(2, 1).build();
        ProtocolDefinition protocol = new ProtocolDefinition(
                "rigorous", "Rigorous", List.of(StageType.REVIEW, StageType.SCORE), Map.of());
        return new CouncilContext(session, profile, policy, protocol);
    }

    private static final String LLAMA_MULTIPLE_ENVELOPES = """
            Here are the reviews for each draft:
            **Draft A**
            {"reviews":[{"draftId":"draft-A","criteria":[],"overallScore":75,"confidence":0.8}]}
            **Draft B**
            {"reviews":[{"draftId":"draft-B","criteria":[],"overallScore":90,"confidence":0.8}]}
            **Draft C**
            {"reviews":[{"draftId":"draft-C","criteria":[],"overallScore":85,"confidence":0.85}]}
            """;

    private static final String MISTRAL_COMPACT_CRITERIA = """
            {"reviews":[
              {"draftId":"draft-A","criteria":{"accuracy":60,"clarity":70},"overallScore":65,"confidence":0.8},
              {"draftId":"draft-B","criteria":{"accuracy":90,"clarity":80},"overallScore":85,"confidence":0.8},
              {"draftId":"draft-C","criteria":{"accuracy":90,"clarity":85},"overallScore":88,"confidence":0.85}
            ]}
            """;

    private static final String QWEN_STANDARD_ENVELOPE = """
            ```json
            {"reviews":[
              {"draftId":"draft-A","criteria":[{"name":"accuracy","score":85,"rationale":"good"}],"overallScore":82,"confidence":0.8},
              {"draftId":"draft-B","criteria":[{"name":"accuracy","score":90,"rationale":"good"}],"overallScore":89,"confidence":0.9},
              {"draftId":"draft-C","criteria":[{"name":"accuracy","score":88,"rationale":"good"}],"overallScore":86,"confidence":0.85}
            ]}
            ```
            """;

    private static final class NoopArtifacts implements ArtifactStore {
        public void writeText(String sessionId, String relativePath, String text) {}
        public void writeJson(String sessionId, String relativePath, Object value) {}
        public List<String> listArtifacts(String sessionId) { return List.of(); }
        public Optional<String> readArtifact(String sessionId, String relativePath) { return Optional.empty(); }
        public boolean deleteSession(String sessionId) { return false; }
    }
}
