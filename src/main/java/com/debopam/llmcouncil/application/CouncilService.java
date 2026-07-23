package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.orchestration.ProtocolOrchestrator;
import com.debopam.llmcouncil.persistence.SessionStore;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Application service that orchestrates session lifecycle and delegates to
 * {@link ProtocolOrchestrator} for protocol execution.
 */
@Service
public class CouncilService {

    /** Failure reason recorded on a run the user stopped. */
    public static final String CANCELLED_BY_USER = "Cancelled by user";

    private final ProtocolOrchestrator orchestrator;
    private final SessionStore sessionStore;
    private final CouncilPolicyResolver policyResolver;
    private final RunRegistry runRegistry;

    public CouncilService(ProtocolOrchestrator orchestrator,
                          SessionStore sessionStore,
                          CouncilPolicyResolver policyResolver,
                          RunRegistry runRegistry) {
        this.orchestrator = orchestrator;
        this.sessionStore = sessionStore;
        this.policyResolver = policyResolver;
        this.runRegistry = runRegistry;
    }

    /**
     * Ask a running council to stop at its next stage boundary.
     *
     * <p>Cancelling a run that has already finished is a no-op, not an error:
     * the caller's intent — that this run should not continue — is satisfied
     * either way, and the race between clicking cancel and the run completing is
     * one the user cannot avoid.
     *
     * @param sessionId the council session to stop
     * @return the session's status at the moment of the request
     * @throws NoSuchElementException if no such session exists
     */
    public CouncilStatus cancelRun(String sessionId) {
        CouncilSession session = getSession(sessionId);
        runRegistry.cancel(sessionId);
        return session.status();
    }

    /** Persist a new session. */
    public CouncilSession createSession(CouncilSession session) {
        sessionStore.save(session);
        return session;
    }

    /**
     * Run the full protocol for the session identified by {@code sessionId}.
     *
     * @return The completed {@link CouncilContext}.
     * @throws NoSuchElementException if no session or profile is found.
     */
    public CouncilContext runCouncil(String sessionId) {
        CouncilSession session = sessionStore.findById(sessionId)
                                             .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        CouncilPolicyResolver.ResolvedCouncilPolicy resolved =
                policyResolver.resolve(session.profileId(), session.depthMode());
        CouncilPolicy policy = resolved.policy();

        session = session.withResolution(policy.id(), policy.protocolId())
                         .withStatus(CouncilStatus.RUNNING);
        sessionStore.save(session);

        CouncilContext ctx;
        try {
            ctx = orchestrator.run(session, resolved.profile(), policy, resolved.catalog());
        } catch (Exception ex) {
            CouncilSession failed = session.withStatus(CouncilStatus.FAILED)
                                           .withFailureReason(ex.getMessage());
            sessionStore.save(failed);
            throw ex;
        }

        // A cancelled run is not a failure and not a success. It gets its own
        // status so a partial answer produced before the stop is not presented
        // as a council that finished its protocol.
        CouncilStatus finalStatus = ctx.isCancelled()
                                    ? CouncilStatus.CANCELLED
                                    : ctx.isTerminal()
                                      ? (ctx.synthesisResult().isPresent() ? CouncilStatus.PARTIAL : CouncilStatus.FAILED)
                                      : CouncilStatus.COMPLETED;
        String failureReason = ctx.isCancelled()
                               ? CANCELLED_BY_USER
                               : ctx.failureMessage().orElse(null);
        CouncilSession completed = session.withStatus(finalStatus)
                                          .withFinalAnswer(ctx.synthesisResult().orElse(null))
                                          .withFailureReason(failureReason);
        sessionStore.save(completed);
        return ctx;
    }

    /** Retrieve a session by ID. */
    public CouncilSession getSession(String sessionId) {
        return sessionStore.findById(sessionId)
                           .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
    }

    /** Mark a created session as failed before orchestration starts. */
    public CouncilSession failSession(String sessionId, String reason) {
        CouncilSession session = getSession(sessionId);
        CouncilSession failed = session.withStatus(CouncilStatus.FAILED)
                                       .withFailureReason(reason);
        sessionStore.save(failed);
        return failed;
    }

}
