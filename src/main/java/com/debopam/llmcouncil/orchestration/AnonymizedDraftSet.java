package com.debopam.llmcouncil.orchestration;

import java.util.List;
import java.util.Map;

public record AnonymizedDraftSet(List<AnonymizedDraft> drafts,
                                 Map<String, String> hiddenDraftToModel,
                                 long seed) {
}
