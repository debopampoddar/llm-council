package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects credential material in the user configuration overlay.
 *
 * <p>The application never accepts, stores, or echoes API keys — they live in
 * the environment. This scanner enforces that at the file boundary, before
 * anything is parsed or logged, so a key pasted into the overlay is refused
 * rather than persisted, written to an export, or returned by the catalog API.
 *
 * <p>Strict binding already rejects unknown fields, so a {@code apiKey:} entry
 * would fail anyway. This runs first to produce an error a user can act on
 * instead of a Jackson type-mismatch, and to catch keys hidden in the values of
 * fields that legitimately accept free text.
 *
 * <p><b>No match is ever echoed.</b> Reporting the offending value would move
 * the secret from a file the user controls into logs and API responses, which is
 * the outcome this class exists to prevent. Errors name the line and the field.
 */
@Component
public class SecretScanner {

    /**
     * Keys that indicate credential material.
     *
     * <p>Anchored at YAML key position and matched whole, so legitimate fields
     * containing the same substrings — {@code defaultOutputTokens},
     * {@code contextWindowTokens} — are not flagged.
     */
    private static final Pattern CREDENTIAL_KEY = Pattern.compile(
            "(?im)^\\s*-?\\s*\"?("
            + "api[_-]?key|apikey|secret[_-]?key|secret|password|passwd|pwd"
            + "|credential[s]?|auth[_-]?token|access[_-]?token|refresh[_-]?token"
            + "|bearer|private[_-]?key|client[_-]?secret|session[_-]?key"
            + ")\"?\\s*:");

    /** Value shapes that are recognisably provider credentials. */
    private static final List<Pattern> CREDENTIAL_VALUES = List.of(
            Pattern.compile("sk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{20,}"),
            Pattern.compile("gho_[A-Za-z0-9]{20,}"),
            Pattern.compile("AIza[A-Za-z0-9_-]{30,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));

    /**
     * Scan raw overlay text for credential material.
     *
     * @param rawYaml the file contents as written
     * @return one issue per offence, empty when the file is clean
     */
    public List<ConfigIssue> scan(String rawYaml) {
        if (rawYaml == null || rawYaml.isBlank()) {
            return List.of();
        }
        List<ConfigIssue> issues = new ArrayList<>();

        Matcher keyMatcher = CREDENTIAL_KEY.matcher(rawYaml);
        while (keyMatcher.find()) {
            String field = keyMatcher.group(1);
            issues.add(new ConfigIssue(
                    ConfigIssue.Severity.ERROR,
                    "file",
                    field,
                    "User configuration contains a credential field '" + field + "' at line "
                    + lineOf(rawYaml, keyMatcher.start())
                    + ". This application never reads credentials from configuration files.",
                    "Remove the field and set the provider's environment variable instead. "
                    + "Provider status and the variable to set are shown by "
                    + "GET /api/council/catalog?include=providers."));
        }

        for (Pattern valuePattern : CREDENTIAL_VALUES) {
            Matcher valueMatcher = valuePattern.matcher(rawYaml);
            if (valueMatcher.find()) {
                issues.add(new ConfigIssue(
                        ConfigIssue.Severity.ERROR,
                        "file",
                        null,
                        "User configuration contains what looks like a provider API key at line "
                        + lineOf(rawYaml, valueMatcher.start())
                        + ". The value has not been logged.",
                        "Remove it and set the provider's environment variable instead. "
                        + "Treat the key as compromised and rotate it: it has been written to disk "
                        + "in plain text and may be in your shell history or editor backups."));
            }
        }
        return issues;
    }

    private int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
