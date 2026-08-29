package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UserFacingAnswerGuard}.
 *
 * <p><b>Why the two tiers are tested separately.</b> This guard has one caller,
 * {@link SynthesisStageExecutor}, and the tier decides what happens to the run:
 * an {@code invariantViolation} that survives the single recovery attempt calls
 * {@code markFailed}, so the caller pays for every model call in the protocol
 * and receives nothing. A {@code qualityFinding} only warns and retries. A test
 * that asserts {@code leaked()} alone cannot tell those apart — which is how a
 * plain-English phrase came to be a terminal condition unnoticed.
 *
 * <p>Every "must not fire" case below is paired with a positive control for the
 * same mechanism, so a clean result proves the detector looked rather than that
 * it is broken.
 */
class UserFacingAnswerGuardTest {

    // ── Tier 1: invariants. These terminate a run, so they must be unambiguous.

    @Test
    @DisplayName("an internal draft identifier is a terminal invariant violation")
    void internalIdentifierIsInvariant() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "What is the capital of France?",
                "Based on draft-a1b2c3d4 the answer is Paris.");
        assertTrue(a.invariantViolation(), "a leaked draft id must fail the run");
        assertTrue(a.invariantFindings().contains("draft-a1b2c3d4"));
    }

    @Test
    @DisplayName("a machine-only label is a terminal invariant violation")
    void machineLabelIsInvariant() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "What is the capital of France?",
                "UNTRUSTED_MODEL_OUTPUT indicates the answer is Paris.");
        assertTrue(a.invariantViolation());
        assertTrue(a.invariantFindings().contains("UNTRUSTED_MODEL_OUTPUT"));
    }

    @Test
    @DisplayName("distinctive council boilerplate is a terminal invariant violation")
    void reservedBoilerplateIsInvariant() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "Summarize the incident",
                "This is a synthesis of the strongest evidence-backed reasoning available.");
        assertTrue(a.invariantViolation(),
                "a model can only produce this phrase by echoing council scaffolding");
    }

    // ── Tier 2: ambiguous process vocabulary. Warn and retry; never fail.
    //
    // Each of these previously terminated the run. They are ordinary answers to
    // ordinary questions, and the recovery attempt reproduces them because the
    // phrasing is simply the natural one.

    @Test
    @DisplayName("'scores and reviews' in a product answer warns but never fails the run")
    void productReviewAnswerIsNotTerminal() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "How should I surface product ratings on an e-commerce listing page?",
                "Show the average star rating near the title, and put the scores and reviews "
                        + "below the fold so the page stays scannable.");
        assertFalse(a.invariantViolation(), "a benign product answer must not fail the run");
        assertTrue(a.leaked(), "it is still reported, so the cleanup pass still runs");
        assertTrue(a.qualityFindings().contains("scores and reviews"));
    }

    @Test
    @DisplayName("'scores and reviews' in a hiring answer warns but never fails the run")
    void hiringAnswerIsNotTerminal() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "How do we structure a fair hiring loop?",
                "Collect scores and reviews from each interviewer independently before the debrief.");
        assertFalse(a.invariantViolation());
        assertTrue(a.leaked());
    }

    @Test
    @DisplayName("'candidate evidence' in ordinary prose warns but never fails the run")
    void candidateEvidenceInProseIsNotTerminal() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "How do scientists evaluate a new hypothesis?",
                "They weigh the candidate evidence against the null result before publishing.");
        assertFalse(a.invariantViolation());
    }

    // ── Exemptions: the user asked about the thing, so naming it is correct.

    @Test
    @DisplayName("a question about drafts exempts draft narration entirely")
    void draftQuestionIsExempt() {
        assertFalse(UserFacingAnswerGuard.assess(
                        "Summarise what the drafts suggest.",
                        "The drafts suggest three viable options.").leaked(),
                "the user asked about drafts; naming them is the answer");
    }

    @Test
    @DisplayName("a question about internal metadata exempts that metadata")
    void internalMetadataQuestionIsExempt() {
        assertFalse(UserFacingAnswerGuard.assess(
                        "Explain what UNTRUSTED_DATA means in an internal council export",
                        "UNTRUSTED_DATA marks context that has no instruction authority.").leaked(),
                "an explicit request for internal metadata must remain answerable");
    }

    // ── Negative controls, each backed by a positive control above.

    @Test
    @DisplayName("an ordinary answer produces no finding at all")
    void ordinaryAnswerIsClean() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "Explain the authentication failure",
                "The old tokens fail because the signing key rotated.");
        assertFalse(a.leaked());
        assertFalse(a.invariantViolation());
        assertTrue(a.invariantFindings().isEmpty());
        assertTrue(a.qualityFindings().isEmpty());
    }

    @Test
    @DisplayName("a blank or null answer is clear rather than a violation")
    void blankAnswerIsClear() {
        assertFalse(UserFacingAnswerGuard.assess("q", null).leaked());
        assertFalse(UserFacingAnswerGuard.assess("q", "   ").leaked());
    }

    @Test
    @DisplayName("a null question does not throw and does not exempt anything")
    void nullQuestionIsHandled() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                null, "Based on draft-a1b2c3d4 the answer is Paris.");
        assertTrue(a.invariantViolation());
    }

    // ── Known residual: a genuine machine token is still terminal even when the
    // user plausibly wanted it. Documented rather than silently surprising.

    @Test
    @DisplayName("a camelCase machine label stays terminal even in an API-design answer")
    void camelCaseLabelRemainsTerminal() {
        UserFacingAnswerGuard.Assessment a = UserFacingAnswerGuard.assess(
                "What should I name the field that carries extra evidence into my prompt?",
                "Call it supportingContext. It reads clearly next to the question field.");
        assertTrue(a.invariantViolation(),
                "known limitation: machine labels stay invariants, so an API-naming "
                        + "question whose answer happens to use one still fails the run");
    }

    // ── sanitizeForRecovery strips both tiers, regardless of severity.

    @Test
    @DisplayName("recovery sanitisation removes labels and both phrase tiers")
    void sanitizeForRecoveryStripsEveryTier() {
        String cleaned = UserFacingAnswerGuard.sanitizeForRecovery(
                "UNTRUSTED_DATA from eligible drafts and candidate evidence supports rollback, "
                        + "per trust-boundary rules and draft-a1b2c3d4.");
        assertFalse(cleaned.contains("UNTRUSTED_DATA"));
        assertFalse(cleaned.contains("eligible drafts"));
        assertFalse(cleaned.contains("candidate evidence"));
        assertFalse(cleaned.contains("trust-boundary rules"));
        assertFalse(cleaned.contains("draft-a1b2c3d4"));
        assertTrue(cleaned.contains("supports rollback"), "the substance must survive");
    }

    @Test
    @DisplayName("sanitisation of clean text is a positive control for the sanitiser")
    void sanitizeLeavesOrdinaryTextAlone() {
        String original = "The signing key rotated, so old tokens fail.";
        assertEquals(original, UserFacingAnswerGuard.sanitizeForRecovery(original),
                "the sanitiser must not rewrite text that contains nothing reserved");
    }
}
