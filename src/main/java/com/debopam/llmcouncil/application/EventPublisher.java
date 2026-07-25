package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Publishes and replays council execution events.
 *
 * <p>This is now a facade over two separate jobs — {@link EventStore} keeps and
 * replays events, {@link EventBroker} hands them to whoever is streaming. It
 * survives as its own interface because fourteen classes take one, and none of
 * them care which half they are using.
 *
 * <p>{@link DefaultEventPublisher} is the only implementation. Swapping the
 * store underneath it is what makes an event history durable; the broker stays
 * in memory either way.
 */
public interface EventPublisher {
    CouncilEvent publish(String sessionId, String stage, String eventType,
                         String modelId, Map<String, Object> metadata);

    List<CouncilEvent> history(String sessionId);

    /**
     * Subscribe to future events for one session.
     *
     * <p>The returned handle must be closed by streaming callers to avoid
     * retaining dead SSE emitters in the process-local event publisher.
     */
    AutoCloseable subscribe(String sessionId, Consumer<CouncilEvent> listener);
}
