/**
 * Auto-generated documentation for ProtocolOrchestratorTest.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.config.CouncilProperties;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolOrchestratorTest {
    @Test
    void runsOnlyStagesDeclaredByProtocol() {
        List<StageType> executed = new ArrayList<>();
        ProtocolDefinition protocol = new ProtocolDefinition(
                "test",
                "test protocol",
                List.of(StageType.GENERATE, StageType.SCORE, StageType.EXPORT),
                Map.of()
        );
        ProtocolDefinitionRegistry protocolRegistry = new FixedProtocolRegistry(protocol);
        StageExecutorRegistry executorRegistry = new StageExecutorRegistry(List.of(
                fake(StageType.GENERATE, executed),
                fake(StageType.SCORE, executed),
                fake(StageType.EXPORT, executed),
                fake(StageType.DEBATE, executed)
        ));

        ProtocolOrchestrator orchestrator = new ProtocolOrchestrator(protocolRegistry, executorRegistry, new NoopEventPublisher());
        CouncilSession session = CouncilSession.created(UUID.randomUUID(),
                "question",
                "context",
                "profile",
                DepthMode.RIGOROUS);
        CouncilProfile profile = new CouncilProfile("profile",
                List.of("m1"),
                "chair",
                "validator",
                "test");

        orchestrator.run(session, profile);

        assertEquals(List.of(StageType.GENERATE, StageType.SCORE, StageType.EXPORT), executed);
    }

    private StageExecutor fake(StageType stageType, List<StageType> executed) {
        return new StageExecutor() {
            @Override
            public StageType stage() {
                return stageType;
            }

            @Override
            public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
                executed.add(stageType);
                return context;
            }
        };
    }

    private static class FixedProtocolRegistry extends ProtocolDefinitionRegistry {
        private final ProtocolDefinition protocol;

        FixedProtocolRegistry(ProtocolDefinition protocol) {
            super(new CouncilProperties(null, null, Map.of(), Map.of(), Map.of(), Map.of()));
            this.protocol = protocol;
        }

        @Override
        public ProtocolDefinition get(String protocolId) {
            return protocol;
        }
    }

    private static class NoopEventPublisher implements EventPublisher {
        @Override
        public CouncilEvent publish(UUID sessionId, String stage, String type, String modelId, Map<String, Object> payload) {
            return new CouncilEvent(UUID.randomUUID(), sessionId, java.time.Instant.now(), stage, type, modelId, payload);
        }

        @Override
        public List<CouncilEvent> history(UUID sessionId) {
            return List.of();
        }

        @Override
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(UUID sessionId) {
            return new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        }
    }
}
