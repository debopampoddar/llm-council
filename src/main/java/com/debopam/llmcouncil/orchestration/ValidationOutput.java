/**
 * Auto-generated documentation for ValidationOutput.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;

/*
Validation criteria:

correctness: does the answer address the question accurately?
completeness: does it cover important constraints and edge cases?
uncertainty: does it disclose unresolved risk or dissent?
safety: does it avoid unsafe or disallowed advice?
actionability: can a reader use the answer?
*/
public record ValidationOutput(boolean approved,
                               double confidence,
                               List<String> issues,
                               List<String> recommendedFixes,
                               List<ValidationCriterionResult> criteria,
                               boolean requiresHumanReview) {
}
