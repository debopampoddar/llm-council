package com.debopam.llmcouncil.orchestration;

import java.util.List;

public record DebateRound(int roundNumber,
                          List<DebateContribution> contributions) {
}
