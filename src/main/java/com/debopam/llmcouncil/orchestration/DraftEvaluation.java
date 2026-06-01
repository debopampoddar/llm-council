package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record DraftEvaluation(String draftId,
                              List<CriterionScore> scores,
                              String strengths,
                              String weaknesses,
                              String evidenceRequired,
                              boolean wouldChangePosition,
                              String whatWouldChangeMyMind) {
}
