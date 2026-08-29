package com.debopam.llmcouncil.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates objective reserved internal output from heuristic narration quality signals. */
final class UserFacingAnswerGuard {

    private static final Pattern INTERNAL_ID = Pattern.compile(
            "(?i)\\b(?:draft|review|score|turn)-[a-z0-9]{6,}\\b");
    private static final Pattern INTERNAL_ID_REFERENCE = Pattern.compile(
            "(?i)\\b(?:candidate\\s+)?(?:draft|review|score|turn)-[a-z0-9]{6,}\\b");
    private static final List<String> MACHINE_ONLY_LABELS = List.of(
            "USER_TASK", "UNTRUSTED_DATA", "UNTRUSTED_MODEL_OUTPUT",
            "CANDIDATE_EVIDENCE", "QUALITY_OBSERVATION", "ADDITIONAL_EVIDENCE",
            "instructionAuthority", "supportingContext", "peerReviews", "debateHistory");
    /**
     * Distinctive internal boilerplate. A model has no plausible reason to emit
     * these except by echoing council scaffolding, so they stay invariants and
     * a run that still contains one after recovery is rejected.
     */
    private static final List<String> RESERVED_BOILERPLATE = List.of(
            "trust-boundary rules",
            "synthesis of the strongest evidence-backed reasoning");

    /**
     * Process vocabulary that is also ordinary English. "scores and reviews" is
     * as likely to be the correct answer to a question about hiring loops or
     * product pages as it is to be leaked council narration, and "candidate
     * evidence" appears in legal and scientific prose. Treating these as
     * invariants terminated the run, so the caller paid for every model call in
     * the protocol and received nothing. They are cleanup signals instead: they
     * warn, they trigger the bounded recovery attempt, and they are still
     * scrubbed from recovery evidence by {@link #RESERVED_OUTPUT}.
     */
    private static final List<String> AMBIGUOUS_PROCESS_PHRASES = List.of(
            "candidate evidence", "eligible draft", "eligible drafts",
            "scores and reviews", "reviews and scores");

    /** Both tiers, for the sanitiser — which scrubs regardless of severity. */
    private static final List<String> RESERVED_PROCESS_PHRASES =
            java.util.stream.Stream.concat(
                            RESERVED_BOILERPLATE.stream(), AMBIGUOUS_PROCESS_PHRASES.stream())
                    .toList();
    private static final Pattern RESERVED_OUTPUT = Pattern.compile(
            "(?<![\\p{L}\\p{N}_-])(?:"
                    + java.util.stream.Stream.concat(
                                    MACHINE_ONLY_LABELS.stream(), RESERVED_PROCESS_PHRASES.stream())
                            .map(Pattern::quote)
                            .collect(java.util.stream.Collectors.joining("|"))
                    + ")(?![\\p{L}\\p{N}_-])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern INTERNAL_NARRATION = Pattern.compile(
            "(?i)\\b(?:debate history|peer reviews?|winning draft|reviewer\\s+local-[a-z0-9_-]+"
            + "|score summary|scores? provided"
            + "|(?:scores?|reviews?)\\s+and\\s+(?:scores?|reviews?)\\s+provided"
            + "|council (?:mechanics|votes?|members?|member identities))\\b");
    private static final Pattern INTERNAL_DRAFT_NARRATION = Pattern.compile(
            "(?i)\\b(?:(?:some|other|these|the|this|initial|revised|highest-scoring)\\s+drafts?"
            + "|drafts?\\s+(?:suggest|suggests|mention|mentions|provide|provides|support|supports))\\b");
    private static final Pattern USER_DRAFT_TASK = Pattern.compile(
            "(?i)\\bdrafts?\\b");
    private static final Pattern INTERNAL_TASK = Pattern.compile(
            "(?i)\\b(?:council mechanics|internal council|draft ids?|peer reviews?|debate history"
            + "|model scores?|member identities|council members?)\\b");

    private UserFacingAnswerGuard() {
    }

    static Assessment assess(String question, String answer) {
        if (answer == null || answer.isBlank()) {
            return Assessment.clear();
        }

        String userQuestion = question == null ? "" : question;
        List<String> invariantFindings = new ArrayList<>();
        Matcher identifiers = INTERNAL_ID.matcher(answer);
        while (identifiers.find() && invariantFindings.size() < 5) {
            invariantFindings.add(identifiers.group());
        }
        boolean internalTask = INTERNAL_TASK.matcher(userQuestion).find();
        for (String label : MACHINE_ONLY_LABELS) {
            if (invariantFindings.size() >= 5) break;
            if (TrustBoundaryGuard.containsBoundedLiteral(answer, label)
                    && !internalTask
                    && !TrustBoundaryGuard.containsBoundedLiteral(userQuestion, label)) {
                invariantFindings.add(label);
            }
        }
        boolean draftTask = USER_DRAFT_TASK.matcher(userQuestion).find();
        if (!internalTask && !draftTask) {
            for (String phrase : RESERVED_BOILERPLATE) {
                if (invariantFindings.size() >= 5) break;
                if (TrustBoundaryGuard.containsBoundedLiteral(answer, phrase)) {
                    invariantFindings.add(phrase);
                }
            }
        }
        List<String> qualityFindings = new ArrayList<>();
        if (!internalTask && !draftTask) {
            for (String phrase : AMBIGUOUS_PROCESS_PHRASES) {
                if (qualityFindings.size() >= 5) break;
                if (TrustBoundaryGuard.containsBoundedLiteral(answer, phrase)) {
                    qualityFindings.add(phrase);
                }
            }
        }
        if (!internalTask) {
            Matcher narration = INTERNAL_NARRATION.matcher(answer);
            while (narration.find() && qualityFindings.size() < 5) {
                qualityFindings.add(narration.group());
            }
        }
        if (!draftTask) {
            Matcher narration = INTERNAL_DRAFT_NARRATION.matcher(answer);
            while (narration.find() && qualityFindings.size() < 5) {
                qualityFindings.add(narration.group());
            }
        }

        if (invariantFindings.isEmpty() && qualityFindings.isEmpty()) {
            return Assessment.clear();
        }
        String reason = !invariantFindings.isEmpty()
                ? "User-facing answer exposes reserved internal output: "
                        + invariantFindings + "."
                : "User-facing answer contains likely internal council narration: "
                        + qualityFindings + ".";
        return new Assessment(!invariantFindings.isEmpty(),
                List.copyOf(invariantFindings), List.copyOf(qualityFindings), reason);
    }

    /** Remove internal labels before evidence is reused by a recovery prompt. */
    static String sanitizeForRecovery(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String normalized = TrustBoundaryGuard.structuralText(text);
        String withoutIds = INTERNAL_ID_REFERENCE.matcher(normalized)
                .replaceAll("supporting material");
        String withoutReserved = RESERVED_OUTPUT.matcher(withoutIds)
                .replaceAll("supporting material");
        String withoutNarration = INTERNAL_NARRATION.matcher(withoutReserved)
                .replaceAll("supporting analysis");
        return INTERNAL_DRAFT_NARRATION.matcher(withoutNarration)
                .replaceAll("supporting analysis");
    }

    record Assessment(boolean invariantViolation,
                      List<String> invariantFindings,
                      List<String> qualityFindings,
                      String reason) {
        boolean leaked() {
            return invariantViolation || !qualityFindings.isEmpty();
        }

        static Assessment clear() {
            return new Assessment(false, List.of(), List.of(), null);
        }
    }
}
