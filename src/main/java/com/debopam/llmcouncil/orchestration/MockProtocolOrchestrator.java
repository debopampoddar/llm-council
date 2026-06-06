/**
 * Auto-generated documentation for MockProtocolOrchestrator.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class MockProtocolOrchestrator {
    private final EventPublisher events;

    public MockProtocolOrchestrator(EventPublisher events) {
        this.events = events;
    }

    public String run(CouncilSession session) {
        publish(session, "GENERATE", "STAGE_STARTED", null, Map.of());
        sleep(Duration.ofMillis(250));
        publish(session, "GENERATE", "MODEL_CALL_COMPLETED", "mock-a", Map.of("draftId", "draft-A"));
        publish(session, "GENERATE", "MODEL_CALL_COMPLETED", "mock-b", Map.of("draftId", "draft-B"));
        publish(session, "GENERATE", "STAGE_COMPLETED", null, Map.of("draftCount", 2));

        publish(session, "REVIEW", "STAGE_STARTED", null, Map.of());
        sleep(Duration.ofMillis(250));
        publish(session, "REVIEW", "REVIEW_COMPLETED", "mock-a", Map.of("reviewedDrafts", 2));
        publish(session, "REVIEW", "STAGE_COMPLETED", null, Map.of("validReviews", 1));

        publish(session, "SYNTHESIZE", "STAGE_STARTED", null, Map.of());
        sleep(Duration.ofMillis(250));
        String answer = "Phase 1 mock council answer for: " + session.question();
        publish(session, "SYNTHESIZE", "SYNTHESIS_COMPLETED", "mock-a", Map.of("characters", answer.length()));
        return answer;
    }

    private void publish(CouncilSession session, String stage, String type, String modelId, Map<String, Object> payload) {
        events.publish(session.id(), stage, type, modelId, payload);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock orchestration interrupted", e);
        }
    }
}
