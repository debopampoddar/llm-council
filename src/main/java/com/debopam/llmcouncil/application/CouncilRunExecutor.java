package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ExecutorService executor;

    public CouncilRunExecutor(CouncilService councilService,
                              @Value("${council.runtime.max-concurrent-runs:1}") int maxConcurrentRuns) {
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

        executor.submit(() -> {
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
                runPermits.release();
            }
        });

        return CouncilRunSubmission.accepted(sessionId);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
