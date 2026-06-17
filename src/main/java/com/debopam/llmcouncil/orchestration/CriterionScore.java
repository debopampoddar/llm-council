package com.debopam.llmcouncil.orchestration;

/** Per-rubric score produced by a reviewer for one draft. */
public record CriterionScore(String name, int score, String rationale) {
    public CriterionScore {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Criterion score must be between 0 and 100");
        }
    }
}
