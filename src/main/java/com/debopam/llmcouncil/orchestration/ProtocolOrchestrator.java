package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    public CouncilContext run(CouncilSession session, CouncilProfile profile) {
        ProtocolDefinition protocol = protocolRegistry.get(profile.protocolId());
        CouncilContext context = new CouncilContext(session, profile, protocol);

        events.publish(session.id(), "PROTOCOL", "PROTOCOL_SELECTED", null, Map.of(
                "protocolId", protocol.id(),
                "stages", protocol.orderedStages().stream().map(StageType::name).toList()
        ));

        for (StageType stageType : protocol.orderedStages()) {
            StageExecutor executor = executorRegistry.get(stageType);
            ProtocolStageOptions options = protocol.optionsFor(stageType);
            events.publish(session.id(), stageType.name(), "STAGE_STARTED", null, Map.of());
            context = executor.execute(context, options);
            events.publish(session.id(), stageType.name(), "STAGE_COMPLETED", null, Map.of());
        }

        return context;
    }
}
