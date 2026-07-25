package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.config.user.UserConfigCodec;
import com.debopam.llmcouncil.config.user.UserConfigLoader;
import com.debopam.llmcouncil.persistence.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Keeps the unapplied proposal in a file next to the configuration overlay.
 *
 * <p>A file rather than a database row, for two reasons. A proposal is a
 * configuration document, and configuration-shaped things in this application
 * live on the filesystem — the overlay itself does, and so does its backup.
 * And {@code council.persistence.type} defaults to {@code memory}, which
 * creates no datasource at all: a default installation has no database for a
 * row to live in, and adding one so that a wizard can save a draft would be a
 * large price for a small feature.
 *
 * <p>It sits beside whatever {@code council.userConfigPath} resolves to, so a
 * user who moved their configuration finds their proposal in the same place
 * rather than in the default location they are not using.
 */
@Component
public class FileProposalStore implements ProposalStore {

    private static final Logger log = LoggerFactory.getLogger(FileProposalStore.class);

    /**
     * Filename for the saved proposal.
     *
     * <p>Deliberately not the overlay's name with a suffix that a shell glob
     * would catch: {@code UserConfigLoader} resolves one fixed filename, so
     * nothing about startup can reach this one by accident.
     */
    static final String PROPOSAL_FILE_NAME = "council-user.proposal.yml";

    private final UserConfigLoader loader;
    private final UserConfigCodec codec;
    private final AtomicFileWriter fileWriter;

    /**
     * @param loader     resolves where the configuration overlay lives
     * @param codec      renders and parses the proposal, scanning it both ways
     * @param fileWriter puts it in place without leaving a partial file
     */
    public FileProposalStore(UserConfigLoader loader, UserConfigCodec codec,
                             AtomicFileWriter fileWriter) {
        this.loader = loader;
        this.codec = codec;
        this.fileWriter = fileWriter;
    }

    @Override
    public Optional<ProposalEnvelope> load() {
        Path path = path();
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            ProposalEnvelope proposal = codec.readProposalYaml(raw, ProposalEnvelope.class);
            if (!proposal.valid()) {
                // Present, parseable, and not a proposal. Reporting it as absent
                // is right: there is nothing here a user can apply, and treating
                // a file that lost its marker as a proposal would defeat the
                // marker.
                log.warn("The file at {} is not a saved proposal and was ignored.", path);
                return Optional.empty();
            }
            return Optional.of(proposal);
        } catch (Exception ex) {
            // An unreadable proposal is not worth failing a request over: it is
            // a convenience, and the user can always run the advisor again.
            log.warn("Could not read the saved proposal at {}: {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String save(ProposalEnvelope proposal) {
        Path path = path();
        if (path == null) {
            throw new AdvisorRequestException(
                    "There is nowhere to save a proposal: the configured configuration path is "
                    + "not usable.",
                    "Correct council.userConfigPath, or unset it to use the default location.");
        }
        // Rendered before anything on disk is touched, and scanned while it is
        // rendered: a credential smuggled through a free-text field must be
        // caught before it reaches a durable file.
        String yaml = codec.writeProposalYaml(proposal);
        try {
            // No backup. A discard that left a copy behind would not be a discard.
            fileWriter.write(path, yaml, false);
        } catch (IOException ex) {
            throw new AdvisorRequestException(
                    "The proposal could not be written to " + path + ": " + ex.getMessage(),
                    "Check the directory exists and is writable.");
        }
        log.info("Saved an unapplied council proposal to {}. Nothing has been applied.", path);
        return path.toAbsolutePath().toString();
    }

    @Override
    public boolean discard() {
        Path path = path();
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Could not discard the saved proposal at {}: {}", path, ex.getMessage());
            return false;
        }
    }

    @Override
    public String location() {
        Path path = path();
        return path == null ? null : path.toAbsolutePath().toString();
    }

    /**
     * Where the proposal sits: beside the configuration overlay.
     *
     * @return the proposal path, or null when the overlay location is unusable
     */
    private Path path() {
        Path overlay = loader.resolvePath();
        return overlay == null ? null : overlay.resolveSibling(PROPOSAL_FILE_NAME);
    }
}
