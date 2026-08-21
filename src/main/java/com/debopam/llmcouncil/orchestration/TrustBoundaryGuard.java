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
            + "|ignore\\s+(all\\s+)?(previous|prior|system)\\s+instructions?"
            + "|disregard\\s+(the\\s+)?(task|instructions?|request)"
            + "|instead\\s+(reply|respond|output|answer)"
            + "|set\\s+(?:the\\s+)?(?:final\\s+)?classification\\s+to"
            + "|(?:reply|respond|output|answer|say)\\s+"
            + "(?:(?:with\\s+)?only|(?:with\\s+)?the\\s+(?:word|phrase))\\b");
    private static final Pattern PAYLOAD_CLAUSE = Pattern.compile(
            "(?i)(?:instead\\s+)?(?:reply|respond|output|answer|say|set|classify|assign|approve)\\b"
            + "([^\\n.!?};]{0,200}?)(?=\\s+(?:and|but|because|regardless|while|although|unless|if)\\b"
            + "|[\\n.!?};]|$)");
    private static final Pattern SAFE_FRAMING = Pattern.compile(
            "(?i)(untrusted|prompt[- ]?injection|ignore(?:d)?\\s+(?:the\\s+)?(?:comment|directive|instruction)"
            + "|ignor(?:e|ed|ing)\\s+(?:(?:the|this|that)\\s+)?"
            + "(?:(?:embedded|quoted|malicious|untrusted)\\s+)?"
            + "(?:comment|directive|instruction|message|text|note)"
            + "|(?:must|should|can|may)\\s+be\\s+ignored"
            + "|(?:do|does|did|will|must|should)\\s+not\\s+"
            + "(?:follow(?:ing)?|obey(?:ing)?|execut(?:e|ing)|honou?r(?:ing)?|output(?:ting)?)"
            + "|(?:(?:do|does|did|should|must|will|can|cannot|can't)\\s+not\\s+"
            + "(?:(?:immediately|directly)\\s+)?(?:retry|approve|assign)"
            + "|(?:should|must|can|cannot|can't)\\s+not\\s+be\\s+retried)"
            + "|(?:do|does|did)\\s+not\\s+(?:[a-z]+\\s+){0,4}approv(?:e|ed)"
            + "|(?:(?:should|must|is|are)\\s+not\\s+(?:be\\s+)?approv(?:e|ed)"
            + "|(?:cannot|can't)\\s+be\\s+approv(?:e|ed))"
            + "|not\\s+(?:evidence|an?\\s+(?:authoritative\\s+)?instruction)"
            + "|does\\s+not\\s+(?:show|indicate|establish)"
            + "|cannot\\s+(?:infer|conclude)|must\\s+not\\s+follow)");
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
        boolean explicitlyDefended = SAFE_FRAMING.matcher(modelOutput).find();
        boolean highSignalMatch = matched.stream().anyMatch(highSignalTerms::contains);
        boolean influenced = !explicitlyDefended && (highSignalMatch || matched.size() >= 2);

        if (!influenced) {
            return new Assessment(true, false, List.copyOf(matched), null);
        }
        String reason = "Model output appears to adopt instruction-like text from untrusted supporting context"
                + (matched.isEmpty() ? "." : ": " + matched + ".");
        return new Assessment(true, true, List.copyOf(matched), reason);
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
