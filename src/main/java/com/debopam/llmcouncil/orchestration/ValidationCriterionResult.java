package com.debopam.llmcouncil.orchestration;

public record ValidationCriterionResult(String criterion,
                                        boolean passed,
                                        String rationale) {
}
