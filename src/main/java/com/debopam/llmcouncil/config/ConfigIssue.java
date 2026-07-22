package com.debopam.llmcouncil.config;

/**
 * A single problem found while building the {@link CouncilCatalog}.
 *
 * <p>Built-in configuration is validated fail-fast and therefore never produces
 * issues — a broken built-in throws at boot. Issues exist for the user
 * configuration overlay, which is validated fail-soft: a bad entity is dropped
 * and reported here rather than preventing startup.
 *
 * @param severity    whether the offending entity was dropped or merely flagged
 * @param entityKey   the entity the issue belongs to, as {@code type:id}
 *                    (for example {@code policy:local-balanced}); never null
 * @param field       the offending field, optionally indexed
 *                    (for example {@code memberModelIds[2]}); null when the
 *                    issue applies to the entity as a whole
 * @param message     human-readable description of what is wrong
 * @param remediation concrete next step the user can take, or null when none
 *                    applies (for example {@code Run: ollama pull mistral:7b})
 */
public record ConfigIssue(
        Severity severity,
        String entityKey,
        String field,
        String message,
        String remediation
) {

    /** How seriously a {@link ConfigIssue} affects the resulting catalog. */
    public enum Severity {
        /** The offending entity was rejected and is absent from the catalog. */
        ERROR,

        /** The entity is present and usable, but something is worth knowing. */
        WARNING
    }

    /**
     * Create an entity-scoped error with no specific field or remediation.
     *
     * @param entityKey the entity the issue belongs to, as {@code type:id}
     * @param message   human-readable description of what is wrong
     * @return an {@link Severity#ERROR} issue
     */
    public static ConfigIssue error(String entityKey, String message) {
        return new ConfigIssue(Severity.ERROR, entityKey, null, message, null);
    }

    /**
     * Create an entity-scoped warning with no specific field or remediation.
     *
     * @param entityKey the entity the issue belongs to, as {@code type:id}
     * @param message   human-readable description of what is worth knowing
     * @return a {@link Severity#WARNING} issue
     */
    public static ConfigIssue warning(String entityKey, String message) {
        return new ConfigIssue(Severity.WARNING, entityKey, null, message, null);
    }
}
