package com.debopam.llmcouncil.config.user;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The bounds a user configuration overlay is held to.
 *
 * <p>These constants exist as a named type rather than as private fields on
 * {@link UserConfigValidator} because two things must agree about them: the
 * validator that rejects an out-of-range value, and the schema the configuration
 * UI renders its inputs from. A range hard-coded in the form and a range checked
 * in Java drift apart within a release, and the failure is quiet — the form
 * accepts a value the server then refuses, or worse, offers a narrower range than
 * the server allows and no one notices the capability was lost.
 *
 * <p>The bounds themselves are deliberately not user-settable. A boundary whose
 * limits can be raised from inside the boundary is not one.
 *
 * <p>This class carries only what the validator actually enforces. A constraint
 * described in the plan but not implemented does not belong here: publishing it
 * would make the UI stricter than the API, which is the same drift in the other
 * direction.
 */
public final class ConfigLimits {

    private ConfigLimits() {
    }

    /**
     * Slug rule for every user-supplied id.
     *
     * <p>Must start and end alphanumeric: ids become map keys and URL path
     * segments, where a trailing hyphen is a nuisance rather than a choice.
     */
    public static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$");

    /** Providers a user may bind to. Adding a provider needs Java, not config. */
    public static final Set<String> ALLOWED_PROVIDERS = Set.of(
            "ollama", "openai", "anthropic", "gemini", "openai-compatible");

    /** Depth modes a profile may map to a policy. */
    public static final List<String> DEPTH_MODES = List.of("QUICK", "BALANCED", "RIGOROUS");

    public static final int MIN_OUTPUT_TOKENS = 64;
    public static final int MAX_OUTPUT_TOKENS = 32_000;
    public static final double MIN_TEMPERATURE = 0.0;
    public static final double MAX_TEMPERATURE = 2.0;
    public static final int MIN_TIMEOUT_SECONDS = 5;
    public static final int MAX_TIMEOUT_SECONDS = 900;
    public static final int MIN_CONTEXT_TOKENS = 1_024;
    public static final int MAX_CONTEXT_TOKENS = 1_000_000;
    public static final int MIN_MEMBERS = 1;
    public static final int MAX_MEMBERS = 8;
    public static final int MIN_CONCURRENT_RUNS = 1;
    public static final int MAX_CONCURRENT_RUNS = 8;
    public static final int MIN_RECENT_TURNS = 1;
    public static final int MAX_RECENT_TURNS = 20;
    public static final int MIN_RETRY_ATTEMPTS = 0;
    public static final int MAX_RETRY_ATTEMPTS = 5;
    public static final long MIN_RETRY_DELAY_MS = 100L;
    public static final long MAX_RETRY_DELAY_MS = 30_000L;
    public static final int MAX_DISPLAY_NAME_LENGTH = 80;

    // Retention bounds. The lower bounds are what stop a user turning eviction
    // into deletion: a maxSessions of 0 would evict every entry the instant it
    // was written, so a finished run would lose its own result. The upper bounds
    // are what stop a user turning eviction off by writing a number so large it
    // never fires — which is the unbounded growth this feature exists to end.
    public static final int MIN_MAX_SESSIONS = 10;
    public static final int MAX_MAX_SESSIONS = 100_000;
    public static final int MIN_MAX_AGE_DAYS = 1;
    public static final int MAX_MAX_AGE_DAYS = 3_650;
    public static final int MIN_MAX_EVENTS_PER_SESSION = 100;
    public static final int MAX_MAX_EVENTS_PER_SESSION = 1_000_000;

    /**
     * Lower bound on a per-1,000-token price, in USD.
     *
     * <p>Zero is permitted and means <em>unpriced</em>, not free.
     */
    public static final double MIN_COST_PER_1K_TOKENS = 0.0;

    /**
     * Upper bound on a per-1,000-token price, in USD.
     *
     * <p>No provider charges anywhere near this. The bound exists to catch a
     * misplaced decimal point or a per-million figure pasted into a per-thousand
     * field, either of which would inflate every reported cost by three orders
     * of magnitude and make the spend signal actively misleading.
     */
    public static final double MAX_COST_PER_1K_TOKENS = 1000.0;

    /**
     * List the allowed providers in a stable order.
     *
     * @return provider keys, sorted, for messages and schema output
     */
    public static List<String> sortedProviders() {
        return ALLOWED_PROVIDERS.stream().sorted().toList();
    }

    /**
     * Copy the allowed providers into a set that preserves insertion order.
     *
     * @return a sorted, ordered copy of {@link #ALLOWED_PROVIDERS}
     */
    public static Set<String> orderedProviders() {
        return new LinkedHashSet<>(sortedProviders());
    }
}
