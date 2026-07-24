package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Appends an event, then publishes it.
 *
 * <p>{@link EventPublisher} survives as this thin composite so that the split
 * underneath it changes no caller: fourteen classes take an
 * {@code EventPublisher}, and none of them care whether the history behind it is
 * a map or a table.
 *
 * <p>Order matters. The event is stored before it is delivered, so a client that
 * reacts to a frame by asking for history cannot be told the event it just
 * received does not exist.
 */
@Component
public class DefaultEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultEventPublisher.class);

    private final EventStore store;
    private final EventBroker broker;

    /**
     * @param store  where events are kept and replayed from
     * @param broker live fan-out to open streams
     */
    public DefaultEventPublisher(EventStore store, EventBroker broker) {
        this.store = store;
        this.broker = broker;
    }

    /**
     * Direct construction over in-memory halves, for tests that only need a
     * working publisher.
     */
    public DefaultEventPublisher() {
        this(new InMemoryEventStore(), new InMemoryEventBroker());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A store failure is logged and swallowed. Events are written on the hot
     * path of every stage, and a council run takes minutes: losing a
     * ten-minute run because a disk filled up would be a far worse outcome than
     * losing the observability record of it. The event is still delivered to
     * anyone streaming, so a run whose history cannot be written is at least
     * watchable while it happens.
     */
    @Override
    public CouncilEvent publish(String sessionId, String stage, String eventType,
                                String modelId, Map<String, Object> metadata) {
        CouncilEvent event = CouncilEvent.of(sessionId, stage, eventType, modelId, metadata);
        CouncilEvent stored;
        try {
            stored = store.append(event);
        } catch (RuntimeException ex) {
            log.warn("Unable to record {}/{} for session {}; the run continues without it: {}",
                     stage, eventType, sessionId, ex.toString());
            stored = event;
        }
        broker.publish(stored);
        log.info("[{}] {}/{} model={} meta={}", sessionId, stage, eventType, modelId, metadata);
        return stored;
    }

    @Override
    public List<CouncilEvent> history(String sessionId) {
        return store.history(sessionId);
    }

    @Override
    public AutoCloseable subscribe(String sessionId, Consumer<CouncilEvent> listener) {
        return broker.subscribe(sessionId, listener);
    }
}
