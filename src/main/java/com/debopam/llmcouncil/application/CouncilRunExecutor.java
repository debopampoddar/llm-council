package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.observability.CouncilMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    private static final Logger log = LoggerFactory.getLogger(CouncilRunExecutor.class);

    private final CouncilService councilService;
    private final Semaphore runPermits;
    // Inserted before execution, avoiding a fast-task race where finally removes
    // an entry before submit() has stored it and a stale handle remains forever.
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final CouncilMetrics metrics;

    /**
     * @param councilService runs the protocol for a submitted session
     * @param catalogHolder  supplies the resolved runtime settings, so a user
     *                       overlay's {@code maxConcurrentRuns} takes effect
     */
    public CouncilRunExecutor(CouncilService councilService,
                              CouncilCatalogHolder catalogHolder) {
        this(councilService, catalogHolder, CouncilMetrics.noop());
    }

    @Autowired
    public CouncilRunExecutor(CouncilService councilService,
                              CouncilCatalogHolder catalogHolder,
                              CouncilMetrics metrics) {
        int maxConcurrentRuns = catalogHolder.get().runtime().maxConcurrentRuns();
        this.councilService = councilService;
        this.runPermits = new Semaphore(Math.max(1, maxConcurrentRuns));
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.metrics = metrics;
    }

    public CouncilRunSubmission submit(String sessionId, Consumer<CouncilRunCompletion> onCompletion) {
        if (!runPermits.tryAcquire()) {
            metrics.runRejected("capacity");
            return CouncilRunSubmission.rejected(
                    sessionId,
                    "Too many council runs are already active. Try again after the current run completes.");
        }
        if (inFlight.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            runPermits.release();
            metrics.runRejected("duplicate");
            return CouncilRunSubmission.rejected(sessionId, "This council session is already running.");
        }

        try {
            metrics.runAccepted();
            executor.execute(() -> execute(sessionId, onCompletion));
        } catch (RejectedExecutionException ex) {
            inFlight.remove(sessionId);
            runPermits.release();
            metrics.runFinished();
            metrics.runRejected("shutting_down");
            return CouncilRunSubmission.rejected(sessionId, "The council run executor is shutting down.");
        }

        return CouncilRunSubmission.accepted(sessionId);
    }

    private void execute(String sessionId, Consumer<CouncilRunCompletion> onCompletion) {
        try {
            CouncilRunCompletion completion;
            try {
                CouncilContext context = councilService.runCouncil(sessionId);
                CouncilSession session = councilService.getSession(sessionId);
                boolean successful = session.failureReason() == null;
                completion = new CouncilRunCompletion(
                        sessionId, successful, session, context, session.failureReason());
            } catch (Exception ex) {
                CouncilSession session = councilService.getSession(sessionId);
                String failure = session.failureReason() != null ? session.failureReason() : ex.getMessage();
                completion = new CouncilRunCompletion(sessionId, false, session, null, failure);
            }

            try {
                // A UI/persistence callback failure must not be mistaken for a council
                // failure and retried: that previously invoked the callback twice.
                onCompletion.accept(completion);
            } catch (RuntimeException ex) {
                log.error("Council run completion callback failed for session {}", sessionId, ex);
            }
        } finally {
            inFlight.remove(sessionId);
            runPermits.release();
            metrics.runFinished();
        }
    }

    /**
     * Reserve a concurrency permit for a run executed on the caller's own thread.
     *
     * <p>The synchronous run endpoint does not submit to this executor — it runs
     * the protocol inline on the request thread — but it consumes exactly the
     * same scarce resources: provider quota, tokens, and local model capacity.
     * Bounding only the asynchronous path made {@code max-concurrent-runs} a
     * chat-only setting whose name promised otherwise, so both paths now draw
     * from one pool.
     *
     * <p>Callers <b>must</b> pair a {@code true} result with {@link #releaseRunPermit()}
     * in a {@code finally} block.
     *
     * @return {@code true} when a permit was acquired and the caller may run
     */
    public boolean tryAcquireRunPermit() {
        if (!runPermits.tryAcquire()) {
            metrics.runRejected("capacity");
            return false;
        }
        metrics.runAccepted();
        return true;
    }

    /** Return a permit taken by {@link #tryAcquireRunPermit()}. */
    public void releaseRunPermit() {
        runPermits.release();
        metrics.runFinished();
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
        // CouncilService/RunRegistry owns cancellation, including the small
        // accepted-before-registered window. Cancelling the Future with
        // mayInterruptIfRunning=false can mark a task cancelled without running
        // its finally block, leaking a semaphore permit.
        return inFlight.containsKey(sessionId);
    }

    /**
     * Stop accepting work and interrupt whatever is still running.
     *
     * <p>This is the one place the "never interrupt a run" rule in
     * {@link #cancel(String)} is deliberately broken. At JVM shutdown there is no
     * later stage boundary to wait for, so an in-flight provider call is
     * interrupted and its connection left in an undefined state. That is
     * acceptable only because the process is going away: nothing in it will read
     * the connection again.
     *
     * <p>The consequence outlives the process, though. A run interrupted here may
     * never persist its terminal status, leaving a session stored as
     * {@code RUNNING} for ever. {@code InterruptedRunSweeper} is what closes that
     * gap on the next start, marking such a session {@code INTERRUPTED} rather
     * than resuming it — so this method depends on that sweeper existing, and
     * removing it would strand sessions rather than merely lose telemetry.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
