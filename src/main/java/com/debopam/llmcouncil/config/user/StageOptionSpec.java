package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.orchestration.StageType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The permitted stage options, their bounds, and their defaults.
 *
 * <p>This table is the specification for what a user may tune. It exists as
 * data rather than as {@code if} statements because three things must agree: the
 * validator that rejects out-of-range values, the configuration UI that renders
 * the input controls, and the documentation. Hard-coding ranges in each would
 * guarantee they drift.
 *
 * <p>Options are an allowlist. {@code ProtocolStageOptions} silently ignores
 * keys it does not recognise, so accepting an unknown key would mean accepting a
 * typo that looks like it worked.
 *
 * @param stage             the stage this option belongs to
 * @param key               the option name as written in configuration
 * @param type              the value type
 * @param min               inclusive lower bound, or null when unbounded
 * @param max               inclusive upper bound, or null when unbounded
 * @param defaultValue      what the stage uses when the option is absent
 * @param allowedValues     permitted values for {@link Type#ENUM}, else empty
 * @param pattern           regular expression for {@link Type#STRING}, else null
 * @param integrityReducing whether setting this weakens an anti-sycophancy guarantee
 * @param description       one line explaining what the option does
 */
public record StageOptionSpec(
        StageType stage,
        String key,
        Type type,
        Double min,
        Double max,
        Object defaultValue,
        List<String> allowedValues,
        String pattern,
        boolean integrityReducing,
        String description
) {

    /** Value types a stage option may take. */
    public enum Type { INT, DOUBLE, BOOLEAN, STRING, ENUM }

    /** Scoring strategies registered in the application. */
    private static final List<String> SCORING_STRATEGIES =
            List.of("median", "trimmed-mean", "confidence-weighted");

    /** Escalation policies understood by the SCORE stage. */
    private static final List<String> ESCALATION_POLICIES =
            List.of("SYNTHESIZE_WITH_DISSENT", "ESCALATE_TO_DEBATE", "FAIL_ON_DISAGREEMENT");

    private static final Map<String, StageOptionSpec> SPECS = buildSpecs();

    /**
     * Look up the specification for a stage option.
     *
     * @param stage the stage the option was written under
     * @param key   the option name
     * @return the spec, or empty when the option is not permitted
     */
    public static Optional<StageOptionSpec> find(StageType stage, String key) {
        return Optional.ofNullable(SPECS.get(stage.name() + "." + key));
    }

    /** @return every permitted option, in declaration order */
    public static List<StageOptionSpec> all() {
        return List.copyOf(SPECS.values());
    }

    /**
     * List the option names permitted for one stage.
     *
     * @param stage the stage
     * @return permitted option names, useful for error messages
     */
    public static List<String> keysFor(StageType stage) {
        return SPECS.values().stream()
                    .filter(spec -> spec.stage() == stage)
                    .map(StageOptionSpec::key)
                    .toList();
    }

    private static Map<String, StageOptionSpec> buildSpecs() {
        List<StageOptionSpec> specs = List.of(
                intSpec(StageType.DEBATE, "min-rounds", 1, 3, 2,
                        "Minimum debate rounds before convergence can end the debate."),
                intSpec(StageType.DEBATE, "max-rounds", 1, 5, 3,
                        "Maximum debate rounds. Must be at least min-rounds."),
                doubleSpec(StageType.DEBATE, "ks-convergence-threshold", 0.01, 0.50, 0.10, false,
                           "Kolmogorov-Smirnov distance below which positions count as converged."),
                doubleSpec(StageType.DEBATE, "debate-trigger-score-variance", 0.0, 1000.0, 120.0, false,
                           "Score variance above which a debate is triggered."),
                doubleSpec(StageType.DEBATE, "sycophancy-threshold", 0.30, 0.95, 0.70, true,
                           "Similarity above which a debate turn is flagged as sycophantic agreement. "
                           + "Raising it suppresses warnings rather than the behaviour."),
                booleanSpec(StageType.DEBATE, "force-run", false, false,
                            "Run the debate even when scores already agree."),
                enumSpec(StageType.SCORE, "scoring-strategy", SCORING_STRATEGIES, "confidence-weighted",
                         "How per-reviewer scores are combined."),
                stringSpec(StageType.SCORE, "artifact-label", "^[a-z0-9-]{1,32}$", null,
                           "Label distinguishing this stage's score artifacts from another pass."),
                doubleSpec(StageType.SCORE, "escalation-variance-threshold", 0.0, 1000.0, 120.0, false,
                           "Score variance above which the escalation policy applies."),
                enumSpec(StageType.SCORE, "escalation-policy", ESCALATION_POLICIES, "SYNTHESIZE_WITH_DISSENT",
                         "What to do when reviewers disagree beyond the variance threshold."),
                booleanSpec(StageType.SYNTHESIZE, "preserve-dissent", true, true,
                            "Carry unresolved disagreement into the final answer. Turning this off "
                            + "produces a more confident answer than the council actually reached."),
                booleanSpec(StageType.EXPORT, "export-raw-artifacts", false, false,
                            "Include raw model responses in the export bundle.")
        );
        Map<String, StageOptionSpec> byKey = new LinkedHashMap<>();
        specs.forEach(spec -> byKey.put(spec.stage().name() + "." + spec.key(), spec));
        return Map.copyOf(byKey);
    }

    private static StageOptionSpec intSpec(StageType stage, String key, int min, int max,
                                           int defaultValue, String description) {
        return new StageOptionSpec(stage, key, Type.INT, (double) min, (double) max, defaultValue,
                                   List.of(), null, false, description);
    }

    private static StageOptionSpec doubleSpec(StageType stage, String key, double min, double max,
                                              double defaultValue, boolean integrityReducing,
                                              String description) {
        return new StageOptionSpec(stage, key, Type.DOUBLE, min, max, defaultValue,
                                   List.of(), null, integrityReducing, description);
    }

    private static StageOptionSpec booleanSpec(StageType stage, String key, boolean defaultValue,
                                               boolean integrityReducing, String description) {
        return new StageOptionSpec(stage, key, Type.BOOLEAN, null, null, defaultValue,
                                   List.of(), null, integrityReducing, description);
    }

    private static StageOptionSpec enumSpec(StageType stage, String key, List<String> allowed,
                                            String defaultValue, String description) {
        return new StageOptionSpec(stage, key, Type.ENUM, null, null, defaultValue,
                                   allowed, null, false, description);
    }

    private static StageOptionSpec stringSpec(StageType stage, String key, String pattern,
                                              String defaultValue, String description) {
        return new StageOptionSpec(stage, key, Type.STRING, null, null, defaultValue,
                                   List.of(), pattern, false, description);
    }
}
