/**
 * Auto-generated documentation for DebateTriggerPolicyTest.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebateTriggerPolicyTest {
    private final DebateTriggerPolicy policy = new DebateTriggerPolicy();

    @Test
    void skipsWhenThereIsNoScoreSummary() {
        CouncilContext context = TestContexts.empty();

        DebateTriggerPolicy.DebateDecision decision = policy.shouldRun(context, ProtocolStageOptions.defaults());

        assertFalse(decision.run());
    }

    @Test
    void runsWhenForcedByProtocol() {
        CouncilContext context = TestContexts.withTwoDraftsAndScoreVariance(10.0);
        ProtocolStageOptions options = new ProtocolStageOptions("peer", 2, 120.0, 2, true, true, false, null);

        DebateTriggerPolicy.DebateDecision decision = policy.shouldRun(context, options);

        assertTrue(decision.run());
    }

    @Test
    void runsWhenVarianceExceedsThreshold() {
        CouncilContext context = TestContexts.withTwoDraftsAndScoreVariance(200.0);
        ProtocolStageOptions options = new ProtocolStageOptions("peer", 2, 120.0, 2, false, true, false, null);

        DebateTriggerPolicy.DebateDecision decision = policy.shouldRun(context, options);

        assertTrue(decision.run());
    }
}
