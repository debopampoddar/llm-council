package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.orchestration.CouncilContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The contexts of runs that are currently executing.
 *
 * <p>A council run owns its {@link CouncilContext} for the whole of its life and
 * then discards it, which leaves nothing for a request thread to talk to. This
 * registry is that handle: it exists so a cancellation arriving over HTTP can
 * reach a run already in flight on a virtual thread.
 *
 * <p>Entries are added when the protocol starts and removed when it finishes,
 * so the map holds at most {@code council.runtime.max-concurrent-runs} entries.
 */
@Component
public class RunRegistry {

    private final Map<String, CouncilContext> running = new ConcurrentHashMap<>();
    private final java.util.Set<String> pendingCancellations = ConcurrentHashMap.newKeySet();

    /**
     * Record a run as started.
     *
     * @param sessionId the council session
     * @param context   the context the run will thread through its stages
     */
    public void register(String sessionId, CouncilContext context) {
        running.put(sessionId, context);
        if (pendingCancellations.remove(sessionId)) {
            context.cancel();
        }
    }

    /**
     * Forget a run that has finished, however it finished.
     *
     * @param sessionId the council session
     */
    public void unregister(String sessionId) {
        running.remove(sessionId);
        pendingCancellations.remove(sessionId);
    }

    /** Clear a request that arrived around a run's terminal persistence window. */
    public void clearPendingCancellation(String sessionId) {
        pendingCancellations.remove(sessionId);
    }

    /**
     * Ask a running council to stop at its next stage boundary.
     *
     * @param sessionId the council session to cancel
     * @return {@code true} if a run was found and asked to stop; {@code false}
     *         when the run had already finished, which is a no-op rather than
     *         an error
     */
    public boolean cancel(String sessionId) {
        CouncilContext context = running.get(sessionId);
        if (context == null) {
            // The async task may have been accepted but not reached the
            // orchestrator yet. Remember the request so register() cannot lose
            // a cancellation in that small but real window.
            pendingCancellations.add(sessionId);
            return false;
        }
        context.cancel();
        return true;
    }

    /**
     * Look up a run in flight.
     *
     * @param sessionId the council session
     * @return the live context, or empty if the run is not currently executing
     */
    public Optional<CouncilContext> find(String sessionId) {
        return Optional.ofNullable(running.get(sessionId));
    }
}
