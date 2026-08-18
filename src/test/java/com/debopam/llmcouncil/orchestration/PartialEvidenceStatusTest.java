package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.application.DefaultEventPublisher;
import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartialEvidenceStatusTest {

    @Test
    void scoringSomeDraftsIsPartialRatherThanSkippedOrClean() {
        CouncilContext ctx = context();
        ctx.addDraft(new Draft("draft-reviewed", "member-a", "a", true));
        ctx.addDraft(new Draft("draft-missing", "member-b", "b", true));
        ctx.addReview(new ReviewArtifact("member-b", "draft-reviewed", List.of(), List.of(),
                List.of(new CriterionScore("accuracy", 80, "ok")), 80, 0.8, "{}"));
        DefaultEventPublisher events = new DefaultEventPublisher();

        new ScoreStageExecutor(events, new NoopArtifacts()).execute(ctx, ProtocolStageOptions.empty());
        ctx.setSynthesisResult("partial answer");

        assertEquals(1, ctx.scores().size());
        assertTrue(ctx.isDegraded());
        assertEquals("PARTIAL", CouncilRunResponse.from("partial-score", ctx).status());

        CouncilEvent completed = events.history("partial-score").stream()
                .filter(event -> "SCORE_COMPLETED".equals(event.type()))
                .findFirst().orElseThrow();
        assertEquals(1, completed.payload().get("scoreCount"));
        assertEquals(2, completed.payload().get("draftCount"));
        assertEquals(1, completed.payload().get("skippedDraftCount"));
        assertTrue(events.history("partial-score").stream()
                .filter(event -> "SCORE_SKIPPED".equals(event.type()))
                .anyMatch(event -> "draft-missing".equals(event.payload().get("draftId"))));
    }

    private CouncilContext context() {
        CouncilSession session = CouncilSession.create(
                "partial-score", "question", null, DepthMode.RIGOROUS, "local");
        CouncilProfile profile = TestModels.profile("local").displayName("Local")
                .defaultDepth(DepthMode.RIGOROUS)
                .depth(DepthMode.RIGOROUS, "local-rigorous").build();
        CouncilPolicy policy = TestModels.policy("local-rigorous").protocol("rigorous")
                .members("member-a", "member-b").chair("chair").quorum(1, 1).build();
        ProtocolDefinition protocol = new ProtocolDefinition(
                "rigorous", "Rigorous", List.of(StageType.SCORE), Map.of());
        return new CouncilContext(session, profile, policy, protocol);
    }

    private static final class NoopArtifacts implements ArtifactStore {
        public void writeText(String sessionId, String relativePath, String text) {}
        public void writeJson(String sessionId, String relativePath, Object value) {}
        public List<String> listArtifacts(String sessionId) { return List.of(); }
        public Optional<String> readArtifact(String sessionId, String relativePath) { return Optional.empty(); }
        public boolean deleteSession(String sessionId) { return false; }
    }
}
