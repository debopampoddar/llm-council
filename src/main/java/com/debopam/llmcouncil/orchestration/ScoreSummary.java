/**
 * Auto-generated documentation for ScoreSummary.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record ScoreSummary(List<DraftScore> draftScores,
                           double variance,
                           String winningDraftId) {
}
