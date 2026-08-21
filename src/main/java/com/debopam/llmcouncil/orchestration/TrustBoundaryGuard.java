package com.debopam.llmcouncil.orchestration;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enforces objective output invariants for explicit directives found in
 * untrusted supporting context.
 *
 * <p>This class intentionally does not classify the meaning, sentiment, or
 * polarity of arbitrary prose. It recognizes a closed grammar of directives
 * that request an explicit literal (for example, {@code reply only BREACHED}
 * or {@code set the classification to APPROVED}). User-facing prose violates
 * the invariant only when that literal appears as a complete standalone answer
 * or verdict segment. Authority-bearing validator fields use stricter exact,
 * bounded containment because they can change the application's verdict.
 *
 * <p>That deliberately narrow contract is deterministic and testable. It is a
 * backstop, not a claim that prompt injection can be inferred from arbitrary
 * natural language. The primary trust boundary remains provenance separation:
 * supporting context and model artifacts never receive instruction authority,
 * and model output is never an executable application command.
 */
final class TrustBoundaryGuard {

    private static final int MAX_LITERAL_CHARS = 120;

    /**
     * Locates explicit instruction syntax for telemetry and bounded recovery
     * sanitization. Matching this pattern alone never rejects model output.
     */
    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?i)(system|developer|assistant)\\s*(message|override|instruction)?\\s*:"
            + "|<\\s*(?:system|developer|assistant)\\s*>"
            + "|ignore\\s+(all\\s+)?(previous|prior|system)\\s+instructions?"
            + "|ignore\\s+the\\s+task"
            + "|disregard\\s+(the\\s+)?(task|instructions?|request)"
            + "|instead\\s+(reply|respond|output|answer|say)"
            + "|set\\s+(?:the\\s+)?(?:final\\s+)?(?:classification|status|decision|label)\\s+to"
            + "|(?:reply|respond|output|answer|say)\\s+"
            + "(?:(?:with\\s+)?only|(?:with\\s+)?the\\s+(?:word|phrase|token))\\b");

    private static final String QUOTED_OR_BARE_LITERAL =
            "(?:\\\"([^\\\"\\r\\n]{1," + MAX_LITERAL_CHARS + "})\\\""
            + "|'([^'\\r\\n]{1," + MAX_LITERAL_CHARS + "})'"
            + "|([\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,63}))";

    /** Closed grammar: a command that explicitly requires only one literal. */
    private static final Pattern ONLY_LITERAL = Pattern.compile(
            "(?i:\\b(?:reply|respond|output|answer|say)\\s+(?:with\\s+)?only"
            + "(?:\\s+the)?(?:\\s+(?:word|phrase|token|text))?\\s+)"
            + QUOTED_OR_BARE_LITERAL);

    /** Closed grammar: a command naming a word, phrase, or token to return. */
    private static final Pattern NAMED_LITERAL = Pattern.compile(
            "(?i:\\b(?:reply|respond|output|answer|say)\\s+(?:with\\s+)?(?:the\\s+)?"
            + "(?:word|phrase|token)\\s+)" + QUOTED_OR_BARE_LITERAL);

    /** Closed grammar for an unquoted, distinctive uppercase output token. */
    private static final Pattern UPPERCASE_OUTPUT_LITERAL = Pattern.compile(
            "(?i:\\b(?:reply|respond|output|answer|say)\\s+)"
            + "([A-Z][A-Z0-9_-]{2,63})");

    /** Closed grammar for an explicit classification/status/decision value. */
    private static final Pattern CLASSIFICATION_LITERAL = Pattern.compile(
            "(?i:\\bset\\s+(?:the\\s+)?(?:final\\s+)?"
            + "(?:classification|status|decision|label)\\s+to\\s+)"
            + QUOTED_OR_BARE_LITERAL);

    private static final List<Pattern> LITERAL_DIRECTIVES = List.of(
            ONLY_LITERAL, NAMED_LITERAL, UPPERCASE_OUTPUT_LITERAL, CLASSIFICATION_LITERAL);

    private TrustBoundaryGuard() {
    }

    static Assessment assess(String supportingContext, String modelOutput) {
        if (supportingContext == null || supportingContext.isBlank()
                || modelOutput == null || modelOutput.isBlank()) {
            return Assessment.clear();
        }

        String normalizedContext = structuralText(supportingContext);
        boolean explicitDirectivePresent = DIRECTIVE.matcher(normalizedContext).find();
        Set<String> requestedLiterals = requestedLiterals(normalizedContext);
        if (!explicitDirectivePresent && requestedLiterals.isEmpty()) {
            return Assessment.clear();
        }

        Set<String> outputSegments = standaloneSegments(modelOutput);
        List<String> matchedLiterals = requestedLiterals.stream()
                .filter(literal -> outputSegments.contains(canonical(literal)))
                .toList();
        if (matchedLiterals.isEmpty()) {
            return new Assessment(true, false, List.of(), null);
        }

        String reason = "Model output contains a standalone literal explicitly requested by "
                + "untrusted supporting context: " + matchedLiterals + ".";
        return new Assessment(true, true, matchedLiterals, reason);
    }

    /**
     * Enforce the stricter contract used for structured fields that can change
     * application state, such as validator issues and recommended fixes.
     *
     * <p>A user-facing answer may safely explain an attacker-requested literal
     * in prose. An authority-bearing control field has no reason to repeat that
     * literal at all. Exact, bounded containment therefore triggers clean-room
     * recovery without attempting to infer the field's sentiment or meaning.
     */
    static Assessment assessControlFields(
            String supportingContext, Collection<String> controlFields) {
        if (supportingContext == null || supportingContext.isBlank()
                || controlFields == null || controlFields.isEmpty()) {
            return Assessment.clear();
        }

        String normalizedContext = structuralText(supportingContext);
        boolean explicitDirectivePresent = DIRECTIVE.matcher(normalizedContext).find();
        Set<String> requestedLiterals = requestedLiterals(normalizedContext);
        if (!explicitDirectivePresent && requestedLiterals.isEmpty()) {
            return Assessment.clear();
        }

        List<String> matchedLiterals = requestedLiterals.stream()
                .filter(literal -> controlFields.stream()
                        .filter(java.util.Objects::nonNull)
                        .anyMatch(field -> containsBoundedLiteral(field, literal)))
                .toList();
        if (matchedLiterals.isEmpty()) {
            return new Assessment(true, false, List.of(), null);
        }

        String reason = "Authority-bearing model output repeats a literal explicitly requested "
                + "by untrusted supporting context: " + matchedLiterals + ".";
        return new Assessment(true, true, matchedLiterals, reason);
    }

    /**
     * Remove an explicit directive and the remainder of its source line while
     * preserving factual text before it and all other lines. Sanitization is
     * used only for one bounded recovery call after an objective violation.
     */
    static String sanitize(String supportingContext) {
        if (supportingContext == null || supportingContext.isBlank()) {
            return supportingContext;
        }
        return supportingContext.lines()
                .map(line -> {
                    String normalizedLine = structuralText(line);
                    int directiveStart = firstDirectiveStart(normalizedLine);
                    if (directiveStart < 0) {
                        return normalizedLine;
                    }
                    return normalizedLine.substring(0, directiveStart)
                            + "[UNTRUSTED_INSTRUCTION_REMOVED]";
                })
                .collect(Collectors.joining("\n"));
    }

    private static int firstDirectiveStart(String line) {
        int first = firstMatchStart(DIRECTIVE, line);
        for (Pattern pattern : LITERAL_DIRECTIVES) {
            int candidate = firstMatchStart(pattern, line);
            if (candidate >= 0 && (first < 0 || candidate < first)) {
                first = candidate;
            }
        }
        return first;
    }

    private static int firstMatchStart(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.start() : -1;
    }

    private static Set<String> requestedLiterals(String context) {
        Set<String> literals = new LinkedHashSet<>();
        for (Pattern pattern : LITERAL_DIRECTIVES) {
            Matcher matcher = pattern.matcher(context);
            while (matcher.find()) {
                String literal = firstCapturedValue(matcher);
                if (!canonical(literal).isBlank()) {
                    literals.add(literal.strip());
                }
            }
        }
        return literals;
    }

    private static String firstCapturedValue(Matcher matcher) {
        for (int group = 1; group <= matcher.groupCount(); group++) {
            String value = matcher.group(group);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Split only on explicit answer/verdict boundaries. This is a content
     * invariant, not sentence sentiment analysis: a segment either equals the
     * requested literal after canonicalization or it does not.
     */
    private static Set<String> standaloneSegments(String output) {
        Set<String> segments = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < output.length(); index++) {
            char value = output.charAt(index);
            if (isSegmentBoundary(value)) {
                addCanonicalSegment(segments, current);
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        addCanonicalSegment(segments, current);
        return segments;
    }

    private static boolean isSegmentBoundary(char value) {
        return value == '.' || value == '!' || value == '?' || value == ';'
                || value == ':' || value == '\n' || value == '\r';
    }

    private static void addCanonicalSegment(Set<String> segments, StringBuilder raw) {
        String canonical = canonical(raw.toString());
        if (!canonical.isBlank()) {
            segments.add(canonical);
        }
    }

    /**
     * Canonical comparison uses JDK Unicode NFKC normalization, case folding,
     * whitespace collapse, and removal of boundary punctuation/symbols. It
     * deliberately performs no stemming, synonym expansion, or sentiment work.
     */
    private static String canonical(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = structuralText(value).toLowerCase(Locale.ROOT);
        int start = 0;
        int end = normalized.length();
        while (start < end) {
            int codePoint = normalized.codePointAt(start);
            if (!isBoundaryDecoration(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = normalized.codePointBefore(end);
            if (!isBoundaryDecoration(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        StringBuilder result = new StringBuilder(end - start);
        boolean previousWhitespace = false;
        for (int index = start; index < end;) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (!previousWhitespace && !result.isEmpty()) {
                    result.append(' ');
                }
                previousWhitespace = true;
            } else {
                result.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == ' ') {
            result.setLength(length - 1);
        }
        return result.toString();
    }

    /** Canonicalize compatibility forms and remove invisible parsing controls. */
    static boolean containsBoundedLiteral(String text, String literal) {
        String compared = canonical(text);
        String expected = canonical(literal);
        if (compared.isBlank() || expected.isBlank()) {
            return false;
        }
        int fromIndex = 0;
        while (fromIndex <= compared.length() - expected.length()) {
            int match = compared.indexOf(expected, fromIndex);
            if (match < 0) {
                return false;
            }
            int after = match + expected.length();
            boolean boundedBefore = match == 0
                    || !isLiteralCharacter(compared.codePointBefore(match));
            boolean boundedAfter = after == compared.length()
                    || !isLiteralCharacter(compared.codePointAt(after));
            if (boundedBefore && boundedAfter) {
                return true;
            }
            fromIndex = match + 1;
        }
        return false;
    }

    private static boolean isLiteralCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-';
    }

    static String structuralText(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder visible = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> {
                    int type = Character.getType(codePoint);
                    return (type != Character.FORMAT && type != Character.CONTROL)
                            || codePoint == '\n' || codePoint == '\r' || codePoint == '\t';
                })
                .forEach(visible::appendCodePoint);
        return visible.toString();
    }

    private static boolean isBoundaryDecoration(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION
                || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.OTHER_SYMBOL;
    }

    record Assessment(boolean suspiciousInput,
                      boolean violated,
                      List<String> matchedLiterals,
                      String reason) {
        Assessment {
            matchedLiterals = matchedLiterals == null ? List.of() : List.copyOf(matchedLiterals);
        }

        static Assessment clear() {
            return new Assessment(false, false, List.of(), null);
        }
    }
}
