package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DebateStageExecutor#parseConfidence(String)}.
 *
 * <p>Every expectation here is derived from the documented contract — free-text
 * confidence normalises to 0–100, ambiguous input is refused — and not from
 * whatever the implementation happened to return. The defect this class exists
 * to prevent was invisible precisely because no test asserted the decimal case:
 * {@code "Confidence: 0.85"} parsed to {@code 0}, and because {@code 0} is a
 * successful parse it bypassed the unreadable sentinel and fed a
 * maximally-unconfident value into the majority median, the convergence check,
 * and the sycophancy index.
 */
class ConfidenceParsingTest {

    // ── The scale table

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "'Confidence: 85',                    85",
            "'Confidence: 0.85',                  85",
            "'confidence: .7',                    70",
            "'Confidence: 92%',                   92",
            "'My confidence is 0.9',              90",
            "'confidence score: 64',              64",
            "'confidence level: 41',              41",
            "'Confidence: 0.5',                   50",
            "'Confidence: 100',                  100",
            "'Confidence: 8.5',                    9",
    })
    @DisplayName("free-text confidence normalises onto the 0-100 scale")
    void normalisesOntoZeroToHundred(String text, int expected) {
        assertEquals(OptionalInt.of(expected), DebateStageExecutor.parseConfidence(text),
                     () -> "wrong normalisation for " + text);
    }

    @Test
    @DisplayName("the decimal scale is not truncated to its leading zero")
    void decimalIsNotTruncatedToLeadingZero() {
        // The exact regression: the old ordered-pattern array matched the "0"
        // of "0.85" first and returned it as a successful parse.
        assertEquals(OptionalInt.of(85), DebateStageExecutor.parseConfidence("Confidence: 0.85"));
        assertEquals(OptionalInt.of(70), DebateStageExecutor.parseConfidence("Confidence: 0.7"));
        assertEquals(OptionalInt.of(90), DebateStageExecutor.parseConfidence("My confidence is 0.9"));
    }

    // ── Refusals

    @ParameterizedTest
    @ValueSource(strings = {
            "Confidence: 1",      // 1% or 100%? unrecoverable
            "Confidence: 0",      // 0% or 0.0 → 0? unrecoverable
    })
    @DisplayName("a bare 0 or 1 is refused rather than guessed")
    void bareZeroOrOneIsAmbiguous(String text) {
        assertTrue(DebateStageExecutor.parseConfidence(text).isEmpty(),
                   () -> text + " is ambiguous across the two scales and must not be guessed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I am fairly sure about this.",
            "no confidence stated anywhere",
            "confidence is high",           // no numeric token
            "Confidence: 150",              // out of range
            "Confidence: 1000",             // out of range
            "",
            "   ",
    })
    @DisplayName("absent, non-numeric, and out-of-range confidence all report nothing")
    void unreadableInputReportsNothing(String text) {
        assertTrue(DebateStageExecutor.parseConfidence(text).isEmpty(),
                   () -> "expected no value for " + text);
    }

    @Test
    @DisplayName("null text reports nothing rather than throwing")
    void nullIsSafe() {
        assertTrue(DebateStageExecutor.parseConfidence(null).isEmpty());
    }

    // ── Positional rules

    @Test
    @DisplayName("the closing declaration wins over earlier prose")
    void lastMatchWins() {
        // The prompts ask models to END with their confidence. Earlier prose
        // that happens to contain the word must not outrank the declaration.
        String text = """
                The cited study reports a confidence level: 3 on its own scale,
                which I do not think transfers to this question.

                Confidence: 78
                """;
        assertEquals(OptionalInt.of(78), DebateStageExecutor.parseConfidence(text));
    }

    @Test
    @DisplayName("an unreadable trailing value falls back to the last readable one")
    void ambiguousTrailingValueDoesNotErasePriorReading() {
        String text = "Confidence: 82 ... on reflection my confidence is 1";
        assertEquals(OptionalInt.of(82), DebateStageExecutor.parseConfidence(text),
                     "an ambiguous later token must not discard a readable earlier one");
    }

    @Test
    @DisplayName("confidence is found inside a full contribution")
    void findsConfidenceInRealisticContribution() {
        String text = """
                I maintain that the caching layer is the bottleneck. The profiler
                output in the previous round supports this: 78% of wall time sits
                in cache misses, not in query planning.

                Confidence: 71
                """;
        assertEquals(OptionalInt.of(71), DebateStageExecutor.parseConfidence(text),
                     "the 78% figure in the body is not a confidence declaration");
    }

    // ── The sentinel contract

    @Test
    @DisplayName("unreadable confidence is counted, not silently dropped")
    void unreadableContributionsAreCounted() {
        DebateRound round = new DebateRound(0, java.util.List.of(
                new DebateContribution("model-a", "argued well", 80),
                new DebateContribution("model-b", "argued well", -1),
                new DebateContribution("model-c", "argued well", -1)));

        assertEquals(2, round.unreadableConfidenceCount());
        assertEquals(1, round.confidenceScores().size(),
                     "unreadable entries stay out of the convergence sample");
    }

    @Test
    @DisplayName("a fully readable round counts zero unreadable")
    void readableRoundCountsZero() {
        // Positive control: the counter must be able to report zero, otherwise
        // the assertion above passes for a detector that always returns 2.
        DebateRound round = new DebateRound(0, java.util.List.of(
                new DebateContribution("model-a", "argued well", 80),
                new DebateContribution("model-b", "argued well", 75)));

        assertEquals(0, round.unreadableConfidenceCount());
        assertEquals(2, round.confidenceScores().size());
    }
}
