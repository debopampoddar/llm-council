package com.debopam.llmcouncil.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class StructuredReviewParser {
    private final ObjectMapper objectMapper;

    public StructuredReviewParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PeerReviewOutput parseReview(String reviewerModelId, String rawJson, Set<String> validDraftIds) {
        try {
            PeerReviewOutput parsed = objectMapper.readValue(rawJson, PeerReviewOutput.class);
            for (DraftEvaluation evaluation : parsed.evaluations()) {
                if (!validDraftIds.contains(evaluation.draftId())) {
                    throw new IllegalArgumentException("Review references unknown draft " + evaluation.draftId());
                }
                for (CriterionScore score : evaluation.scores()) {
                    if (score.score() < 1 || score.score() > 100) {
                        throw new IllegalArgumentException("Score must be between 1 and 100");
                    }
                }
            }
            return new PeerReviewOutput(reviewerModelId, parsed.evaluations(), parsed.reviewerConfidence(), parsed.globalConcerns());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse review JSON from " + reviewerModelId, e);
        }
    }

    public ValidationOutput parseValidation(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, ValidationOutput.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to parse validation JSON", e);
        }
    }
}
