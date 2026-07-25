package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.advisor.AdvisorEnvironment;
import com.debopam.llmcouncil.advisor.AdvisorRequestException;
import com.debopam.llmcouncil.advisor.AdvisorService;
import com.debopam.llmcouncil.advisor.ExtractionOutcome;
import com.debopam.llmcouncil.advisor.StoredProposal;
import com.debopam.llmcouncil.advisor.SynthesisResult;
import com.debopam.llmcouncil.api.dto.AdvisorEnvironmentResponse;
import com.debopam.llmcouncil.api.dto.AdvisorRequests.AdvisorError;
import com.debopam.llmcouncil.api.dto.AdvisorRequests.ExtractRequest;
import com.debopam.llmcouncil.api.dto.AdvisorRequests.SaveProposalRequest;
import com.debopam.llmcouncil.api.dto.AdvisorRequests.SynthesizeRequest;
import com.debopam.llmcouncil.api.dto.AdvisorRequests.SynthesizeResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.config.user.UserConfigDocumentException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The requirement advisor: describe a council, get a validated configuration.
 *
 * <p>A thin mapper over {@link AdvisorService}. Every rule lives there, not
 * here — including the two that look like web concerns and are not. The
 * acknowledgement before a description leaves the machine, and the allowlist a
 * submitted model id is checked against, would both be decorations if they lived
 * in a controller: this endpoint is reachable without the wizard, and a
 * command-line caller would never see a dialog at all.
 *
 * <p>Applying goes through {@code PUT /api/council/config/draft}, the write path
 * that already exists. Nothing here writes configuration. {@code PUT /proposal}
 * writes a proposal, which is a different file that startup never reads.
 *
 * <p>No endpoint here accepts a protocol id, and none returns one that could be
 * run. A user defines a profile; they do not bypass one.
 */
@RestController
@RequestMapping("/api/council/advisor")
public class AdvisorController {

    private final AdvisorService advisor;

    /**
     * @param advisor the advisor, as calls rather than as endpoints
     */
    public AdvisorController(AdvisorService advisor) {
        this.advisor = advisor;
    }

    /**
     * Report what this machine can actually run.
     *
     * <p>Probes the local runtime on every call. That is the point of it: a
     * wizard asking what is installed wants the answer now, not the answer at
     * startup, because pulling a model is exactly what a user does between
     * opening the wizard and finishing it.
     *
     * @return 200 OK with installed models, provider states, and what to do when
     *         there is not enough to work with
     */
    @GetMapping("/environment")
    public ResponseEntity<AdvisorEnvironmentResponse> environment() {
        return ResponseEntity.ok(AdvisorEnvironmentResponse.from(advisor.environment()));
    }

    /**
     * Read a free-text description into a requirement.
     *
     * <p>Returns 200 even when extraction failed. A model that could not be
     * reached, or that did not answer in the required shape, is a normal outcome
     * with a defined next step — {@code fallbackToForm} — rather than an error
     * the caller has to handle in a second shape. Only a refusal to use the named
     * model at all is a 400.
     *
     * @param request the description and which model to ask
     * @return 200 OK with what was understood, or with instructions to use the form
     */
    @PostMapping(value = "/extract", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExtractionOutcome> extract(@RequestBody ExtractRequest request) {
        return ResponseEntity.ok(advisor.extract(request.text(), request.modelId(),
                                                 request.acknowledged()));
    }

    /**
     * Turn a requirement into configuration. Writes nothing.
     *
     * <p>Returns 200 with a null {@code profileId} when no council could be
     * seated, carrying the reason and what to do about it. That is an answer to a
     * well-formed question, not a malformed request: the machine simply has
     * nothing to seat yet, and the caller renders the remediation.
     *
     * @param request the requirement to satisfy
     * @return 200 OK with the configuration, its rationale, and its checks
     */
    @PostMapping(value = "/synthesize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SynthesizeResponse> synthesize(@RequestBody SynthesizeRequest request) {
        // One probe, one snapshot. Synthesising against one environment and
        // previewing against another could describe a council neither would
        // produce.
        AdvisorEnvironment environment = advisor.environment();
        SynthesisResult result =
                advisor.synthesize(request.requirementOrDefaults(), environment, request.shadow());

        return ResponseEntity.ok(SynthesizeResponse.of(
                result, advisor.validate(result.document()), advisor.preview(result.document())));
    }

    /**
     * Read the council somebody saved without applying it.
     *
     * <p>Re-checked here rather than trusted: validation and the diff are
     * computed now, and staleness is reported separately from breakage. A
     * proposal saved three weeks ago may still validate perfectly while naming
     * models that have since been uninstalled.
     *
     * @return 200 OK with the proposal, or with {@code present: false}
     */
    @GetMapping("/proposal")
    public ResponseEntity<StoredProposal> proposal() {
        return ResponseEntity.ok(advisor.proposal());
    }

    /**
     * Save a council for later, applying nothing.
     *
     * <p>Takes a requirement rather than a document. The proposal store is
     * therefore unreachable by a hand-assembled configuration: what is saved is
     * always what this application derived from a stated intent.
     *
     * @param request the requirement to satisfy
     * @return 200 OK with the saved proposal, re-checked as though just read
     */
    @PutMapping(value = "/proposal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoredProposal> saveProposal(@RequestBody SaveProposalRequest request) {
        return ResponseEntity.ok(
                advisor.saveProposal(request.requirementOrDefaults(), request.shadow()));
    }

    /**
     * Discard the saved proposal.
     *
     * <p>204 whether or not one existed. Discarding something already gone is
     * the outcome the caller wanted, not a failure to report.
     *
     * @return 204 No Content
     */
    @DeleteMapping("/proposal")
    public ResponseEntity<Void> discardProposal() {
        advisor.discardProposal();
        return ResponseEntity.noContent().build();
    }

    /**
     * Refuse a request the advisor will not act on.
     *
     * @param ex the refusal, carrying what to do instead
     * @return 400 Bad Request with the reason and the remediation
     */
    @ExceptionHandler(AdvisorRequestException.class)
    public ResponseEntity<AdvisorError> handleRefusal(AdvisorRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                             .body(new AdvisorError(ex.getMessage(), ex.remediation()));
    }

    /**
     * Refuse a document that carries credential material.
     *
     * <p>Answers in the same shape configuration validation does, so a caller has
     * one thing to render. The issues name the offending field and never the
     * value.
     *
     * @param ex the refusal, carrying its reasons
     * @return 400 Bad Request with the reasons
     */
    @ExceptionHandler(UserConfigDocumentException.class)
    public ResponseEntity<ValidationReportResponse> handleUnwritable(UserConfigDocumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                             .body(ValidationReportResponse.of(ex.issues(), false));
    }
}
