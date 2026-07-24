package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;

import java.util.function.Consumer;

/**
 * Live fan-out of council events to whoever is streaming.
 *
 * <p>The other half of the old {@link EventPublisher}. This one is always
 * process-local, whatever {@code council.persistence.type} says: subscribers are
 * open SSE connections held by this JVM, and there is nothing to make durable
 * about a listener that dies with the connection.
 */
public interface EventBroker {

    /**
     * Hand an event to every current subscriber for its session.
     *
     * @param event the event to deliver
     */
    void publish(CouncilEvent event);

    /**
     * Subscribe to future events for one session.
     *
     * <p>The returned handle must be closed by streaming callers, or dead SSE
     * emitters accumulate for the life of the process.
     *
     * @param sessionId the council session to follow
     * @param listener  called for each subsequent event
     * @return a handle that unsubscribes when closed
     */
    AutoCloseable subscribe(String sessionId, Consumer<CouncilEvent> listener);
}
