package com.debopam.llmcouncil.config;

/**
 * Runtime knobs resolved from configuration, carried on the {@link CouncilCatalog}.
 *
 * <p>These live on the catalog rather than being read directly from properties
 * so that a user overlay can change them. Reading them straight from
 * {@code @Value} would mean the overlay's {@code runtime:} section validated
 * cleanly and then did nothing — accepted configuration that silently has no
 * effect is worse than configuration that is refused, because the user has no
 * way to tell the difference.
 *
 * <p>Values are applied when the catalog is built, which today is once at
 * startup: changing them takes a restart. Applying them to a running process is
 * the reload work, not something to half-do here.
 *
 * @param maxConcurrentRuns   how many council runs may be active at once
 * @param chatRecentTurnCount how many prior turns are folded into a chat's context
 * @param artifactBasePath    directory beneath which run artifacts are written
 */
public record CouncilRuntimeSettings(
        int maxConcurrentRuns,
        int chatRecentTurnCount,
        String artifactBasePath
) {

    /** Lower bound enforced on every path, so a bad value degrades rather than deadlocks. */
    private static final int MIN_CONCURRENT_RUNS = 1;

    /** Lower bound on chat history depth; zero turns would strip a chat of its context. */
    private static final int MIN_RECENT_TURNS = 1;

    /**
     * Clamps values that would otherwise stall the runtime.
     *
     * <p>The validator already rejects out-of-range overlay values with an
     * explanation. This is the last line of defence for the built-in path, where
     * a zero would leave the executor with no permits and every run rejected.
     */
    public CouncilRuntimeSettings {
        maxConcurrentRuns = Math.max(MIN_CONCURRENT_RUNS, maxConcurrentRuns);
        chatRecentTurnCount = Math.max(MIN_RECENT_TURNS, chatRecentTurnCount);
    }

    /**
     * Return a copy with any non-null override applied.
     *
     * <p>Null means "not mentioned", which keeps the existing value: a user who
     * sets only {@code maxConcurrentRuns} must not have the other knobs reset to
     * defaults as a side effect.
     *
     * @param maxConcurrentRuns   override, or null to keep the current value
     * @param chatRecentTurnCount override, or null to keep the current value
     * @param artifactBasePath    override, or null/blank to keep the current value
     * @return the merged settings
     */
    public CouncilRuntimeSettings withOverrides(Integer maxConcurrentRuns,
                                                Integer chatRecentTurnCount,
                                                String artifactBasePath) {
        return new CouncilRuntimeSettings(
                maxConcurrentRuns != null ? maxConcurrentRuns : this.maxConcurrentRuns,
                chatRecentTurnCount != null ? chatRecentTurnCount : this.chatRecentTurnCount,
                artifactBasePath != null && !artifactBasePath.isBlank()
                ? artifactBasePath
                : this.artifactBasePath);
    }
}
