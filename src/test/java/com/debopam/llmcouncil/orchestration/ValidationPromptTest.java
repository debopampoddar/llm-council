package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationPromptTest {

    @Test
    void requiresIndependentChecksAndExplicitEscalationForUnverifiableClaims() {
        String system = new PromptBuilder().validationMessages(
                "question", "context", "answer").getFirst().content();

        assertTrue(system.contains("reason through the task independently"));
        assertTrue(system.contains("Recompute material arithmetic"));
        assertTrue(system.contains("requiresHumanReview=true and approved=false"));
        assertTrue(system.contains("assets, trust boundaries"));
        assertTrue(system.contains("confidence in this assessment"));
    }
}
