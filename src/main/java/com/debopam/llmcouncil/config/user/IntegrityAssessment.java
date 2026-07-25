package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.orchestration.ProtocolDefinition;
import com.debopam.llmcouncil.orchestration.StageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which anti-sycophancy guarantees a protocol gives up, and what that means.
 *
 * <p>Every option here is permitted — a user may have a reason to raise the
 * sycophancy threshold or switch dissent preservation off. What is not permitted
 * is a run using one looking identical to a run that did not. "No sycophancy
 * detected" and "detection was set so loose it could not fire" are different
 * statements about an answer, and a reader who cannot tell them apart is being
 * told the stronger one.
 *
 * <p>Whether a given value weakens anything is decided by
 * {@link StageOptionSpec#weakensIntegrity(Object)}, so the caution shown while
 * editing configuration and the flag carried by a finished run cannot disagree.
 * This type adds only the phrasing, which differs because the audiences do: one
 * is choosing a value, the other is reading a result.
 *
 * <p>The effective values are resolved against the protocol's <em>ordered
 * stages</em>, not merely its options. A protocol with no {@code DEBATE} stage
 * has no sycophancy threshold at all — reporting the default 0.70 for it would
 * imply a measurement that never happens.
 *
 * @param reduced            whether any guarantee is weakened
 * @param preserveDissent    effective dissent preservation, or null when the
 *                           protocol has no synthesis stage
 * @param sycophancyThreshold effective detection threshold, or null when the
 *                           protocol has no debate stage and therefore never
 *                           measures sycophancy
 * @param notes              one plainly-worded line per weakened guarantee
 */
public record IntegrityAssessment(
        boolean reduced,
        Boolean preserveDissent,
        Double sycophancyThreshold,
        List<String> notes
) {

    /** Defensive copy, so a caller cannot append to a finished assessment. */
    public IntegrityAssessment {
        notes = List.copyOf(notes);
    }

    /**
     * Assess a resolved protocol, as a run would execute it.
     *
     * <p>Callers pass the protocol pinned on the run's own context, never one
     * looked up afterwards. A run is evidence: its trust signals have to describe
     * the configuration it actually executed, and configuration can change
     * between the run finishing and someone reading it.
     *
     * @param protocol the protocol this run executed
     * @return what it gave up, and the effective values behind the answer
     */
    public static IntegrityAssessment of(ProtocolDefinition protocol) {
        if (protocol == null) {
            return new IntegrityAssessment(false, null, null, List.of());
        }
        List<String> notes = new ArrayList<>();

        Boolean preserveDissent = null;
        if (protocol.orderedStages().contains(StageType.SYNTHESIZE)) {
            Object configured = option(protocol, StageType.SYNTHESIZE, "preserve-dissent");
            preserveDissent = configured == null
                              ? Boolean.parseBoolean(String.valueOf(defaultOf(StageType.SYNTHESIZE,
                                                                             "preserve-dissent")))
                              : Boolean.parseBoolean(String.valueOf(configured));
            if (!preserveDissent) {
                notes.add("Dissent preservation was off for this run, so the chair was never asked "
                          + "to surface minority positions. The absence of a dissent section says "
                          + "nothing about whether the council disagreed.");
            }
        }

        Double sycophancyThreshold = null;
        if (protocol.orderedStages().contains(StageType.DEBATE)) {
            Object configured = option(protocol, StageType.DEBATE, "sycophancy-threshold");
            sycophancyThreshold = asDouble(configured != null
                                           ? configured
                                           : defaultOf(StageType.DEBATE, "sycophancy-threshold"));
            if (weakens(StageType.DEBATE, "sycophancy-threshold", configured)) {
                notes.add("Sycophancy detection ran at a threshold of " + sycophancyThreshold
                          + ", above the " + StageOptionSpec.SYCOPHANCY_SUPPRESSION_THRESHOLD
                          + " at which most agreement passes unflagged. A clean result here is "
                          + "weaker evidence than it looks.");
            }
        }

        // Anything else the table marks as integrity-reducing is reported
        // generically rather than silently, so a new option added to the spec
        // reaches a reader before anyone writes bespoke prose for it.
        protocol.stageOptions().forEach((stage, options) -> options.values().forEach((key, value) -> {
            if (isKnownSpecialCase(stage, key) || !weakens(stage, key, value)) {
                return;
            }
            notes.add("Stage option '" + key + "' on " + stage + " was set to " + value
                      + ", which weakens an anti-sycophancy guarantee for this run.");
        }));

        return new IntegrityAssessment(!notes.isEmpty(), preserveDissent, sycophancyThreshold, notes);
    }

    /**
     * Whether a set of user-supplied stage options weakens any guarantee.
     *
     * <p>Used before a protocol exists, while a proposed configuration is still
     * being validated.
     *
     * @param stageOptions stage name to option name to value, as written
     * @return {@code true} when at least one option weakens a guarantee
     */
    public static boolean reducedIn(Map<String, Map<String, Object>> stageOptions) {
        if (stageOptions == null) {
            return false;
        }
        for (Map.Entry<String, Map<String, Object>> stage : stageOptions.entrySet()) {
            if (stage.getValue() == null) {
                continue;
            }
            StageType stageType;
            try {
                stageType = StageType.valueOf(stage.getKey());
            } catch (IllegalArgumentException ex) {
                // An unknown stage is a validation error reported elsewhere; it
                // cannot weaken anything because it never runs.
                continue;
            }
            for (Map.Entry<String, Object> option : stage.getValue().entrySet()) {
                if (weakens(stageType, option.getKey(), option.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The two options with bespoke wording above, so they are not repeated below. */
    private static boolean isKnownSpecialCase(StageType stage, String key) {
        return (stage == StageType.SYNTHESIZE && key.equals("preserve-dissent"))
               || (stage == StageType.DEBATE && key.equals("sycophancy-threshold"));
    }

    private static boolean weakens(StageType stage, String key, Object value) {
        return StageOptionSpec.find(stage, key)
                              .map(spec -> spec.weakensIntegrity(value))
                              .orElse(false);
    }

    private static Object option(ProtocolDefinition protocol, StageType stage, String key) {
        return Optional.ofNullable(protocol.stageOptions().get(stage))
                       .map(options -> options.values().get(key))
                       .orElse(null);
    }

    private static Object defaultOf(StageType stage, String key) {
        return StageOptionSpec.find(stage, key).map(StageOptionSpec::defaultValue).orElse(null);
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
