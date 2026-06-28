package com.debopam.llmcouncil.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Parses model-produced JSON for review and validation stages.
 *
 * <p>Model output is untrusted. Parsing is intentionally strict about score and
 * confidence ranges; callers decide whether to retry or exclude a malformed
 * reviewer.
 */
@Component
public class StructuredOutputParser {
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
        try {
            ReviewEnvelope envelope = objectMapper.readValue(extractJson(text), ReviewEnvelope.class);
            if (envelope.reviews() == null || envelope.reviews().isEmpty()) {
                throw new IllegalArgumentException("Review output did not contain reviews");
            }
            envelope.reviews().forEach(this::validateReview);
            return envelope;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse review JSON", ex);
        }
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
     */
    private String extractJson(String text) {
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
     * Removes markdown code fences that surround JSON in LLM output.
     *
     * <p>Handles both fenced blocks with a language tag ({@code ```json})
     * and plain fences ({@code ```}).
     */
    private String stripMarkdownFences(String text) {
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
    public record ReviewEnvelope(List<ReviewJson> reviews) {}

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
