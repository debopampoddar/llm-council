package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Small async boundary for demo-facing chat runs.
 *
 * <p>The existing one-shot run endpoint remains synchronous. Chat uses this
 * executor so the API can return a running turn immediately while the council
 * continues on a virtual thread. The semaphore is deliberately process-local;
 * durable queues and recovery are called out as post-demo work.
 */
@Service
public class CouncilRunExecutor {

    private final CouncilService councilService;
    private final Semaphore runPermits;
    // Futures of submitted runs, so a queued run can be stopped before it starts.
    private final Map<String, Future<?>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    /**
     * @param councilService runs the protocol for a submitted session
     * @param catalogHolder  supplies the resolved runtime settings, so a user
     *                       overlay's {@code maxConcurrentRuns} takes effect
     */
    public CouncilRunExecutor(CouncilService councilService,
                              CouncilCatalogHolder catalogHolder) {
        int maxConcurrentRuns = catalogHolder.get().runtime().maxConcurrentRuns();
        this.councilService = councilService;
        this.runPermits = new Semaphore(Math.max(1, maxConcurrentRuns));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public CouncilRunSubmission submit(String sessionId, Consumer<CouncilRunCompletion> onCompletion) {
        if (!runPermits.tryAcquire()) {
            return CouncilRunSubmission.rejected(
                    sessionId,
                    "Too many council runs are already active. Try again after the current run completes.");
        }

        Future<?> future = executor.submit(() -> {
            try {
                CouncilContext context = councilService.runCouncil(sessionId);
                CouncilSession session = councilService.getSession(sessionId);
                boolean successful = session.failureReason() == null;
                String failure = session.failureReason();
                onCompletion.accept(new CouncilRunCompletion(sessionId, successful, session, context, failure));
            } catch (Exception ex) {
                CouncilSession session = councilService.getSession(sessionId);
                String failure = session.failureReason() != null ? session.failureReason() : ex.getMessage();
                onCompletion.accept(new CouncilRunCompletion(sessionId, false, session, null, failure));
            } finally {
                inFlight.remove(sessionId);
                runPermits.release();
            }
        });
        inFlight.put(sessionId, future);

        return CouncilRunSubmission.accepted(sessionId);
    }

    /**
     * Stop a queued run from starting.
     *
     * <p>Only ever called with {@code mayInterruptIfRunning = false}. Interrupting
     * a virtual thread part-way through an HTTP call to a model provider leaves
     * that connection in an undefined state, so a run that has already started is
     * left to notice its own cancellation at the next stage boundary instead.
     *
     * <p>This matters more than it looks: {@code max-concurrent-runs} defaults to
     * 1, so one unwanted run blocks every other run until it drains.
     *
     * @param sessionId the council session to stop
     * @return {@code true} if a submitted run was found
     */
    public boolean cancel(String sessionId) {
        Future<?> future = inFlight.get(sessionId);
        if (future == null) {
            return false;
        }
        future.cancel(false);
        return true;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
