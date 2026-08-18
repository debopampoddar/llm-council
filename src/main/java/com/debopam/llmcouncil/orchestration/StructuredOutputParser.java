package com.debopam.llmcouncil.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses model-produced JSON for review and validation stages.
 *
 * <p>Model output is untrusted. Parsing is intentionally strict about score and
 * confidence ranges; callers decide whether to retry or exclude a malformed
 * reviewer. Fractional scores inside the valid range are rounded to the nearest
 * integer because local models sometimes emit values such as {@code 83.6} even
 * when the requested schema says integer.
 */
@Component
public class StructuredOutputParser {
    private static final int MAX_REVIEW_ENVELOPES = 32;
    private static final int MAX_REVIEWS = 64;

    private final ObjectMapper objectMapper;

    /**
     * Creates a parser with lenient Jackson settings suitable for LLM-produced JSON.
     *
     * <p>A defensive copy of the supplied {@link ObjectMapper} is configured to
     * tolerate trailing commas and single-line comments — common artefacts of
     * model output that would otherwise cause hard parse failures.
     */
    public StructuredOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
    }

    public ReviewEnvelope parseReviews(String text) {
        List<String> jsonObjects = extractTopLevelJsonObjects(text);
        List<ReviewJson> reviews = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        boolean foundEnvelope = false;

        for (int envelopeIndex = 0;
             envelopeIndex < jsonObjects.size() && envelopeIndex < MAX_REVIEW_ENVELOPES;
             envelopeIndex++) {
            try {
                JsonNode root = objectMapper.readTree(jsonObjects.get(envelopeIndex));
                JsonNode reviewNodes = root == null ? null : root.get("reviews");
                if (reviewNodes == null) {
                    continue;
                }
                foundEnvelope = true;
                if (!reviewNodes.isArray()) {
                    diagnostics.add("Envelope " + (envelopeIndex + 1)
                            + " was ignored because reviews was not an array");
                    continue;
                }
                for (int reviewIndex = 0;
                     reviewIndex < reviewNodes.size() && reviews.size() < MAX_REVIEWS;
                     reviewIndex++) {
                    try {
                        reviews.add(parseReview(reviewNodes.get(reviewIndex)));
                    } catch (IllegalArgumentException ex) {
                        diagnostics.add("Review " + (reviewIndex + 1) + " in envelope "
                                + (envelopeIndex + 1) + " was ignored: " + ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                diagnostics.add("Envelope " + (envelopeIndex + 1)
                        + " was ignored because it was not valid JSON");
            }
        }

        if (!foundEnvelope || reviews.isEmpty()) {
            String detail = diagnostics.isEmpty() ? "no review envelope was found"
                                                   : String.join("; ", diagnostics);
            throw new IllegalArgumentException("Unable to parse review JSON: " + detail);
        }
        return new ReviewEnvelope(reviews, diagnostics);
    }

    private ReviewJson parseReview(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("review must be an object");
        }
        ReviewJson review = new ReviewJson(
                requiredText(node, "draftId"),
                stringList(node.get("strengths"), "strengths"),
                stringList(node.get("issues"), "issues"),
                criteria(node.get("criteria")),
                requiredScore(node.get("overallScore"), "overallScore"),
                requiredDouble(node, "confidence"));
        validateReview(review);
        return review;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("review missing " + field);
        }
        return value.textValue();
    }

    private int requiredScore(JsonNode value, String field) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        double score = value.doubleValue();
        if (score < 0.0 || score > 100.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
        return (int) Math.round(score);
    }

    private double requiredDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return value.doubleValue();
    }

    private List<String> stringList(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(field + " entries must be strings");
            }
            values.add(value.textValue());
        }
        return List.copyOf(values);
    }

    /**
     * Read the documented criterion array and the compact object form commonly
     * produced by smaller local models. The compact form loses rationale text,
     * but not the score itself, so rejecting every review in the response would
     * discard more trustworthy evidence than accepting the empty rationale.
     */
    private List<CriterionScore> criteria(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<CriterionScore> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode criterion : node) {
                if (!criterion.isObject()) {
                    throw new IllegalArgumentException("criteria entries must be objects");
                }
                JsonNode rationale = criterion.get("rationale");
                result.add(new CriterionScore(
                        requiredText(criterion, "name"),
                        requiredScore(criterion.get("score"), "criterion score"),
                        rationale != null && rationale.isTextual() ? rationale.textValue() : ""));
            }
            return List.copyOf(result);
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                JsonNode score = entry.getValue();
                result.add(new CriterionScore(entry.getKey(),
                        requiredScore(score, "compact criterion score for " + entry.getKey()), ""));
            });
            return List.copyOf(result);
        }
        throw new IllegalArgumentException("criteria must be an array or score object");
    }

    public ValidationEnvelope parseValidation(String text) {
        try {
            ValidationEnvelope validation = objectMapper.readValue(extractJson(text), ValidationEnvelope.class);
            if (validation.confidence() < 0.0 || validation.confidence() > 1.0) {
                throw new IllegalArgumentException("Validation confidence must be between 0.0 and 1.0");
            }
            return validation;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse validation JSON", ex);
        }
    }

    private void validateReview(ReviewJson review) {
        if (review.draftId() == null || review.draftId().isBlank()) {
            throw new IllegalArgumentException("Review missing draftId");
        }
        if (review.overallScore() < 0 || review.overallScore() > 100) {
            throw new IllegalArgumentException("Review score must be between 0 and 100");
        }
        if (review.confidence() < 0.0 || review.confidence() > 1.0) {
            throw new IllegalArgumentException("Review confidence must be between 0.0 and 1.0");
        }
        if (review.criteria() != null) {
            review.criteria().forEach(c -> {
                if (c.score() < 0 || c.score() > 100) {
                    throw new IllegalArgumentException("Criterion score must be between 0 and 100");
                }
            });
        }
    }

    /**
     * Extracts the outermost JSON object from model output.
     *
     * <p>LLMs frequently wrap JSON in markdown code fences
     * ({@code ```json ... ```}) or emit preamble/postamble prose.
     * This method strips fences first, then locates the first {@code \{}
     * and last {@code \}} to isolate the JSON payload.
     *
     * <p>Public and static because the requirement advisor parses model output
     * too, and a second copy of this tolerance would drift: the two would end up
     * accepting different shapes of the same sloppy reply.
     *
     * @param text raw model output, possibly fenced or wrapped in prose
     * @return the outermost JSON object
     * @throws IllegalArgumentException when the output contains no JSON object
     */
    public static String extractJson(String text) {
        String trimmed = text == null ? "" : text.trim();
        // Strip markdown code fences that LLMs often wrap JSON in.
        trimmed = stripMarkdownFences(trimmed);
        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (objectStart < 0 || objectEnd <= objectStart) {
            throw new IllegalArgumentException("No JSON object found in model output");
        }
        return trimmed.substring(objectStart, objectEnd + 1);
    }

    /**
     * Extract complete top-level JSON objects without mistaking braces inside a
     * quoted string for structure. Some local models emit one review envelope
     * per draft with headings between them. Parsing only the first envelope can
     * silently erase every non-self review, so all bounded envelopes are read.
     */
    static List<String> extractTopLevelJsonObjects(String text) {
        String value = text == null ? "" : text;
        List<String> objects = new ArrayList<>();
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"' && depth > 0) {
                inString = true;
            } else if (current == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
            } else if (current == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(value.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("No complete JSON object found in model output");
        }
        return List.copyOf(objects);
    }

    /**
     * Removes markdown code fences that surround JSON in LLM output.
     *
     * <p>Handles both fenced blocks with a language tag ({@code ```json})
     * and plain fences ({@code ```}).
     */
    private static String stripMarkdownFences(String text) {
        // Match opening fence with optional language tag and closing fence.
        // Pattern: ```json?\n ... \n```  or ```\n ... \n```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewEnvelope(List<ReviewJson> reviews, List<String> diagnostics) {
        public ReviewEnvelope {
            reviews = reviews == null ? List.of() : List.copyOf(reviews);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewJson(
            String draftId,
            List<String> strengths,
            List<String> issues,
            List<CriterionScore> criteria,
            int overallScore,
            double confidence
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ValidationEnvelope(
            boolean approved,
            double confidence,
            List<String> issues,
            List<String> recommendedFixes,
            Map<String, String> criteria,
            boolean requiresHumanReview
    ) {}
}
