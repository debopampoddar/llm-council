package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SycophancyDetector}.
 *
 * <p><b>Every detection test here runs at the shipped thresholds</b>
 * ({@link #SHIPPED_SIMILARITY} and {@link #SHIPPED_CONFIDENCE_DELTA}, the values
 * in {@code application.yml}), not at a convenient value. The previous version of
 * this class exercised only {@code 0.10} while the application shipped
 * {@code 0.70} against a product index that could not reach it — so the suite was
 * green, the feature was dead, and nothing connected the two. CLAUDE.md requires
 * a positive control proving a detector can fire; that control has to run at the
 * configuration users actually get.
 */
class SycophancyDetectorTest {

    /** The similarity threshold in application.yml. */
    private static final double SHIPPED_SIMILARITY = 0.70;

    /** The confidence-delta threshold in application.yml, in points. */
    private static final double SHIPPED_CONFIDENCE_DELTA = 15.0;

    private static SycophancyDetector shipped() {
        return new SycophancyDetector(SHIPPED_SIMILARITY, SHIPPED_CONFIDENCE_DELTA);
    }

    // ── Positive controls: the detector fires as configured

    @Test
    @DisplayName("a member that restates itself while swinging to the majority is flagged")
    void detectsCapitulationWithUnchangedArgument() {
        String unchanged = "I believe the answer is X based on evidence A B C D E F G H";
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", unchanged, 30),
                new DebateContribution("model-b", "Answer is Y", 80),
                new DebateContribution("model-c", "Answer is Y", 85)));
        // majority median = 80
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", unchanged, 78),
                new DebateContribution("model-b", "Answer is still Y", 82),
                new DebateContribution("model-c", "Answer is still Y", 84)));

        SycophancyDetector.SycophancyReport report = shipped().analyze(prev, curr);

        assertTrue(report.sycophancyDetected(),
                   "48 points of movement with an unchanged argument is the whole signal");
        assertTrue(scoreFor(report, "model-a").flagged());
        assertFalse(scoreFor(report, "model-b").flagged(), "model-b barely moved");
        assertFalse(scoreFor(report, "model-c").flagged(), "model-c barely moved");
    }

    @Test
    @DisplayName("a member that adopts the others' language is flagged even after rewriting")
    void detectsCapitulationByAdoptingOthersLanguage() {
        // The signal the old confidence-median metric could not see: model-a's
        // text changed completely, so self-similarity is ~0, but what it changed
        // INTO is what model-b and model-c said last round.
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "alpha beta gamma delta epsilon", 30),
                new DebateContribution("model-b", "the caching layer is the bottleneck here", 80),
                new DebateContribution("model-c", "the caching layer is the bottleneck here too", 85)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "the caching layer is the bottleneck here", 78),
                new DebateContribution("model-b", "the caching layer is the bottleneck here", 82),
                new DebateContribution("model-c", "the caching layer is the bottleneck here too", 84)));

        SycophancyDetector.SycophancyReport report = shipped().analyze(prev, curr);
        SycophancyDetector.ModelSycophancyScore a = scoreFor(report, "model-a");

        assertTrue(a.textSimilarity() < SHIPPED_SIMILARITY,
                   "model-a rewrote itself, so self-similarity alone would clear it");
        assertTrue(a.alignmentToOthers() >= SHIPPED_SIMILARITY,
                   "but it rewrote itself into the others' prior words");
        assertTrue(a.flagged());
    }

    // ── Negative controls: each gate condition can independently clear a member

    @Test
    @DisplayName("a genuine position update with new reasoning is not flagged")
    void noSycophancyWhenReasoningActuallyChanged() {
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "I think the answer is definitely X", 30),
                new DebateContribution("model-b", "Y is correct", 80),
                new DebateContribution("model-c", "Y is correct indeed", 85)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a",
                        "After weighing counterarguments I now favour a hybrid "
                        + "combining several distinct mechanisms", 78),
                new DebateContribution("model-b", "Y is correct", 82),
                new DebateContribution("model-c", "Y is correct indeed", 84)));

        assertFalse(shipped().analyze(prev, curr).sycophancyDetected(),
                    "moving after doing the reasoning is what debate is for");
    }

    @Test
    @DisplayName("an unchanged argument with a small confidence move is not flagged")
    void noSycophancyWhenConfidenceBarelyMoved() {
        // The discrimination the multiplied index could not express: identical
        // text is only interesting when the stated position moved with it.
        String unchanged = "same position, same reasoning, same supporting evidence";
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", unchanged, 78),
                new DebateContribution("model-b", "a different position entirely", 80)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", unchanged, 79),
                new DebateContribution("model-b", "a different position entirely", 80)));

        SycophancyDetector.SycophancyReport report = shipped().analyze(prev, curr);

        assertEquals(1.0, scoreFor(report, "model-a").textSimilarity(), 1e-9,
                     "the text really is unchanged");
        assertFalse(report.sycophancyDetected(),
                    "one point of movement is not capitulation");
    }

    @Test
    @DisplayName("moving away from the majority is independence, not capitulation")
    void noSycophancyWhenConfidenceMovesAwayFromMajority() {
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "same text here", 70),
                new DebateContribution("model-b", "other position", 75)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "same text here", 40),
                new DebateContribution("model-b", "other position", 78)));

        assertFalse(shipped().analyze(prev, curr).sycophancyDetected());
    }

    // ── Exclusions

    @Test
    @DisplayName("unreadable confidence produces no score rather than a default one")
    void handlesUnreadableConfidence() {
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "text", -1)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "text", 80)));

        SycophancyDetector.SycophancyReport report = shipped().analyze(prev, curr);
        assertTrue(report.scores().isEmpty());
        assertFalse(report.sycophancyDetected());
    }

    @Test
    @DisplayName("a member present in only one round is skipped")
    void handlesModelOnlyInOneRound() {
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "text", 70)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-b", "different model", 80)));

        assertTrue(shipped().analyze(prev, curr).scores().isEmpty());
    }

    @Test
    @DisplayName("components are reported for members that were not flagged")
    void reportsComponentsForClearedMembers() {
        // "Not flagged" should be readable as "measured, and here is why it
        // cleared", not as silence.
        DebateRound prev = new DebateRound(0, List.of(
                new DebateContribution("model-a", "steady position", 70),
                new DebateContribution("model-b", "another position", 72)));
        DebateRound curr = new DebateRound(1, List.of(
                new DebateContribution("model-a", "steady position", 71),
                new DebateContribution("model-b", "another position", 72)));

        SycophancyDetector.SycophancyReport report = shipped().analyze(prev, curr);
        assertEquals(2, report.scores().size());
        assertTrue(report.scores().stream().noneMatch(SycophancyDetector.ModelSycophancyScore::flagged));
    }

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
        assertEquals(0.5, SycophancyDetector.jaccardSimilarity("the cat sat", "the dog sat"), 1e-9);
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

    // ── Fixtures

    private static SycophancyDetector.ModelSycophancyScore scoreFor(
            SycophancyDetector.SycophancyReport report, String modelId) {
        return report.scores().stream()
                     .filter(s -> s.modelId().equals(modelId))
                     .findFirst()
                     .orElseThrow(() -> new AssertionError("no score for " + modelId));
    }
}
