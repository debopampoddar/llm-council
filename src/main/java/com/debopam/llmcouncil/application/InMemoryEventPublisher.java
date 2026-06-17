package com.debopam.llmcouncil.application;
import com.debopam.llmcouncil.domain.CouncilEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory event store plus logger.
 *
 * <p>This gives the API a replayable event history during local development.
 * It is still process-local; production persistence should swap this component
 * for a durable implementation without changing stage executors.
 */
@Component
public class InMemoryEventPublisher implements EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(InMemoryEventPublisher.class);
    private final Map<String, List<CouncilEvent>> eventsBySession = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<CouncilEvent>>> subscribersBySession = new ConcurrentHashMap<>();

    @Override
    public CouncilEvent publish(String sessionId, String stage, String eventType,
                                String modelId, Map<String, Object> metadata) {
        CouncilEvent event = CouncilEvent.of(sessionId, stage, eventType, modelId, metadata);
        eventsBySession.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        subscribersBySession.getOrDefault(sessionId, List.of())
                .forEach(listener -> listener.accept(event));
        log.info("[{}] {}/{} model={} meta={}", sessionId, stage, eventType, modelId, metadata);
        return event;
    }

    @Override
    public List<CouncilEvent> history(String sessionId) {
        return List.copyOf(eventsBySession.getOrDefault(sessionId, new ArrayList<>()));
    }

    @Override
    public AutoCloseable subscribe(String sessionId, Consumer<CouncilEvent> listener) {
        List<Consumer<CouncilEvent>> listeners =
                subscribersBySession.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                subscribersBySession.remove(sessionId, listeners);
            }
        };
    }
}
