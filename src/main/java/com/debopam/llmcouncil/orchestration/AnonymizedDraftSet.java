/**
 * Auto-generated documentation for AnonymizedDraftSet.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;
import java.util.Map;

public record AnonymizedDraftSet(List<AnonymizedDraft> drafts,
                                 Map<String, String> hiddenDraftToModel,
                                 long seed) {
}
