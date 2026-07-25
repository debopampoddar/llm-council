package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.ConfigSaveResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.user.UserConfigCodec;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves the user configuration overlay.
 *
 * <p>The only class in this feature that writes anything. Reading, validating,
 * and previewing live on {@link ConfigDraftService}, which touches nothing —
 * keeping the two apart is what lets a UI validate on every keystroke.
 *
 * <p>Three properties matter here, and all three are about the moment something
 * goes wrong:
 *
 * <ul>
 *   <li><b>Nothing invalid is written.</b> A configuration with errors is refused
 *       whole rather than saved with the bad entities stripped out. Silently
 *       saving a subset would mean a user's file no longer says what they
 *       wrote.</li>
 *   <li><b>The write is atomic.</b> Content goes to a temporary file in the same
 *       directory and is moved into place. A crash mid-write leaves the previous
 *       file intact, not a truncated one — and a truncated overlay is worse than
 *       no overlay, because it parses.</li>
 *   <li><b>The previous version survives.</b> One {@code .bak}, replaced each
 *       time. This is a file a user may have hand-written; a UI that overwrote it
 *       with no way back would be a bad trade for the convenience.</li>
 * </ul>
 *
 * <p>Saving does not apply. The catalog is built once at boot and every in-flight
 * run pins its own snapshot, so the change waits for a restart and the response
 * says so plainly rather than implying a live reload that does not exist yet.
 */
@Service
public class ConfigWriteService {

    private static final Logger log = LoggerFactory.getLogger(ConfigWriteService.class);

    /** Suffix for the single retained previous version. */
    static final String BACKUP_SUFFIX = ".bak";

    private final ConfigDraftService draftService;
    private final UserConfigLoader loader;
    private final UserConfigCodec codec;

    /**
     * @param draftService validates the submitted configuration
     * @param loader       resolves where the overlay lives
     * @param codec        renders the document as the YAML to be written
     */
    public ConfigWriteService(ConfigDraftService draftService,
                              UserConfigLoader loader,
                              UserConfigCodec codec) {
        this.draftService = draftService;
        this.loader = loader;
        this.codec = codec;
    }

    /**
     * Validate a configuration and, if it is clean, save it.
     *
     * @param document the configuration to save
     * @return what happened, including why nothing was written when nothing was
     */
    public ConfigSaveResponse save(UserConfigDocument document) {
        ValidationReportResponse validation = draftService.validate(document);
        Path path = loader.resolvePath();

        if (!validation.valid()) {
            // Refused whole. Writing the entities that happened to pass would
            // leave the user's file saying something they did not write.
            return ConfigSaveResponse.refused(display(path), validation);
        }
        if (path == null) {
            return ConfigSaveResponse.refused(null, withIssue(validation, new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null,
                    "The configured overlay location is not a usable path, so there is nowhere "
                    + "to save to.",
                    "Correct council.userConfigPath, or unset it to use the default location.")));
        }

        // Rendered before anything on disk is touched: the render is scanned for
        // credential material and can still refuse the whole operation.
        String yaml = codec.writeYaml(document);

        try {
            Path backup = writeAtomically(path, yaml);
            log.info("User configuration saved to {} ({} warnings). A restart is required to apply it.",
                     path, validation.warningCount());
            return ConfigSaveResponse.written(display(path), display(backup), validation);
        } catch (IOException ex) {
            return ConfigSaveResponse.refused(display(path), withIssue(validation, new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null,
                    "The configuration is valid but could not be written to " + path + ": "
                    + ex.getMessage(),
                    "Check the directory exists and is writable. Your previous configuration is "
                    + "unchanged.")));
        }
    }

    /**
     * Render the current overlay as YAML, for download.
     *
     * <p>Re-rendered from the parsed document rather than streamed from disk. The
     * export therefore contains exactly what the schema permits and nothing else:
     * whatever a hand-edited file might have picked up cannot ride along into a
     * document meant to be shared.
     *
     * @return the overlay as YAML text
     */
    public String export() {
        return codec.writeYaml(draftService.draft());
    }

    /**
     * Write content into place without ever leaving a partial file there.
     *
     * @param path    the destination
     * @param content what to write
     * @return the backup that was kept, or null when there was no previous file
     * @throws IOException when the directory or the file cannot be written
     */
    private Path writeAtomically(Path path, String content) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        Files.createDirectories(directory);

        // Same directory as the destination: ATOMIC_MOVE is only guaranteed
        // within one filesystem, and the system temp directory is often another.
        Path temporary = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
        Path backup = null;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);

            if (Files.exists(path)) {
                // Copied before the move, so the backup holds the version being
                // replaced rather than the one replacing it.
                backup = path.resolveSibling(path.getFileName() + BACKUP_SUFFIX);
                Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                           StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // Some filesystems cannot promise it. Say so rather than
                // pretending the guarantee held.
                log.warn("Atomic move is not supported at {}; the overlay was replaced without it. "
                         + "The previous version is at {}.", directory, backup);
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return backup;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private ValidationReportResponse withIssue(ValidationReportResponse report, ConfigIssue issue) {
        List<ConfigIssue> issues = new ArrayList<>(report.issues());
        issues.add(issue);
        return ValidationReportResponse.of(issues, report.integrityReduced());
    }

    private String display(Path path) {
        return path == null ? null : path.toAbsolutePath().toString();
    }
}
