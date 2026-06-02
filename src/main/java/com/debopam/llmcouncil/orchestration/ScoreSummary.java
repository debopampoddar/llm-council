package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record ScoreSummary(List<DraftScore> draftScores,
                           double variance,
                           String winningDraftId) {
}
