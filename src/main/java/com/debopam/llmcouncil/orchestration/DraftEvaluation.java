/**
 * Auto-generated documentation for DraftEvaluation.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record DraftEvaluation(String draftId,
                              List<CriterionScore> scores,
                              String strengths,
                              String weaknesses,
                              String evidenceRequired,
                              boolean wouldChangePosition,
                              String whatWouldChangeMyMind) {
}
