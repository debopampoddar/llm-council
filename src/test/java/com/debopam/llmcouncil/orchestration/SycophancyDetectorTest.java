package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SycophancyDetector} — validates sycophancy index calculation,
 * Jaccard similarity, threshold detection, and edge cases.
 */
class SycophancyDetectorTest {

    // ── Jaccard similarity 

    @Test
    void jaccardOfIdenticalTextsIsOne() {
        assertEquals(1.0, SycophancyDetector.jaccardSimilarity("hello world", "hello world"), 1e-9);
    }

    @Test
    void jaccardOfCompletelyDifferentTextsIsZero() {
        assertEquals(0.0, SycophancyDetector.jaccardSimilarity("alpha beta", "gamma delta"), 1e-9);
    }

    @Test
    void jaccardOfPartialOverlap() {
        // words1 = {the, cat, sat}, words2 = {the, dog, sat}
        // intersection = {the, sat} = 2, union = {the, cat, sat, dog} = 4
        double j = SycophancyDetector.jaccardSimilarity("the cat sat", "the dog sat");
        assertEquals(0.5, j, 1e-9, "Jaccard should be 2/4 = 0.5");
    }

    @Test
    void jaccardHandlesNullAndEmpty() {
        assertEquals(0.0, SycophancyDetector.jaccardSimilarity(null, "hello"));
        assertEquals(0.0, SycophancyDetector.jaccardSimilarity("hello", null));
        assertEquals(0.0, SycophancyDetector.jaccardSimilarity("", "hello"));
        assertEquals(0.0, SycophancyDetector.jaccardSimilarity("  ", "hello"));
    }

    @Test
    void jaccardIsCaseInsensitive() {
        assertEquals(1.0, SycophancyDetector.jaccardSimilarity("Hello World", "hello world"), 1e-9);
    }

    // ── Sycophancy detection 

    @Test
    void noSycophancyWhenTextChangesSignificantly() {
        // Model changes both text and confidence — genuinely updating position
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "I think the answer is definitely X", 60),
                new DebateContribution("model-b", "The answer is clearly Y", 80)));

        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "After considering Y I now believe a hybrid approach combining X and Y is best", 75),
                new DebateContribution("model-b", "My position remains Y with additional evidence", 82)));

        SycophancyDetector detector = new SycophancyDetector(0.10);
        SycophancyDetector.SycophancyReport report = detector.analyze(prev, curr);

        assertFalse(report.sycophancyDetected(),
                    "No sycophancy when text changes substantially");
    }

    @Test
    void detectsSycophancyWhenTextSameButConfidenceShiftsTowardMajority() {
        // Model barely changes text but shifts confidence dramatically toward majority
        String sameText = "I believe the answer is X based on evidence A B C D E F G H";
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", sameText, 30),  // far from majority
                new DebateContribution("model-b", "Answer is Y", 80),
                new DebateContribution("model-c", "Answer is Y", 85)));
        // Majority median = 80.0

        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", sameText, 78),  // jumped to near majority with SAME text
                new DebateContribution("model-b", "Answer is still Y", 82),
                new DebateContribution("model-c", "Answer is still Y", 84)));

        SycophancyDetector detector = new SycophancyDetector(0.10);
        SycophancyDetector.SycophancyReport report = detector.analyze(prev, curr);

        // model-a: textSimilarity ≈ 1.0, confidenceDelta toward majority = large
        assertTrue(report.sycophancyDetected(),
                   "Should detect sycophancy when text stays same but confidence shifts to majority");

        // Find model-a's score
        var modelAScore = report.scores().stream()
                .filter(s -> s.modelId().equals("model-a"))
                .findFirst().orElseThrow();
        assertTrue(modelAScore.flagged(), "model-a should be flagged");
        assertTrue(modelAScore.sycophancyIndex() > 0.10, "Index should exceed threshold");
    }

    @Test
    void noSycophancyWhenConfidenceMovesAwayFromMajority() {
        // Model moves AWAY from majority — this is independent thinking, not sycophancy
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "same text here", 70),
                new DebateContribution("model-b", "other position", 75)));
        // Majority median = 72.5

        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "same text here", 40),  // moved AWAY
                new DebateContribution("model-b", "other position", 78)));

        SycophancyDetector detector = new SycophancyDetector(0.10);
        SycophancyDetector.SycophancyReport report = detector.analyze(prev, curr);

        assertFalse(report.sycophancyDetected(),
                    "Moving away from majority should not be flagged as sycophancy");
    }

    @Test
    void handlesUnparseableConfidence() {
        // Confidence = -1 means unparseable — should be excluded from analysis
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "text", -1)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "text", 80)));

        SycophancyDetector detector = new SycophancyDetector(0.10);
        SycophancyDetector.SycophancyReport report = detector.analyze(prev, curr);

        assertTrue(report.scores().isEmpty(),
                   "Models with unparseable confidence should be excluded");
        assertFalse(report.sycophancyDetected());
    }

    @Test
    void handlesModelOnlyInOneRound() {
        // Model present in prev but not curr — should be skipped
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "text", 70)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-b", "different model", 80)));

        SycophancyDetector detector = new SycophancyDetector(0.10);
        SycophancyDetector.SycophancyReport report = detector.analyze(prev, curr);

        assertTrue(report.scores().isEmpty());
        assertFalse(report.sycophancyDetected());
    }
}
