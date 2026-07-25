package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse;
import com.debopam.llmcouncil.application.ConfigSchemaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final ConfigSchemaService schemaService;

    /**
     * @param schemaService generates the overlay schema
     */
    public ConfigController(ConfigSchemaService schemaService) {
        this.schemaService = schemaService;
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
