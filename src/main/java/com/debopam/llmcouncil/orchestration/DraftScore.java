/**
 * Auto-generated documentation for DraftScore.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

public record DraftScore(String draftId,
                         double baseScore,
                         double debateAdjustment,
                         double weightedScore,
                         int reviewCount) {
}
