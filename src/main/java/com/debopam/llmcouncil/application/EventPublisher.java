package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Publishes and replays council execution events.
 *
 * <p>The first implementation is in-memory, but callers depend on the replay
 * contract rather than log output. That makes it straightforward to replace
 * this with a database-backed event store later.
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
