package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Orchestrates the execution of a council protocol by running each configured
 * stage in order. Emits lifecycle events for each stage start, completion,
 * failure, and skip.
 *
 * <p>If any stage throws or calls {@link CouncilContext#markFailed}, subsequent
 * stages are skipped and a {@code PROTOCOL_FAILED} event is emitted.
 */
@Component
public class ProtocolOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ProtocolOrchestrator.class);

    private final ProtocolDefinitionRegistry protocolRegistry;
    private final StageExecutorRegistry executorRegistry;
    private final EventPublisher events;

    public ProtocolOrchestrator(ProtocolDefinitionRegistry protocolRegistry,
                                StageExecutorRegistry executorRegistry,
                                EventPublisher events) {
        this.protocolRegistry = protocolRegistry;
        this.executorRegistry = executorRegistry;
        this.events = events;
    }

    /**
     * Run a full protocol for the given session and council profile.
     *
     * @param session The council session to run.
     * @param profile The council member and chair configuration.
     * @return The completed (or partially completed) {@link CouncilContext}.
     */
    public CouncilContext run(CouncilSession session, CouncilProfile profile, CouncilPolicy policy) {
        String protocolId = policy.protocolId();
        ProtocolDefinition protocol = protocolRegistry.get(protocolId);
        CouncilContext context = new CouncilContext(session, profile, policy, protocol);

        events.publish(session.id(), "PROTOCOL", "PROTOCOL_STARTED", null,
                       Map.of("protocolId", protocolId,
                              "policyId", policy.id(),
                              "profileId", profile.id(),
                              "stages", protocol.orderedStages().stream().map(StageType::name).toList()));

        List<StageType> stages = protocol.orderedStages();
        for (int i = 0; i < stages.size(); i++) {
            StageType stageType = stages.get(i);

            if (context.isTerminal()) {
                // Skip remaining stages after a fatal failure
                events.publish(session.id(), stageType.name(), "STAGE_SKIPPED", null,
                               Map.of("reason", "previous stage marked context terminal"));
                continue;
            }

            if (!executorRegistry.has(stageType)) {
                log.warn("No executor for stage {}; skipping", stageType);
                continue;
            }

            ProtocolStageOptions options = protocol.optionsFor(stageType);
            events.publish(session.id(), stageType.name(), "STAGE_STARTED", null,
                           Map.of("stageIndex", i));
            try {
                context = executorRegistry.get(stageType).execute(context, options);
                events.publish(session.id(), stageType.name(), "STAGE_COMPLETED", null, Map.of());
            } catch (Exception ex) {
                log.error("Stage {} failed for session {}", stageType, session.id(), ex);
                events.publish(session.id(), stageType.name(), "STAGE_FAILED", null,
                               Map.of("errorType", ex.getClass().getSimpleName(),
                                      "message", ex.getMessage() != null ? ex.getMessage() : ""));
                context.markFailed(stageType, ex);
            }
        }

        String terminalEvent = context.isTerminal() ? "PROTOCOL_FAILED" : "PROTOCOL_COMPLETED";
        events.publish(session.id(), "PROTOCOL", terminalEvent, null, Map.of());
        return context;
    }
}
