package com.debopam.llmcouncil.orchestration;

public record DraftScore(String draftId, double weightedScore, int reviewCount) {
}
