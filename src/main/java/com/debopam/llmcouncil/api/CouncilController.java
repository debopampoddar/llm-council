// CouncilController.java
package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.CatalogResponse;
import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.api.dto.CreateSessionRequest;
import com.debopam.llmcouncil.api.dto.ProfileHealthResponse;
import com.debopam.llmcouncil.api.dto.SessionResponse;
import com.debopam.llmcouncil.application.CatalogService;
import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.application.ProfileHealthService;
import com.debopam.llmcouncil.application.RunResultStore;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * REST API for the LLM Council.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/council/sessions         – Create a new session</li>
 *   <li>POST /api/council/sessions/{id}/run – Run the council protocol</li>
 *   <li>GET  /api/council/sessions/{id}    – Retrieve session status</li>
 *   <li>GET  /api/council/sessions/{id}/result – Trust signals for a finished run</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/council")
public class CouncilController {

    private final CouncilService councilService;
    private final ProfileHealthService profileHealthService;
    private final EventPublisher eventPublisher;
    private final ArtifactStore artifactStore;
    private final CatalogService catalogService;
    private final RunResultStore runResultStore;

    public CouncilController(CouncilService councilService,
                             ProfileHealthService profileHealthService,
                             EventPublisher eventPublisher,
                             ArtifactStore artifactStore,
                             CatalogService catalogService,
                             RunResultStore runResultStore) {
        this.councilService = councilService;
        this.profileHealthService = profileHealthService;
        this.eventPublisher = eventPublisher;
        this.artifactStore = artifactStore;
        this.catalogService = catalogService;
        this.runResultStore = runResultStore;
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
        CouncilRunResponse result = CouncilRunResponse.from(sessionId, ctx);
        runResultStore.save(sessionId, result);
        return ResponseEntity.ok(result);
    }

    /**
     * Read the trust signals for a finished run.
     *
     * <p>The synchronous run endpoint returns this same shape directly, but the
     * chat path cannot: it responds as soon as the run is submitted. Without
     * this endpoint a chat client would have to reassemble sycophancy warnings,
     * excluded models, scores, the validation verdict and its independence tier
     * from three separate sources — the event stream, the catalog and the
     * artifact files — duplicating logic that already exists here in tested Java.
     *
     * @param sessionId the council session to read
     * @return 200 OK with the run result, or 404 while the run is still going
     */
    @GetMapping("/sessions/{sessionId}/result")
    public ResponseEntity<CouncilRunResponse> getRunResult(@PathVariable("sessionId") String sessionId) {
        councilService.getSession(sessionId);
        return runResultStore.findById(sessionId)
                             .map(ResponseEntity::ok)
                             .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Read the active configuration snapshot.
     *
     * <p>All configuration projections are served from this one resource rather
     * than one endpoint per entity type. Six separate reads could straddle a
     * configuration reload and return sections that disagree with each other;
     * one read returns one internally consistent snapshot, identified by its
     * {@code generation}.
     *
     * @param include         comma-separated sections to return; omitted returns all
     * @param includeTestOnly whether to include test-only profiles and their models
     * @return 200 OK with the requested sections; sections not requested are omitted
     */
    @GetMapping("/catalog")
    public ResponseEntity<CatalogResponse> catalog(
            @RequestParam(name = "include", required = false) Set<String> include,
            @RequestParam(name = "includeTestOnly", defaultValue = "false") boolean includeTestOnly) {
        return ResponseEntity.ok(catalogService.catalog(include, includeTestOnly));
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

    /**
     * Return one artifact's content.
     *
     * <p>The path is resolved and canonicalised against the session directory by
     * {@link ArtifactStore}; anything escaping it is rejected as a bad request.
     *
     * @param sessionId the session the artifact belongs to
     * @param request   used to recover the wildcard portion of the path
     * @return 200 OK with the artifact content, or 404 when it does not exist
     */
    @GetMapping("/sessions/{sessionId}/artifacts/**")
    public ResponseEntity<String> getArtifactContent(@PathVariable("sessionId") String sessionId,
                                                     HttpServletRequest request) {
        councilService.getSession(sessionId);
        String prefix = "/api/council/sessions/" + sessionId + "/artifacts/";
        String path = request.getRequestURI();
        int index = path.indexOf(prefix);
        String relativePath = index < 0 ? "" : path.substring(index + prefix.length());
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("Artifact path must not be blank");
        }
        Optional<String> content = artifactStore.readArtifact(sessionId, relativePath);
        return content.map(body -> ResponseEntity.ok()
                                                 .contentType(mediaTypeFor(relativePath))
                                                 .body(body))
                      .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MediaType mediaTypeFor(String relativePath) {
        return relativePath.endsWith(".json")
               ? MediaType.APPLICATION_JSON
               : MediaType.TEXT_PLAIN;
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
