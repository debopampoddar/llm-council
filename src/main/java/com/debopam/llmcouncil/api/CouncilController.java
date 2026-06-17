// CouncilController.java
package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.api.dto.CreateSessionRequest;
import com.debopam.llmcouncil.api.dto.ProfileHealthResponse;
import com.debopam.llmcouncil.api.dto.SessionResponse;
import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.application.ProfileHealthService;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.List;
import java.util.UUID;

/**
 * REST API for the LLM Council.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/council/sessions         – Create a new session</li>
 *   <li>POST /api/council/sessions/{id}/run – Run the council protocol</li>
 *   <li>GET  /api/council/sessions/{id}    – Retrieve session status</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/council")
public class CouncilController {

    private final CouncilService councilService;
    private final ProfileHealthService profileHealthService;
    private final EventPublisher eventPublisher;
    private final ArtifactStore artifactStore;

    public CouncilController(CouncilService councilService,
                             ProfileHealthService profileHealthService,
                             EventPublisher eventPublisher,
                             ArtifactStore artifactStore) {
        this.councilService = councilService;
        this.profileHealthService = profileHealthService;
        this.eventPublisher = eventPublisher;
        this.artifactStore = artifactStore;
    }

    /**
     * Create a new council session.
     *
     * @param request Body containing the question, optional context, depth mode,
     *                and profile ID.
     * @return 201 Created with session details.
     */
    @PostMapping("/sessions")
    public ResponseEntity<SessionResponse> createSession(
            @RequestBody @Valid CreateSessionRequest request) {

        DepthMode depth = request.depthMode() != null ? request.depthMode() : DepthMode.BALANCED;
        String profileId = request.profileId() != null ? request.profileId() : "default";

        CouncilSession session = CouncilSession.create(
                UUID.randomUUID().toString(),
                request.question(),
                request.context(),
                depth, profileId);

        CouncilSession saved = councilService.createSession(session);
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(saved));
    }

    /**
     * Run the council protocol for an existing session.
     *
     * @param sessionId The session ID returned by createSession.
     * @return 200 OK with the synthesised answer and artifact counts.
     */
    @PostMapping("/sessions/{sessionId}/run")
    public ResponseEntity<CouncilRunResponse> runCouncil(@PathVariable("sessionId") String sessionId) {
        CouncilContext ctx = councilService.runCouncil(sessionId);
        return ResponseEntity.ok(CouncilRunResponse.from(sessionId, ctx));
    }

    /** Preflight health check for the models required by a profile/depth pair. */
    @GetMapping("/profiles/{profileId}/health")
    public ResponseEntity<ProfileHealthResponse> profileHealth(
            @PathVariable("profileId") String profileId,
            @RequestParam(name = "depthMode", required = false) DepthMode depthMode) {
        return ResponseEntity.ok(profileHealthService.health(profileId, depthMode));
    }

    /**
     * Retrieve the current status of a council session.
     *
     * @param sessionId The session ID.
     * @return 200 OK with session summary.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable("sessionId") String sessionId) {
        CouncilSession session = councilService.getSession(sessionId);
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    /** Return replayable events emitted for a session. */
    @GetMapping("/sessions/{sessionId}/events")
    public ResponseEntity<List<CouncilEvent>> getEvents(@PathVariable("sessionId") String sessionId) {
        councilService.getSession(sessionId);
        return ResponseEntity.ok(eventPublisher.history(sessionId));
    }

    /** Return artifact paths written for a session. */
    @GetMapping("/sessions/{sessionId}/artifacts")
    public ResponseEntity<List<String>> getArtifacts(@PathVariable("sessionId") String sessionId) {
        councilService.getSession(sessionId);
        return ResponseEntity.ok(artifactStore.listArtifacts(sessionId));
    }

    /** Handle unknown session IDs. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    /** Handle invalid profile/depth/policy combinations. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}
