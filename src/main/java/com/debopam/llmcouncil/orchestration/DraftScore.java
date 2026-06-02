package com.debopam.llmcouncil.orchestration;

public record DraftScore(String draftId,
                         double baseScore,
                         double debateAdjustment,
                         double weightedScore,
                         int reviewCount) {
}
