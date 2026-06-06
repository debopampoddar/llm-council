/**
 * Auto-generated documentation for PeerReviewOutput.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record PeerReviewOutput(String reviewerModelId,
                               List<DraftEvaluation> evaluations,
                               String reviewerConfidence,
                               List<String> globalConcerns) {
}
