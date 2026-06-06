/**
 * Auto-generated documentation for DebateSummary.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record DebateSummary(boolean skipped,
                            String skipReason,
                            List<DebateRound> rounds,
                            List<String> consensusPoints,
                            List<String> preservedDissent,
                            List<String> unresolvedRisks,
                            List<String> synthesisInstructions) {
    public static DebateSummary skipped(String reason) {
        return new DebateSummary(true, reason, List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
