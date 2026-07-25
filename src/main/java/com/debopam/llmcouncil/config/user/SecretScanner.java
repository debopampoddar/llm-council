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
    private static final String CREDENTIAL_NAMES =
            "api[_-]?key|apikey|secret[_-]?key|secret|password|passwd|pwd"
            + "|credential[s]?|auth[_-]?token|access[_-]?token|refresh[_-]?token"
            + "|bearer|private[_-]?key|client[_-]?secret|session[_-]?key";

    private static final Pattern CREDENTIAL_KEY = Pattern.compile(
            "(?im)^\\s*-?\\s*\"?(" + CREDENTIAL_NAMES + ")\"?\\s*:");

    /**
     * The same names, matched against a field name on its own.
     *
     * <p>Needed because {@link #CREDENTIAL_KEY} anchors at the start of a line,
     * which is right for the YAML file on disk and wrong for a JSON request body
     * where every field can sit on one line. Whole-match, so legitimate fields
     * containing the same substrings — {@code defaultOutputTokens},
     * {@code contextWindowTokens} — are not flagged.
     */
    private static final Pattern CREDENTIAL_FIELD_NAME =
            Pattern.compile("(?i)^(" + CREDENTIAL_NAMES + ")$");

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

        issues.addAll(scanValues(rawYaml));
        return issues;
    }

    /**
     * Scan text for values shaped like provider credentials, ignoring field names.
     *
     * <p>Separate from {@link #scan} so a caller that already knows its field
     * names — a parsed request body, say — can check them precisely with
     * {@link #isCredentialFieldName} and still catch a key smuggled into a field
     * that legitimately accepts free text.
     *
     * @param text the document as written
     * @return one issue per recognisable credential shape, empty when clean
     */
    public List<ConfigIssue> scanValues(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ConfigIssue> issues = new ArrayList<>();
        for (Pattern valuePattern : CREDENTIAL_VALUES) {
            Matcher valueMatcher = valuePattern.matcher(text);
            if (valueMatcher.find()) {
                issues.add(new ConfigIssue(
                        ConfigIssue.Severity.ERROR,
                        "file",
                        null,
                        "User configuration contains what looks like a provider API key at line "
                        + lineOf(text, valueMatcher.start())
                        + ". The value has not been logged.",
                        "Remove it and set the provider's environment variable instead. "
                        + "Treat the key as compromised and rotate it: it has been written to disk "
                        + "in plain text and may be in your shell history or editor backups."));
            }
        }
        return issues;
    }

    /**
     * Decide whether a field name is one credentials are written under.
     *
     * @param name the field name, without quotes or punctuation
     * @return {@code true} when the name must never appear in configuration
     */
    public boolean isCredentialFieldName(String name) {
        return name != null && CREDENTIAL_FIELD_NAME.matcher(name).matches();
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
