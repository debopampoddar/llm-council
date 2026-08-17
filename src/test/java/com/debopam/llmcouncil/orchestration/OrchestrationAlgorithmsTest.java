package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.DefaultEventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class OrchestrationAlgorithmsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "'Confidence: 85',85",
            "'confidence is 92%',92",
            "'Confidence: 0.85',85",
            "'Confidence level: .7',70",
            "'earlier confidence: 20 final Confidence: 77',77",
            "'Confidence: 8.5',9",
            "'Confidence score: 100',100"
    })
    void confidenceParsingNormalisesSupportedShapes(String text, int expected) {
        assertEquals(expected, DebateStageExecutor.parseConfidence(text).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no confidence", "Confidence: 1", "Confidence: 0", "Confidence: 101", "Confidence: -4"})
    void confidenceParsingRejectsAbsentAmbiguousAndOutOfRangeValues(String text) {
        assertTrue(DebateStageExecutor.parseConfidence(text).isEmpty());
    }

    @Test
    void structuredParserAcceptsFencesCommentsAndTrailingCommas() {
        StructuredOutputParser parser = new StructuredOutputParser(new ObjectMapper());
        String output = """
                preface
                ```json
                {"reviews":[{
                  "draftId":"draft-a", // generated id
                  "strengths":[],"issues":[],"criteria":[],
                  "overallScore":80,"confidence":0.7,
                }]}
                ```
                trailing words
                """;

        var envelope = parser.parseReviews(output);
        assertEquals(1, envelope.reviews().size());
        assertEquals(80, envelope.reviews().getFirst().overallScore());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"reviews\":[]}",
            "{\"reviews\":[{\"draftId\":\"x\",\"overallScore\":101,\"confidence\":0.5}]}",
            "{\"reviews\":[{\"draftId\":\"x\",\"overallScore\":50,\"confidence\":-0.1}]}"
    })
    void structuredParserRejectsMissingOrInvalidReviewEvidence(String output) {
        StructuredOutputParser parser = new StructuredOutputParser(new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> parser.parseReviews(output));
    }

    @Test
    void scoringStrategiesProduceExpectedRobustAggregates() {
        List<ReviewArtifact> reviews = List.of(review("a", 10, 0.1), review("b", 80, 0.8), review("c", 100, 0.1));

        assertEquals(63.333, new AverageScoringStrategy().aggregateOverallScore(reviews), 0.001);
        assertEquals(80.0, new MedianScoringStrategy().aggregateOverallScore(reviews), 0.001);
        assertEquals(80.0, new TrimmedMeanScoringStrategy().aggregateOverallScore(reviews), 0.001);
        assertEquals(75.0, new ConfidenceWeightedScoringStrategy().aggregateOverallScore(reviews), 0.001);
    }

    @Test
    void confidenceWeightedScoringFallsBackWhenEveryConfidenceIsZero() {
        List<ReviewArtifact> reviews = List.of(review("a", 20, 0), review("b", 80, 0));
        assertEquals(50.0, new ConfidenceWeightedScoringStrategy().aggregateOverallScore(reviews));
    }

    @Test
    void secondScorePassUsesOnlyPostDebateReviews() {
        CouncilContext ctx = context();
        ctx.addDraft(new Draft("draft-a", "member-a", "answer", true));
        ctx.addReview(reviewForDraft("initial", "draft-a", 10, 1.0));
        ScoreStageExecutor scorer = new ScoreStageExecutor(new DefaultEventPublisher(), new NoopArtifacts());

        scorer.execute(ctx, ProtocolStageOptions.empty());
        ctx.addPostDebateReview(reviewForDraft("later", "draft-a", 90, 1.0));
        scorer.execute(ctx, ProtocolStageOptions.empty());

        assertEquals(10.0, ctx.scores().getFirst().weightedTotal());
        assertEquals(90.0, ctx.scores().getLast().weightedTotal(),
                "old reviews must not dilute the post-debate result");
        assertEquals(List.of("initial", "post-debate"),
                ctx.scores().stream().map(ScoreArtifact::label).toList());
    }

    @Test
    void postDebateDisagreementCanHaltTheProtocol() {
        CouncilContext ctx = context();
        ctx.addDraft(new Draft("draft-a", "member-a", "answer", true));
        ctx.addReview(reviewForDraft("a", "draft-a", 50, 1));
        ScoreStageExecutor scorer = new ScoreStageExecutor(new DefaultEventPublisher(), new NoopArtifacts());
        scorer.execute(ctx, ProtocolStageOptions.empty());

        ctx.addPostDebateReview(reviewForDraft("a", "draft-a", 0, 1));
        ctx.addPostDebateReview(reviewForDraft("b", "draft-a", 100, 1));
        scorer.execute(ctx, new ProtocolStageOptions(Map.of(
                "escalation-variance-threshold", 40,
                "escalation-policy", "HALT_AND_ESCALATE")));

        assertTrue(ctx.scoreSummary().orElseThrow().escalated());
        assertTrue(ctx.isTerminal());
        assertEquals(StageType.SCORE, ctx.failedStage());
    }

    @Test
    void convergenceRequiresPairedEvidenceAndUsesPerMemberMovementForSmallCouncils() {
        DebateConvergenceDetector detector = new DebateConvergenceDetector(0.1, 5);
        DebateRound before = round(0, contribution("a", 50), contribution("b", 70));
        DebateRound stable = round(1, contribution("a", 54), contribution("b", 68));
        DebateRound moving = round(1, contribution("a", 60), contribution("b", 68));
        DebateRound unrelated = round(1, contribution("c", 50));

        assertTrue(detector.hasConverged(before, stable));
        assertFalse(detector.hasConverged(before, moving));
        assertFalse(detector.hasConverged(before, unrelated));
    }

    @Test
    void ksStatisticHandlesTiesAndSeparatedSamples() {
        DebateConvergenceDetector detector = new DebateConvergenceDetector(0.1);
        assertEquals(0.0, detector.ksStat(List.of(1d, 1d, 2d), List.of(1d, 1d, 2d)));
        assertEquals(1.0, detector.ksStat(List.of(1d, 2d), List.of(10d, 11d)));
    }

    @Test
    void sycophancyNeedsBothMovementAndUnchangedOrCopiedReasoning() {
        SycophancyDetector detector = new SycophancyDetector(0.5, 15);
        DebateRound before = round(0,
                new DebateContribution("a", "keep the original evidence and argument", 20),
                new DebateContribution("b", "majority position with shared evidence", 80),
                new DebateContribution("c", "majority position with shared evidence", 80));
        DebateRound after = round(1,
                new DebateContribution("a", "keep the original evidence and argument", 70),
                new DebateContribution("b", "majority position with shared evidence", 80),
                new DebateContribution("c", "majority position with shared evidence", 80));

        var report = detector.analyze(before, after);
        assertTrue(report.sycophancyDetected());
        assertTrue(report.scores().stream().filter(score -> score.modelId().equals("a")).findFirst().orElseThrow().flagged());
    }

    @Test
    void promptBudgetNeverTruncatesReservedMaterial() {
        ModelProfile model = new ModelProfile("small", "mock", "small", 10, 0.1,
                Duration.ofSeconds(1), ModelRole.MEMBER, CouncilRole.PROPOSER, "mock", 40);
        PromptBudget budget = PromptBudget.forModel(model);
        Map<String, List<String>> fitted = budget.fit(1_000, Map.of("drafts", List.of("x".repeat(500))));
        assertNotNull(fitted.get("drafts"));
        assertTrue(budget.truncated());
    }

    private CouncilContext context() {
        CouncilSession session = CouncilSession.create("session", "question", null, DepthMode.RIGOROUS, "profile");
        CouncilProfile profile = new CouncilProfile("profile", "Profile", false, DepthMode.RIGOROUS,
                Map.of(DepthMode.RIGOROUS, "policy"));
        CouncilPolicy policy = new CouncilPolicy("policy", "protocol", List.of("member-a", "member-b"),
                "chair", "validator", 1, 1, true, true);
        ProtocolDefinition protocol = new ProtocolDefinition("protocol", "test", List.of(StageType.SCORE), Map.of());
        return new CouncilContext(session, profile, policy, protocol);
    }

    private ReviewArtifact review(String reviewer, int score, double confidence) {
        return reviewForDraft(reviewer, "draft", score, confidence);
    }

    private ReviewArtifact reviewForDraft(String reviewer, String draft, int score, double confidence) {
        return new ReviewArtifact(reviewer, draft, List.of(), List.of(),
                List.of(new CriterionScore("accuracy", score, "test")), score, confidence, "{}");
    }

    private DebateContribution contribution(String id, int confidence) {
        return new DebateContribution(id, "position " + id, confidence);
    }

    private DebateRound round(int number, DebateContribution... contributions) {
        return new DebateRound(number, List.of(contributions));
    }

    private static final class NoopArtifacts implements ArtifactStore {
        public void writeText(String sessionId, String relativePath, String text) {}
        public void writeJson(String sessionId, String relativePath, Object value) {}
        public List<String> listArtifacts(String sessionId) { return List.of(); }
        public Optional<String> readArtifact(String sessionId, String relativePath) { return Optional.empty(); }
        public boolean deleteSession(String sessionId) { return false; }
    }
}
