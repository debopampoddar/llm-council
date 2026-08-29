package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import com.debopam.llmcouncil.api.dto.ConfigImportResponse;
import com.debopam.llmcouncil.api.dto.ConfigSaveResponse;
import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.application.ConfigDraftService;
import com.debopam.llmcouncil.application.ConfigSchemaService;
import com.debopam.llmcouncil.application.ConfigWriteService;
import com.debopam.llmcouncil.config.user.UserConfigCodec;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The configuration write path.
 *
 * <p>Edits the same user overlay file Phase 1 reads at startup, through
 * something other than a text editor. Everything here is about <em>one</em>
 * file, and the split of responsibilities is deliberate:
 *
 * <ul>
 *   <li>{@code GET /schema} — what may be written, generated from the
 *       validator's own bounds.</li>
 *   <li>{@code POST /validate} and {@code POST /preview} — pure functions over
 *       the shipped catalog. They never touch disk.</li>
 *   <li>{@code PUT /draft} — the only write, and only when validation found no
 *       errors.</li>
 * </ul>
 *
 * <p>Applying a saved overlay still requires a restart. Nothing here pretends
 * otherwise: a live apply that silently half-worked would be worse than an
 * honest banner.
 *
 * <p>This endpoint never accepts a {@code protocolId} as a way to run something.
 * A user may <em>define</em> a profile here; callers still select a profile and a
 * depth, so quorum, validation, and cost controls cannot be bypassed by naming a
 * protocol directly.
 */
@RestController
@RequestMapping("/api/council/config")
public class ConfigController {

    /** Filename offered when the configuration is downloaded. */
    private static final String EXPORT_FILE_NAME = "council-user.yml";

    private final ConfigSchemaService schemaService;
    private final ConfigDraftService draftService;
    private final ConfigWriteService writeService;
    private final UserConfigCodec codec;

    /**
     * @param schemaService generates the overlay schema
     * @param draftService  reads, validates, and previews configuration
     * @param writeService  saves and exports configuration
     * @param codec         parses request bodies, refusing credentials first
     */
    public ConfigController(ConfigSchemaService schemaService,
                            ConfigDraftService draftService,
                            ConfigWriteService writeService,
                            UserConfigCodec codec) {
        this.schemaService = schemaService;
        this.draftService = draftService;
        this.writeService = writeService;
        this.codec = codec;
    }

    /**
     * Read the configuration overlay as it currently exists on disk.
     *
     * <p>An installation running shipped configuration has no overlay file, and
     * the empty document returned here is the accurate description of that — not
     * a 404, which would suggest something is missing.
     *
     * @return 200 OK with the current draft
     */
    @GetMapping("/draft")
    public ResponseEntity<UserConfigDocument> draft() {
        return ResponseEntity.ok(draftService.draft());
    }

    /**
     * Check a proposed configuration. Writes nothing.
     *
     * <p>The body is taken as text and scanned before it is parsed, in that
     * order, for the same reason the file loader does it: a credential must be
     * refused even when the rest of the document is malformed, and must not reach
     * a parser's exception message on its way to being refused.
     *
     * @param body the proposed overlay as JSON
     * @return 200 OK with every issue found, whether or not there are any
     */
    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationReportResponse> validate(@RequestBody(required = false) String body) {
        return ResponseEntity.ok(draftService.validate(codec.readJson(body)));
    }

    /**
     * Show what a proposed configuration would change. Writes nothing.
     *
     * @param body the proposed overlay as JSON
     * @return 200 OK with the entity diff and the resulting profile list
     */
    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CatalogDiffResponse> preview(@RequestBody(required = false) String body) {
        return ResponseEntity.ok(draftService.preview(codec.readJson(body)));
    }

    /**
     * Save a proposed configuration, if it is clean.
     *
     * <p>Returns 200 whether or not anything was written, with {@code written}
     * saying which. A refused save is a considered answer to a well-formed
     * question — the same answer {@code POST /validate} would have given — and
     * reporting it as an HTTP error would make a caller handle two shapes for one
     * outcome. Only a body that cannot be read at all is a 400.
     *
     * <p>The saved configuration takes effect at the next restart, which is what
     * {@code restartRequired} says. Nothing here swaps the running catalog.
     *
     * @param body the configuration to save, as JSON
     * @return 200 OK with the outcome, written or refused
     */
    @PutMapping(value = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfigSaveResponse> saveDraft(@RequestBody(required = false) String body) {
        return ResponseEntity.ok(writeService.save(codec.readJson(body)));
    }

    /**
     * Download the current configuration as YAML.
     *
     * <p>Re-rendered from the parsed document rather than streamed from disk, so
     * an export carries exactly what the schema permits — which matters, because
     * an export is the artefact people send each other.
     *
     * @return 200 OK with a YAML attachment
     */
    @GetMapping(value = "/export", produces = "application/yaml")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                             .header("Content-Disposition",
                                     "attachment; filename=\"" + EXPORT_FILE_NAME + "\"")
                             .body(writeService.export());
    }

    /**
     * Check a configuration someone else wrote. Writes nothing.
     *
     * <p>Deliberately separate from saving. An imported configuration is exactly
     * the case where a user should see what they are taking on — which profiles it
     * redefines, whether it weakens a guarantee — before it replaces their own.
     *
     * @param body the configuration as YAML
     * @return 200 OK with the parsed document and what validating it found
     */
    @PostMapping(value = "/import", consumes = {"application/yaml", "text/yaml", "text/plain"})
    public ResponseEntity<ConfigImportResponse> importConfig(@RequestBody(required = false) String body) {
        UserConfigDocument document = codec.readYaml(body);
        return ResponseEntity.ok(new ConfigImportResponse(document, draftService.validate(document)));
    }

    /**
     * Describe what a user configuration overlay may contain.
     *
     * <p>Generated from the validator's clamp table, not written by hand. A UI
     * that stated its own ranges would drift from the server's within a release.
     *
     * @return 200 OK with the schema
     */
    @GetMapping("/schema")
    public ResponseEntity<ConfigSchemaResponse> schema() {
        return ResponseEntity.ok(schemaService.schema());
    }
}
