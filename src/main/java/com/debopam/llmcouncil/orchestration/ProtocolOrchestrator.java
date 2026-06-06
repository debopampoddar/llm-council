/**
 * Auto-generated documentation for ProtocolOrchestrator.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Orchestrates the execution of a council protocol by running each configured stage in order.
 * Emits lifecycle events for protocol selection, stage start, completion, and failure.
 */
@Component
public class ProtocolOrchestrator {

    private final ProtocolDefinitionRegistry protocolRegistry;
    private final StageExecutorRegistry executorRegistry;
    private final EventPublisher events;

    public ProtocolOrchestrator(
            ProtocolDefinitionRegistry protocolRegistry,
            StageExecutorRegistry executorRegistry,
            EventPublisher events
    ) {
        this.protocolRegistry = protocolRegistry;
        this.executorRegistry = executorRegistry;
        this.events = events;
    }

    /**
     * Run a full protocol for the given session and council profile.
     * Any stage may mark the context as terminal, in which case remaining stages are skipped.
     */
    public CouncilContext run(CouncilSession session, CouncilProfile profile) {
        ProtocolDefinition protocol = protocolRegistry.get(profile.protocolId());
        CouncilContext context = new CouncilContext(session, profile, protocol);

        events.publish(session.id(), "PROTOCOL", "PROTOCOL_SELECTED", null, Map.of(
                "protocolId", protocol.id(),
                "stages", protocol.orderedStages().stream().map(StageType::name).toList()
        ));

        for (StageType stageType : protocol.orderedStages()) {
            if (context.isTerminal()) {
                // A previous stage decided to stop the pipeline (e.g. fatal validation failure).
                break;
            }

            StageExecutor executor = executorRegistry.get(stageType);
            ProtocolStageOptions options = protocol.optionsFor(stageType);

            events.publish(session.id(), stageType.name(), "STAGE_STARTED", null, Map.of());
            try {
                context = executor.execute(context, options);
                events.publish(session.id(), stageType.name(), "STAGE_COMPLETED", null, Map.of());
            } catch (Exception ex) {
                // Record failure and mark context as terminal; downstream stages will be skipped.
                events.publish(session.id(), stageType.name(), "STAGE_FAILED", null, Map.of(
                        "errorType", ex.getClass().getSimpleName(),
                        "message", ex.getMessage()
                ));
                context.markFailed(stageType, ex);
            }
        }

        return context;
    }
}
