/**
 * Auto-generated documentation for MockModelClient.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class MockModelClient implements CouncilModelClient {
    private final ProviderProfile provider;

    public MockModelClient(ProviderProfile provider) {
        this.provider = provider;
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) {
        Instant started = Instant.now();
        String text = switch (request.stage()) {
            case "GENERATE" -> "Draft from " + request.logicalModelId() + " for question: " + lastUserMessage(request);
            case "REVIEW" -> """
                {
                  "evaluations": [
                    {
                      "draftId": "draft-A",
                      "scores": [
                        {"criterionId": "accuracy", "score": 82, "rationale": "Mostly correct"},
                        {"criterionId": "completeness", "score": 78, "rationale": "Covers main points"}
                      ],
                      "strengths": "Clear structure",
                      "weaknesses": "Needs more evidence",
                      "evidenceRequired": "Cite implementation constraints",
                      "wouldChangePosition": false,
                      "whatWouldChangeMyMind": "A stronger counterexample"
                    }
                  ],
                  "reviewerConfidence": "medium",
                  "globalConcerns": ["mock review"]
                }
                """;
            case "VALIDATE" -> "{\"approved\": true, \"confidence\": 0.8, \"issues\": [], \"recommendedFixes\": []}";
            default -> "Mock response from " + request.logicalModelId();
        };
        return new ModelCallResult(
                request.logicalModelId(),
                provider.id(),
                text,
                0,
                text.length() / 4,
                Duration.between(started, Instant.now()),
                Map.of("mock", true)
        );
    }

    @Override
    public ModelHealth health() {
        return ModelHealth.available(provider.id());
    }

    private String lastUserMessage(ModelCallRequest request) {
        return request.messages().stream()
                .filter(message -> "user".equals(message.role()))
                .reduce((first, second) -> second)
                .map(ChatMessage::content)
                .orElse("");
    }
}
