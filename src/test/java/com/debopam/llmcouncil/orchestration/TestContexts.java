/**
 * Auto-generated documentation for TestContexts.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilProfile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TestContexts {
    private TestContexts() {
    }

    static CouncilContext empty() {
        return base();
    }

    static CouncilContext withTwoDraftsAndScoreVariance(double variance) {
        CouncilContext context = base();
        context.addDraft(new Draft("draft-1", "m1", "answer 1"));
        context.addDraft(new Draft("draft-2", "m2", "answer 2"));
        context.setScoreSummary("initial", new ScoreSummary(List.of(
                new DraftScore("draft-A", 80, 0, 80, 1),
                new DraftScore("draft-B", 50, 0, 50, 1)
        ), variance, "draft-A"));
        return context;
    }

    private static CouncilContext base() {
        CouncilSession session = CouncilSession.created(UUID.randomUUID(), "question", "context", "profile", DepthMode.RIGOROUS);
        CouncilProfile profile = new CouncilProfile("profile", List.of("m1", "m2"), "chair", "validator", "rigorous");
        ProtocolDefinition protocol = new ProtocolDefinition("rigorous", "test", List.of(StageType.DEBATE), Map.of());
        return new CouncilContext(session, profile, protocol);
    }
}
