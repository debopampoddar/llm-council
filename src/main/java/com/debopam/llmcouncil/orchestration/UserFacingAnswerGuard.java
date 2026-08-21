package com.debopam.llmcouncil.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separates objective internal identifiers from heuristic narration quality signals. */
final class UserFacingAnswerGuard {

    private static final Pattern INTERNAL_ID = Pattern.compile(
            "(?i)\\b(?:draft|review|score|turn)-[a-z0-9]{6,}\\b");
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

        List<String> invariantFindings = new ArrayList<>();
        Matcher identifiers = INTERNAL_ID.matcher(answer);
        while (identifiers.find() && invariantFindings.size() < 5) {
            invariantFindings.add(identifiers.group());
        }
        List<String> qualityFindings = new ArrayList<>();
        if (!INTERNAL_TASK.matcher(question == null ? "" : question).find()) {
            Matcher narration = INTERNAL_NARRATION.matcher(answer);
            while (narration.find() && qualityFindings.size() < 5) {
                qualityFindings.add(narration.group());
            }
        }
        if (!USER_DRAFT_TASK.matcher(question == null ? "" : question).find()) {
            Matcher narration = INTERNAL_DRAFT_NARRATION.matcher(answer);
            while (narration.find() && qualityFindings.size() < 5) {
                qualityFindings.add(narration.group());
            }
        }

        if (invariantFindings.isEmpty() && qualityFindings.isEmpty()) {
            return Assessment.clear();
        }
        String reason = !invariantFindings.isEmpty()
                ? "User-facing answer exposes reserved internal identifiers: "
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
        String withoutIds = INTERNAL_ID.matcher(text).replaceAll("candidate evidence");
        String withoutNarration = INTERNAL_NARRATION.matcher(withoutIds)
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
