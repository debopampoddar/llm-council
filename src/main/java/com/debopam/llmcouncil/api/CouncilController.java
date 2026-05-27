package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.model.CouncilEventResponse;
import com.debopam.llmcouncil.model.CreateSessionRequest;
import com.debopam.llmcouncil.model.SessionResponse;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/council")
public class CouncilController {
    private final CouncilService councilService;
    private final EventPublisher eventPublisher;
    private final ArtifactStore artifactStore;

    public CouncilController(CouncilService councilService, EventPublisher eventPublisher, ArtifactStore artifactStore) {
        this.councilService = councilService;
        this.eventPublisher = eventPublisher;
        this.artifactStore = artifactStore;
    }

    @PostMapping("/sessions")
    public SessionResponse createSession(@Valid @RequestBody CreateSessionRequest request) {
        return councilService.createSession(request);
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@PathVariable UUID sessionId) {
        return councilService.getSession(sessionId);
    }

    @GetMapping(value = "/sessions/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID sessionId) {
        return eventPublisher.subscribe(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/events/history")
    public List<CouncilEventResponse> eventHistory(@PathVariable UUID sessionId) {
        return eventPublisher.history(sessionId).stream().map(this::toResponse).toList();
    }

    @GetMapping("/sessions/{sessionId}/artifacts")
    public List<String> artifacts(@PathVariable UUID sessionId) {
        return artifactStore.listArtifacts(sessionId);
    }

    private CouncilEventResponse toResponse(CouncilEvent event) {
        return new CouncilEventResponse(
                event.id(),
                event.sessionId(),
                event.occurredAt(),
                event.stage(),
                event.type(),
                event.modelId(),
                event.payload()
        );
    }
}
