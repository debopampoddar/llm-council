package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.DefaultEventPublisher;
import com.debopam.llmcouncil.application.RunRegistry;
import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProtocolPartialStatusTest {

    @Test
    void degradedNonTerminalRunEndsWithProtocolPartial() {
        ProtocolDefinition protocol = new ProtocolDefinition(
                "partial-test", "Partial test", List.of(StageType.SYNTHESIZE), Map.of());
        ProtocolDefinitionRegistry protocols = new ProtocolDefinitionRegistry();
        protocols.register(Map.of(protocol.id(), protocol));
        StageExecutor degradingExecutor = new StageExecutor() {
            @Override public StageType stage() { return StageType.SYNTHESIZE; }
            @Override public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
                context.setSynthesisResult("answer");
                context.markDegraded("required review evidence was unavailable");
                return context;
            }
        };
        DefaultEventPublisher events = new DefaultEventPublisher();
        ProtocolOrchestrator orchestrator = new ProtocolOrchestrator(
                protocols, new StageExecutorRegistry(List.of(degradingExecutor)),
                events, new RunRegistry());
        CouncilSession session = CouncilSession.create(
                "protocol-partial", "question", null, DepthMode.RIGOROUS, "local");
        CouncilProfile profile = TestModels.profile("local").displayName("Local")
                .defaultDepth(DepthMode.RIGOROUS).depth(DepthMode.RIGOROUS, "policy").build();
        CouncilPolicy policy = TestModels.policy("policy").protocol("partial-test")
                .members("member").chair("chair").build();

        CouncilContext result = orchestrator.run(session, profile, policy);

        assertFalse(result.isTerminal(), "degraded evidence is not a fatal execution failure");
        assertEquals("PROTOCOL_PARTIAL", events.history(session.id()).getLast().type());
        assertEquals(List.of("required review evidence was unavailable"),
                events.history(session.id()).getLast().payload().get("reasons"));
    }
}
