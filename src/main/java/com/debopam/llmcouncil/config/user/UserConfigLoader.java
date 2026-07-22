package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the optional user configuration overlay from disk.
 *
 * <p>Absence is the normal case and is not a problem. Anything else that goes
 * wrong — unreadable file, malformed YAML, unknown field — becomes a
 * {@link ConfigIssue} rather than an exception. Built-in configuration remains
 * fail-fast because it is the application's own contract; user configuration is
 * fail-soft because a typo in a file a user edits must not prevent the
 * application from starting.
 *
 * <p>The file is loaded explicitly rather than through Spring's
 * {@code spring.config.import}. Spring would union the model list, deep-merge
 * the maps, and leave no way to tell a user entry from a built-in one or to drop
 * a single bad entry — all three of which the overlay depends on.
 */
@Component
public class UserConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(UserConfigLoader.class);

    /** Default overlay location beneath the council home directory. */
    static final String DEFAULT_FILE_NAME = "council-user.yml";

    private final ObjectMapper yamlMapper;
    private final SecretScanner secretScanner;
    private final String configuredPath;
    private final String councilHome;

    /**
     * @param secretScanner  scanner run before parsing
     * @param configuredPath explicit overlay path, or blank to use the default
     * @param councilHome    council home directory holding the default overlay
     */
    public UserConfigLoader(SecretScanner secretScanner,
                            @Value("${council.userConfigPath:}") String configuredPath,
                            @Value("${council.home:${user.home}/.llm-council}") String councilHome) {
        this.secretScanner = secretScanner;
        this.configuredPath = configuredPath;
        this.councilHome = councilHome;
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                // Unknown fields are errors. A silently ignored typo is
                // indistinguishable from a setting that took effect.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
    }

    /**
     * Load and parse the overlay.
     *
     * @return the parsed document plus any issues found; the document is empty
     *         when no file exists or when the file could not be used at all
     */
    public LoadResult load() {
        Path path = resolvePath();
        if (path == null) {
            return new LoadResult(UserConfigDocument.empty(), List.of(), null);
        }
        if (!Files.isRegularFile(path)) {
            log.info("No user configuration overlay at {}; using built-in configuration only", path);
            return new LoadResult(UserConfigDocument.empty(), List.of(), path);
        }

        String raw;
        try {
            raw = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return new LoadResult(UserConfigDocument.empty(), List.of(new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null,
                    "Could not read user configuration at " + path + ": " + ex.getMessage(),
                    "Check the file exists and is readable, or unset council.userConfigPath.")), path);
        }

        // Scan before parsing: a credential must be refused even if the rest of
        // the file is malformed, and must never reach a log line.
        List<ConfigIssue> issues = new ArrayList<>(secretScanner.scan(raw));
        if (!issues.isEmpty()) {
            return new LoadResult(UserConfigDocument.empty(), issues, path);
        }

        if (raw.isBlank()) {
            return new LoadResult(UserConfigDocument.empty(), List.of(), path);
        }

        try {
            UserConfigDocument document = yamlMapper.readValue(raw, UserConfigDocument.class);
            if (document == null) {
                return new LoadResult(UserConfigDocument.empty(), List.of(), path);
            }
            if (document.version() != null && document.version() != UserConfigDocument.SUPPORTED_VERSION) {
                issues.add(new ConfigIssue(
                        ConfigIssue.Severity.ERROR, "file", "version",
                        "User configuration declares version " + document.version()
                        + " but this application understands version "
                        + UserConfigDocument.SUPPORTED_VERSION + ".",
                        "Set version: " + UserConfigDocument.SUPPORTED_VERSION + "."));
                return new LoadResult(UserConfigDocument.empty(), issues, path);
            }
            return new LoadResult(document, issues, path);
        } catch (Exception ex) {
            issues.add(new ConfigIssue(
                    ConfigIssue.Severity.ERROR, "file", null,
                    "User configuration at " + path + " could not be parsed: " + rootMessage(ex),
                    "Fix the reported field or remove it. Built-in configuration is being used "
                    + "in the meantime, so the application is running without your overrides."));
            return new LoadResult(UserConfigDocument.empty(), issues, path);
        }
    }

    /**
     * Resolve the overlay location.
     *
     * @return the path to read, or null when the configured path is unusable
     */
    Path resolvePath() {
        try {
            if (configuredPath != null && !configuredPath.isBlank()) {
                return Path.of(configuredPath.trim());
            }
            return Path.of(councilHome, DEFAULT_FILE_NAME);
        } catch (InvalidPathException ex) {
            log.warn("Configured user config path is not a valid path: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Extract the most specific message from a parse failure.
     *
     * <p>Jackson nests the useful detail — which field, which line — inside
     * wrapper exceptions whose own messages are generic.
     */
    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message.split("\n")[0];
    }

    /**
     * The outcome of loading the overlay.
     *
     * @param document the parsed document, empty when unusable
     * @param issues   problems found while loading
     * @param path     the file that was consulted, or null when none
     */
    public record LoadResult(UserConfigDocument document, List<ConfigIssue> issues, Path path) {

        /** @return {@code true} when an error prevented the overlay being used */
        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == ConfigIssue.Severity.ERROR);
        }
    }
}
