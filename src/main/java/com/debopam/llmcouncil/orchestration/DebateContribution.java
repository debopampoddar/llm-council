/**
 * Auto-generated documentation for DebateContribution.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record DebateContribution(String modelId,
                                 String position,
                                 List<String> supportedDraftIds,
                                 List<String> challengedDraftIds,
                                 List<String> newEvidence,
                                 List<String> unresolvedRisks,
                                 boolean changedPosition,
                                 String changeReason,
                                 double confidence) {
}
