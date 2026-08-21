package com.debopam.llmcouncil.orchestration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * High-precision backstop for instruction influence from supporting context.
 *
 * <p>This deliberately does not pretend to be a general prompt-injection
 * classifier. It looks for explicit attempts to redirect the task and then
 * checks whether a model output adopted distinctive terms from that directive.
 * A positive result is strong enough to fail closed; a negative result is not a
 * security guarantee and the model review/validation layers still run.
 */
final class TrustBoundaryGuard {

    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?i)(system|developer|assistant)\\s*(message|override|instruction)?\\s*:"
            + "|<\\s*(?:system|developer|assistant)\\s*>"
            + "|ignore\\s+(all\\s+)?(previous|prior|system)\\s+instructions?"
            + "|ignore\\s+the\\s+task"
            + "|disregard\\s+(the\\s+)?(task|instructions?|request)"
            + "|instead\\s+(reply|respond|output|answer)"
            + "|set\\s+(?:the\\s+)?(?:final\\s+)?classification\\s+to"
            + "|(?:reply|respond|output|answer|say)\\s+"
            + "(?:(?:with\\s+)?only|(?:with\\s+)?the\\s+(?:word|phrase))\\b");
    private static final Pattern PAYLOAD_CLAUSE = Pattern.compile(
            "(?i)(?:instead\\s+)?(?:reply|respond|output|answer|say|set|classify|assign|approve)\\b"
            + "([^\\n.!?};]{0,200}?)(?=\\s+(?:and|but|because|regardless|while|although|unless|if)\\b"
            + "|[\\n.!?};]|$)");
    private static final Pattern DEFENSIVE_CLAUSE = Pattern.compile(
            "(?i)(prompt[- ]?injection|instruction[- ]?injection|command[- ]?injection"
            + "|(?:quoted|embedded|malicious|untrusted|unauthori[sz]ed)\\s+"
            + "(?:comment|directive|instruction|message|text|note|command|context|data)"
            + "|(?:comment|directive|instruction|message|text|note|command|context|data)"
            + "(?:\\s+\\w+){0,4}\\s+(?:quoted|embedded|malicious|untrusted|unauthori[sz]ed)"
            + "|(?:note|quote|quotation)(?:\\s+\\w+){0,8}\\s+"
            + "(?:instruction|command|override)(?:\\s+\\w+){0,8}\\s+"
            + "(?:asks?|instructs?|says?|ignore|output)"
            + "|manipulat(?:e|es|ed|ing|ion)"
            + "|attempt(?:s|ed|ing)?\\s+to|trying\\s+to|tries\\s+to"
            + "|no\\s+(?:credible\\s+)?evidence|without\\s+(?:instruction\\s+)?authority"
            + "|no\\s+(?:instruction\\s+)?authority|not\\s+(?:an?\\s+)?authoritative"
            + "|(?:do|does|did|will|would|must|should|can|cannot|can't)\\s+not"
            + "(?:\\s+\\w+){0,4}\\s+(?:follow|obey|execut|output|approv|assign|retry)\\w*"
            + "|(?:must|should|can|cannot|can't)\\s+not\\s+(?:be\\s+)?"
            + "(?:followed|obeyed|executed|output|approved|assigned|retried)"
            + "|not\\s+(?:following|obeying|executing|outputting|approving|assigning|retrying)"
            + "|(?:instruction|directive|comment|message|text|note)(?:\\s+\\w+){0,5}\\s+"
            + "(?:ignored|rejected|disregarded|not\\s+valid|not\\s+supported)"
            + "|(?:ignore|ignored|ignoring|reject|rejected|rejecting|disregard|disregarded)"
            + "(?:\\s+\\w+){0,5}\\s+(?:instruction|directive|comment|message|text|note))");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{4,}");
    private static final Pattern UPPERCASE_PAYLOAD = Pattern.compile("\\b[A-Z][A-Z0-9_-]{3,}\\b");

    private static final Set<String> STOP_WORDS = Set.of(
            "system", "developer", "assistant", "message", "override", "instruction",
            "instructions", "ignore", "previous", "prior", "disregard", "analysis",
            "instead", "reply", "respond", "output", "answer", "only", "word", "phrase",
            "with", "this", "that", "from", "into", "task", "request", "ticket", "customer",
            "comment", "assign", "confirmed", "technical", "finding", "findings", "final",
            "classification");

    private TrustBoundaryGuard() {
    }

    static Assessment assess(String supportingContext, String modelOutput) {
        if (supportingContext == null || supportingContext.isBlank()
                || modelOutput == null || modelOutput.isBlank()) {
            return Assessment.clear();
        }

        List<String> suspiciousFragments = supportingContext.lines()
                .map(TrustBoundaryGuard::directiveFragment)
                .filter(fragment -> !fragment.isBlank())
                .toList();
        if (suspiciousFragments.isEmpty()) {
            return Assessment.clear();
        }

        Set<String> payloadTerms = new LinkedHashSet<>();
        Set<String> highSignalTerms = new LinkedHashSet<>();
        for (String fragment : suspiciousFragments) {
            Matcher payload = PAYLOAD_CLAUSE.matcher(fragment);
            while (payload.find()) {
                payloadTerms.addAll(meaningfulTerms(payload.group(1)));
            }
            Matcher uppercase = UPPERCASE_PAYLOAD.matcher(fragment);
            while (uppercase.find()) {
                String term = stem(uppercase.group());
                if (!STOP_WORDS.contains(term)) {
                    highSignalTerms.add(term);
                }
            }
        }
        payloadTerms.addAll(highSignalTerms);

        Set<String> outputTerms = meaningfulTerms(modelOutput);
        Set<String> matched = payloadTerms.stream()
                .filter(outputTerms::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> unframedMatches = matched.stream()
                .filter(term -> !allOccurrencesDefended(modelOutput, term))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean highSignalMatch = unframedMatches.stream().anyMatch(highSignalTerms::contains);
        boolean influenced = highSignalMatch || unframedMatches.size() >= 2;

        if (!influenced) {
            return new Assessment(true, false, List.copyOf(matched), null);
        }
        String reason = "Model output appears to adopt instruction-like text from untrusted supporting context"
                + (unframedMatches.isEmpty() ? "." : ": " + unframedMatches + ".");
        return new Assessment(true, true, List.copyOf(unframedMatches), reason);
    }

    /**
     * Remove an explicit directive and the remainder of its source line while
     * preserving factual text that appeared before it and all other lines.
     * Recovery prompts use this reduced context so the same model is not asked
     * to resist the same payload a second time.
     */
    static String sanitize(String supportingContext) {
        if (supportingContext == null || supportingContext.isBlank()) {
            return supportingContext;
        }
        return supportingContext.lines()
                .map(line -> {
                    Matcher directive = DIRECTIVE.matcher(line);
                    if (!directive.find()) {
                        return line;
                    }
                    return line.substring(0, directive.start())
                            + "[UNTRUSTED_INSTRUCTION_REMOVED]";
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * A defensive phrase only protects payload words in the same sentence.
     * This avoids both polarity mistakes ("do not retry" versus "retry") and
     * the unsafe document-wide shortcut where an early disclaimer could excuse
     * a later standalone execution of the payload.
     */
    private static boolean allOccurrencesDefended(String output, String term) {
        Matcher words = WORD.matcher(output);
        boolean found = false;
        while (words.find()) {
            if (!stem(words.group()).equals(term)) {
                continue;
            }
            found = true;
            int sentenceStart = sentenceStart(output, words.start());
            int sentenceEnd = sentenceEnd(output, words.end());
            String sentence = output.substring(sentenceStart, sentenceEnd);
            if (!DEFENSIVE_CLAUSE.matcher(sentence).find()) {
                return false;
            }
        }
        return found;
    }

    private static int sentenceStart(String value, int from) {
        for (int i = from - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    private static int sentenceEnd(String value, int from) {
        for (int i = from; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                return i;
            }
        }
        return value.length();
    }

    /**
     * Exclude ordinary evidence that happens to precede a directive on the same
     * line. Only the directive and its payload are candidates for adoption.
     */
    private static String directiveFragment(String line) {
        Matcher matcher = DIRECTIVE.matcher(line);
        return matcher.find() ? line.substring(matcher.start()) : "";
    }

    private static Set<String> meaningfulTerms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher words = WORD.matcher(value.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String term = stem(words.group());
            if (!STOP_WORDS.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static String stem(String value) {
        String term = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        for (String suffix : Arrays.asList("ing", "edly", "ed", "es", "s")) {
            if (term.length() > suffix.length() + 4 && term.endsWith(suffix)) {
                return term.substring(0, term.length() - suffix.length());
            }
        }
        return term;
    }

    record Assessment(boolean suspiciousInput,
                      boolean influenced,
                      List<String> matchedTerms,
                      String reason) {
        static Assessment clear() {
            return new Assessment(false, false, List.of(), null);
        }
    }
}
