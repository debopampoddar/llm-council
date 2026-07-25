package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Live event fan-out to open SSE streams.
 *
 * <p>Unconditionally in-memory. There is no durable variant of this and there
 * should not be: a subscriber is an open connection held by this process, and it
 * stops existing when the process does.
 *
 * <p>The listener list is copy-on-write because events arrive from the virtual
 * threads running a council while request threads subscribe and unsubscribe.
 */
@Component
public class InMemoryEventBroker implements EventBroker {

    private final Map<String, List<Consumer<CouncilEvent>>> subscribersBySession =
            new ConcurrentHashMap<>();

    @Override
    public void publish(CouncilEvent event) {
        subscribersBySession.getOrDefault(event.sessionId(), List.of())
                            .forEach(listener -> listener.accept(event));
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
