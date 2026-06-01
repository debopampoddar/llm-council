package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class ProtocolOrchestrator {
    private final List<StageExecutor> executors;
    private final EventPublisher events;

    public ProtocolOrchestrator(List<StageExecutor> executors, EventPublisher events) {
        this.executors = executors.stream().sorted(Comparator.comparingInt(this::order)).toList();
        this.events = events;
    }

    public CouncilContext run(CouncilSession session, CouncilProfile profile) {
        CouncilContext context = new CouncilContext(session, profile);
        for (StageExecutor executor : executors) {
            events.publish(session.id(), executor.stage(), "STAGE_STARTED", null, Map.of());
            context = executor.execute(context);
            events.publish(session.id(), executor.stage(), "STAGE_COMPLETED", null, Map.of());
        }
        return context;
    }

    private int order(StageExecutor executor) {
        return switch (executor.stage()) {
            case "GENERATE" -> 10;
            case "ANONYMIZE" -> 20;
            case "REVIEW" -> 30;
            case "SCORE" -> 40;
            case "SYNTHESIZE" -> 50;
            case "VALIDATE" -> 60;
            default -> 100;
        };
    }
}
