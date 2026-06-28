package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.orchestration.StageType;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic test client.
 *
 * <p>Mock is intentionally stage-aware so local smoke tests exercise the same
 * structured review and validation parsers as real providers.
 *
 * <p>Returns synthetic token counts ({@code promptTokens=50, completionTokens=100})
 * and a fixed latency so integration tests can verify the token usage pipeline.
 */
public class MockModelClient implements ModelClient {
    private static final Pattern DRAFT_ID_PATTERN = Pattern.compile("id=\"(draft-[^\"]+)\"");

    /** Synthetic prompt token count returned by the mock for testing. */
    private static final long MOCK_PROMPT_TOKENS = 50L;
    /** Synthetic completion token count returned by the mock for testing. */
    private static final long MOCK_COMPLETION_TOKENS = 100L;
    /** Synthetic latency returned by the mock for testing. */
    private static final Duration MOCK_LATENCY = Duration.ofMillis(42);

    private final String modelId;

    public MockModelClient(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) {
        String text = switch (request.stage()) {
            case REVIEW, REVIEW_POST_DEBATE -> reviewJson(request);  // reuse review JSON for post-debate
            case VALIDATE -> validationJson();
            case SYNTHESIZE -> "Mock final answer from " + modelId
                    + "\n\nRationale: mock synthesis combined available drafts."
                    + "\n\nImportant dissent: none in mock mode."
                    + "\n\nUnresolved risks: real model validation is required outside tests."
                    + "\n\nConfidence: 0.80";
            case DEBATE -> "Mock debate contribution from " + modelId + ". Confidence: 75";
            // Mock revision retains original position with minor adjustments
            case REVISE -> "Mock revised answer from " + modelId
                    + "\n\nRevised based on debate arguments."
                    + "\nChanges: incorporated feedback from debate round."
                    + "\nOriginal position retained where evidence supports it."
                    + "\nConfidence: 80";
            default -> "Mock answer from " + modelId
                    + "\nKey reasons: deterministic test output."
                    + "\nUncertainties: mock output is not factual evidence."
                    + "\nConfidence: 0.75";
        };
        return new ModelCallResult(text, MOCK_PROMPT_TOKENS, MOCK_COMPLETION_TOKENS, MOCK_LATENCY);
    }

    private String reviewJson(ModelCallRequest request) {
        Set<String> draftIds = draftIds(request);
        if (draftIds.isEmpty()) {
            draftIds = Set.of("draft-mock");
        }
        String reviews = draftIds.stream()
                .map(draftId -> """
                        {
                          "draftId": "%s",
                          "strengths": ["Clear enough for mock testing"],
                          "issues": ["Mock review is not a factual evaluation"],
                          "criteria": [
                            {"name": "accuracy", "score": 80, "rationale": "mock score"},
                            {"name": "completeness", "score": 78, "rationale": "mock score"},
                            {"name": "reasoning", "score": 76, "rationale": "mock score"},
                            {"name": "clarity", "score": 82, "rationale": "mock score"},
                            {"name": "constructiveness", "score": 75, "rationale": "mock score"}
                          ],
                          "overallScore": 79,
                          "confidence": 0.70
                        }
                        """.formatted(draftId))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return "{\"reviews\":[" + reviews + "]}";
    }

    private Set<String> draftIds(ModelCallRequest request) {
        Set<String> ids = new LinkedHashSet<>();
        request.messages().forEach(message -> {
            Matcher matcher = DRAFT_ID_PATTERN.matcher(message.content());
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        });
        return ids;
    }

    private String validationJson() {
        return """
                {
                  "approved": true,
                  "confidence": 0.82,
                  "issues": [],
                  "recommendedFixes": [],
                  "criteria": {
                    "correctness": "pass: mock validation",
                    "completeness": "pass: mock validation",
                    "uncertainty": "pass: mock validation",
                    "safety": "pass: mock validation",
                    "actionability": "pass: mock validation"
                  },
                  "requiresHumanReview": false
                }
                """;
    }
}
