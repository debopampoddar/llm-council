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

    private final ProtocolOrchestrator orchestrator;
    private final SessionStore sessionStore;
    private final CouncilPolicyResolver policyResolver;

    public CouncilService(ProtocolOrchestrator orchestrator,
                          SessionStore sessionStore,
                          CouncilPolicyResolver policyResolver) {
        this.orchestrator = orchestrator;
        this.sessionStore = sessionStore;
        this.policyResolver = policyResolver;
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
            ctx = orchestrator.run(session, resolved.profile(), policy);
        } catch (Exception ex) {
            CouncilSession failed = session.withStatus(CouncilStatus.FAILED)
                                           .withFailureReason(ex.getMessage());
            sessionStore.save(failed);
            throw ex;
        }

        CouncilStatus finalStatus = ctx.isTerminal()
                                    ? (ctx.synthesisResult().isPresent() ? CouncilStatus.PARTIAL : CouncilStatus.FAILED)
                                    : CouncilStatus.COMPLETED;
        CouncilSession completed = session.withStatus(finalStatus)
                                          .withFinalAnswer(ctx.synthesisResult().orElse(null))
                                          .withFailureReason(ctx.failureMessage().orElse(null));
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
