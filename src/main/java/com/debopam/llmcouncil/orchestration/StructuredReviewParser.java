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
            throw new IllegalArgumentException("Invalid review JSON from " + reviewerModelId, e);
        }
    }

    public DebateModelOutput parseDebate(String modelId, String rawJson, Set<String> validDraftIds) {
        try {
            DebateModelOutput parsed = objectMapper.readValue(rawJson, DebateModelOutput.class);
            validateDraftIds("supportedDraftIds", parsed.supportedDraftIds(), validDraftIds);
            validateDraftIds("challengedDraftIds", parsed.challengedDraftIds(), validDraftIds);
            if (parsed.confidence() < 0.0 || parsed.confidence() > 1.0) {
                throw new IllegalArgumentException("Debate confidence must be between 0.0 and 1.0");
            }
            return parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid debate JSON from " + modelId, e);
        }
    }

    public ValidationOutput parseValidation(String rawJson) {
        try {
            ValidationOutput output = objectMapper.readValue(rawJson, ValidationOutput.class);
            if (output.confidence() < 0.0 || output.confidence() > 1.0) {
                throw new IllegalArgumentException("Validation confidence must be between 0.0 and 1.0");
            }
            return output;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid validation JSON", e);
        }
    }

    private void validateDraftIds(String field, java.util.List<String> draftIds, Set<String> validDraftIds) {
        if (draftIds == null) {
            return;
        }
        for (String draftId : draftIds) {
            if (!validDraftIds.contains(draftId)) {
                throw new IllegalArgumentException(field + " references unknown draft " + draftId);
            }
        }
    }
}
