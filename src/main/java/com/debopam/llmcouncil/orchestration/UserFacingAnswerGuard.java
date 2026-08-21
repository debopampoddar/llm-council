package com.debopam.llmcouncil.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects internal council metadata that must not appear in a user-facing answer. */
final class UserFacingAnswerGuard {

    private static final Pattern INTERNAL_ID = Pattern.compile(
            "(?i)\\b(?:draft|review|score|turn)-[a-z0-9]{6,}\\b");
    private static final Pattern INTERNAL_NARRATION = Pattern.compile(
            "(?i)\\b(?:debate history|peer reviews?|winning draft|reviewer\\s+local-[a-z0-9_-]+"
            + "|scores? provided|council (?:mechanics|votes?|member identities))\\b");
    private static final Pattern INTERNAL_TASK = Pattern.compile(
            "(?i)\\b(?:council mechanics|internal council|draft ids?|peer reviews?|debate history"
            + "|model scores?|member identities)\\b");

    private UserFacingAnswerGuard() {
    }

    static Assessment assess(String question, String answer) {
        if (answer == null || answer.isBlank()) {
            return Assessment.clear();
        }

        List<String> findings = new ArrayList<>();
        Matcher identifiers = INTERNAL_ID.matcher(answer);
        while (identifiers.find() && findings.size() < 5) {
            findings.add(identifiers.group());
        }
        if (!INTERNAL_TASK.matcher(question == null ? "" : question).find()) {
            Matcher narration = INTERNAL_NARRATION.matcher(answer);
            while (narration.find() && findings.size() < 5) {
                findings.add(narration.group());
            }
        }

        if (findings.isEmpty()) {
            return Assessment.clear();
        }
        return new Assessment(true, List.copyOf(findings),
                "User-facing answer exposes internal council metadata: " + findings + ".");
    }

    record Assessment(boolean leaked, List<String> findings, String reason) {
        static Assessment clear() {
            return new Assessment(false, List.of(), null);
        }
    }
}
