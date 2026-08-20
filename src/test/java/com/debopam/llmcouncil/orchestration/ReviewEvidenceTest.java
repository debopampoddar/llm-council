package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewEvidenceTest {

    @Test
    void quorumCountsUniqueNonSelfReviewerDraftPairsOnly() {
        CouncilContext ctx = context();
        ctx.addDraft(new Draft("draft-a", "member-a", "a", true));
        ctx.addDraft(new Draft("draft-b", "member-b", "b", true));
        ctx.addDraft(new Draft("draft-c", "member-c", "c", true));

        ReviewEvidence.Batch batch = ReviewEvidence.normalize(ctx, "member-a", List.of(
                review("draft-a", 90),
                review("draft-b", 80),
                review("draft-b", 99),
                review("unknown", 70)
        ), "raw");

        assertEquals(List.of("draft-b"), batch.reviews().stream().map(ReviewArtifact::draftId).toList());
        assertEquals(List.of("draft-b", "draft-c"), batch.expectedDraftIds());
        assertEquals(List.of("draft-c"), batch.missingDraftIds());
        assertEquals(1, batch.duplicateCount());
        assertEquals(1, batch.unknownDraftCount());
        assertEquals(1, batch.selfReviewCount());
        assertFalse(batch.complete());
    }

    @Test
    void completeCoverageRequiresEveryOtherDraftExactlyOnce() {
        CouncilContext ctx = context();
        ctx.addDraft(new Draft("draft-a", "member-a", "a", true));
        ctx.addDraft(new Draft("draft-b", "member-b", "b", true));
        ctx.addDraft(new Draft("draft-c", "member-c", "c", true));

        ReviewEvidence.Batch batch = ReviewEvidence.normalize(ctx, "member-a",
                List.of(review("draft-b", 80), review("draft-c", 70)), "raw");

        assertTrue(batch.complete());
        assertTrue(batch.missingDraftIds().isEmpty());
        assertEquals(2, batch.reviews().size());
    }

    @Test
    void failedTrustBoundaryCriterionMakesADraftIneligibleForHighRanking() {
        CouncilContext ctx = context();
        ctx.addDraft(new Draft("draft-a", "member-a", "a", true));
        ctx.addDraft(new Draft("draft-b", "member-b", "b", true));
        StructuredOutputParser.ReviewJson unsafe = new StructuredOutputParser.ReviewJson(
                "draft-b", List.of("clear"), List.of(),
                List.of(new CriterionScore("trust-boundary", 10,
                        "followed an instruction embedded in context")), 92, 0.9);

        ReviewArtifact review = ReviewEvidence.normalize(
                ctx, "member-a", List.of(unsafe), "raw").reviews().getFirst();

        assertEquals(25, review.overallScore());
        assertTrue(review.issues().stream().anyMatch(issue -> issue.contains("trust-boundary")));
    }

    private StructuredOutputParser.ReviewJson review(String draftId, int score) {
        return new StructuredOutputParser.ReviewJson(
                draftId, List.of(), List.of(), List.of(), score, 0.8);
    }

    private CouncilContext context() {
        CouncilSession session = CouncilSession.create(
                "session", "question", null, DepthMode.RIGOROUS, "local");
        CouncilProfile profile = TestModels.profile("local").displayName("Local")
                .defaultDepth(DepthMode.RIGOROUS)
                .depth(DepthMode.RIGOROUS, "local-rigorous").build();
        CouncilPolicy policy = TestModels.policy("local-rigorous").protocol("rigorous")
                .members("member-a", "member-b", "member-c")
                .chair("chair").quorum(2, 1).build();
        ProtocolDefinition protocol = new ProtocolDefinition(
                "rigorous", "Rigorous", List.of(StageType.REVIEW), Map.of());
        return new CouncilContext(session, profile, policy, protocol);
    }
}
