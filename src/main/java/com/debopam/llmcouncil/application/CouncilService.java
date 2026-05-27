package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.config.CouncilProperties;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CreateSessionRequest;
import com.debopam.llmcouncil.model.SessionResponse;
import com.debopam.llmcouncil.orchestration.MockProtocolOrchestrator;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import com.debopam.llmcouncil.persistence.SessionStore;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class CouncilService {
    private final SessionStore sessionStore;
    private final ArtifactStore artifactStore;
    private final EventPublisher events;
    private final MockProtocolOrchestrator orchestrator;
    private final CouncilProperties properties;
    private final ExecutorService applicationTaskExecutor;

    public CouncilService(SessionStore sessionStore,
                          ArtifactStore artifactStore,
                          EventPublisher events,
                          MockProtocolOrchestrator orchestrator,
                          CouncilProperties properties,
                          ExecutorService applicationTaskExecutor) {
        this.sessionStore = sessionStore;
        this.artifactStore = artifactStore;
        this.events = events;
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.applicationTaskExecutor = applicationTaskExecutor;
    }

    public SessionResponse createSession(CreateSessionRequest request) {
        String profileId = request.profileId() == null || request.profileId().isBlank()
                ? properties.defaultProfileId()
                : request.profileId();
        CouncilSession session = CouncilSession.created(
                UUID.randomUUID(),
                request.question(),
                request.context(),
                profileId,
                DepthMode.parse(request.depthMode())
        );
        sessionStore.save(session);
        artifactStore.writeJson(session.id(), "request.json", request);
        events.publish(session.id(), "SESSION", "SESSION_CREATED", null, Map.of("profileId", profileId));
        applicationTaskExecutor.submit(() -> runSessionAsync(session));
        return toResponse(session);
    }

    public SessionResponse getSession(UUID sessionId) {
        return sessionStore.findById(sessionId)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Unknown council session " + sessionId));
    }

    private void runSessionAsync(CouncilSession createdSession) {
        CouncilSession running = sessionStore.save(createdSession.running());
        events.publish(running.id(), "SESSION", "SESSION_RUNNING", null, Map.of());
        try {
            String answer = orchestrator.run(running);
            CouncilSession completed = sessionStore.save(running.completed(answer));
            artifactStore.writeText(completed.id(), "final/answer.md", answer);
            artifactStore.writeJson(completed.id(), "events.json", events.history(completed.id()));
            events.publish(completed.id(), "SESSION", "SESSION_COMPLETED", null, Map.of());
        } catch (RuntimeException e) {
            CouncilSession failed = sessionStore.save(running.failed(e.getMessage()));
            artifactStore.writeJson(failed.id(), "events.json", events.history(failed.id()));
            events.publish(failed.id(), "SESSION", "SESSION_FAILED", null, Map.of("reason", e.getMessage()));
        }
    }

    private SessionResponse toResponse(CouncilSession session) {
        return new SessionResponse(
                session.id(),
                session.status(),
                session.profileId(),
                session.depthMode().name(),
                session.createdAt(),
                session.updatedAt(),
                session.finalAnswer(),
                session.failureReason()
        );
    }
}
