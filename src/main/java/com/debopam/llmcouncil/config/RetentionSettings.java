package com.debopam.llmcouncil.config;

/**
 * Bounds on how much history the process keeps.
 *
 * <p>Every in-memory store was previously a plain {@code ConcurrentHashMap} that
 * only ever grew: sessions, chats, run results, and events accumulated for the
 * life of the process. Events are the worst of them at dozens per run, and run
 * results the largest, since each holds a full answer. A long-lived instance
 * ended in an {@code OutOfMemoryError} rather than in degraded service, and it
 * gave no warning on the way.
 *
 * <p>Carried on {@link CouncilRuntimeSettings} rather than read through
 * {@code @Value} at the point of use, for the reason recorded there: a value
 * read from {@code @Value} cannot be changed by the user overlay, so the
 * overlay's section would validate cleanly and then do nothing.
 *
 * @param maxSessions         how many entries any one store keeps before the
 *                            oldest are evicted
 * @param maxAgeDays          how long an entry may sit untouched before it is
 *                            evicted regardless of how few there are
 * @param maxEventsPerSession how many events one session may accumulate before
 *                            its oldest events are dropped
 */
public record RetentionSettings(
        int maxSessions,
        int maxAgeDays,
        int maxEventsPerSession
) {

    /** Shipped defaults, matching {@code application.yml}. */
    public static final RetentionSettings DEFAULTS = new RetentionSettings(500, 90, 2000);

    /**
     * Clamps values that would make the process unusable rather than merely
     * frugal.
     *
     * <p>Zero is the dangerous one: a cap of zero would evict every entry the
     * moment it was written, so a run would finish and immediately lose its own
     * result. The validator rejects out-of-range overlay values with an
     * explanation; this is the last line of defence for the built-in path.
     */
    public RetentionSettings {
        maxSessions = Math.max(1, maxSessions);
        maxAgeDays = Math.max(1, maxAgeDays);
        maxEventsPerSession = Math.max(1, maxEventsPerSession);
    }

    /**
     * Return a copy with any non-null override applied.
     *
     * <p>Null means "not mentioned", which keeps the existing value: setting
     * only {@code maxSessions} must not reset the other two to defaults.
     *
     * @param maxSessions         override, or null to keep the current value
     * @param maxAgeDays          override, or null to keep the current value
     * @param maxEventsPerSession override, or null to keep the current value
     * @return the merged settings
     */
    public RetentionSettings withOverrides(Integer maxSessions,
                                           Integer maxAgeDays,
                                           Integer maxEventsPerSession) {
        return new RetentionSettings(
                maxSessions != null ? maxSessions : this.maxSessions,
                maxAgeDays != null ? maxAgeDays : this.maxAgeDays,
                maxEventsPerSession != null ? maxEventsPerSession : this.maxEventsPerSession);
    }
}
