package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.config.user.ConfigLimits;

import java.util.Collection;
import java.util.Locale;

/**
 * The namespace the advisor owns, and the only ids it may write.
 *
 * <p>The advisor is <b>additive</b>: it may create and replace entities whose id
 * begins with {@link #PREFIX}, and it must leave everything else in a user's
 * overlay exactly as it found it. Concentrating that rule in one place means the
 * carry-forward filter and the id generator cannot disagree about which entities
 * belong to whom — if they did, the advisor would either delete something it did
 * not write or refuse to replace its own previous output.
 *
 * <p>Ids are deterministic rather than freshly generated per run. A second run
 * therefore reports as replacing the first council rather than as adding a
 * second one next to it, which is what actually happened.
 */
public final class AdvisorIds {

    /** Every entity the advisor writes starts with this. */
    public static final String PREFIX = "advisor-";

    /** The profile a user selects to run the synthesised council. */
    public static final String PROFILE = "advisor";

    /** The built-in profile id a user may optionally point at the same policies. */
    public static final String DEFAULT_PROFILE = "default";

    /** Policy ids, one per depth. */
    public static final String QUICK_POLICY = PREFIX + "quick";
    public static final String BALANCED_POLICY = PREFIX + "balanced";
    public static final String RIGOROUS_POLICY = PREFIX + "rigorous";

    /** The derived protocol emitted when a fast, rigorous council is asked for. */
    public static final String FAST_RIGOROUS_PROTOCOL = PREFIX + "rigorous-fast";

    private AdvisorIds() {
    }

    /**
     * Whether an id belongs to the advisor and may therefore be overwritten.
     *
     * <p>The profile id {@code advisor} counts even though it does not carry the
     * hyphen, because it is the advisor's own output by definition.
     *
     * @param id the entity id, may be null
     * @return {@code true} when the advisor owns this id
     */
    public static boolean owns(String id) {
        return id != null && (id.equals(PROFILE) || id.startsWith(PREFIX));
    }

    /**
     * Build a model id for a model this configuration will define.
     *
     * <p>The result always satisfies {@link ConfigLimits#ID_PATTERN}. A provider
     * model id is not a slug — {@code qwen2.5:14b} carries a dot and a colon —
     * and an id the validator rejects would drop the model, the policies that
     * referenced it, and then the profile, leaving a user with an empty council
     * and a cascade of errors about entities they never typed.
     *
     * @param providerModelId the tag at the provider
     * @param taken           ids already used, to disambiguate against
     * @return a unique, valid model id inside the advisor's namespace
     */
    public static String modelId(String providerModelId, Collection<String> taken) {
        String slug = slug(providerModelId);
        String candidate = truncate(PREFIX + (slug.isEmpty() ? "model" : slug));
        if (!taken.contains(candidate)) {
            return candidate;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String numbered = truncate(candidate, String.valueOf(suffix));
            if (!taken.contains(numbered)) {
                return numbered;
            }
        }
        throw new IllegalStateException("Could not derive a unique model id for " + providerModelId);
    }

    /**
     * Reduce arbitrary text to the id alphabet.
     *
     * @param text the text to reduce, may be null
     * @return lowercase alphanumerics and single hyphens, trimmed at both ends
     */
    static String slug(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean lastWasHyphen = false;
        for (char character : text.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(character) && character < 128) {
                builder.append(character);
                lastWasHyphen = false;
            } else if (!lastWasHyphen && !builder.isEmpty()) {
                builder.append('-');
                lastWasHyphen = true;
            }
        }
        while (!builder.isEmpty() && builder.charAt(builder.length() - 1) == '-') {
            builder.deleteCharAt(builder.length() - 1);
        }
        return builder.toString();
    }

    private static String truncate(String id) {
        return truncate(id, "");
    }

    /**
     * Fit an id inside the pattern's length bound, keeping any suffix intact.
     *
     * <p>The bound is 63 characters and the last character must be
     * alphanumeric, so trimming can leave a trailing hyphen that has to go too.
     */
    private static String truncate(String id, String suffix) {
        int maximum = 63 - suffix.length() - (suffix.isEmpty() ? 0 : 1);
        String head = id.length() <= maximum ? id : id.substring(0, maximum);
        while (!head.isEmpty() && head.charAt(head.length() - 1) == '-') {
            head = head.substring(0, head.length() - 1);
        }
        return suffix.isEmpty() ? head : head + "-" + suffix;
    }
}
