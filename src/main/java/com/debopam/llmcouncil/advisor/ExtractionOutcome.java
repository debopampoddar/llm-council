package com.debopam.llmcouncil.advisor;

import java.util.List;

/**
 * What extracting a requirement from free text produced.
 *
 * <p>The free text is <b>not</b> echoed here. It is never logged, never
 * persisted, and never returned — the wizard keeps its own copy, so a user whose
 * extraction failed does not retype anything, and the description does not
 * travel through a second response body on its way back to the page it came
 * from. {@code ExtractionOutcomeTest} asserts nothing in this record contains it.
 *
 * @param requirement      what was understood; the defaults when nothing was
 * @param notes            one line per field that could not be read, naming the
 *                         value that was rejected and what was used instead
 * @param modelRationale   the model's own explanation, shown to the user as
 *                         something the model said rather than as a decision
 * @param usedModelId      the model that answered, or null when the form fallback
 *                         applies
 * @param attempts         how many times the model was called
 * @param promptTokens     prompt tokens billed, or null when the provider omitted
 *                         the count
 * @param completionTokens completion tokens billed, or null when omitted
 * @param latencyMillis    wall-clock time across every attempt
 * @param fallbackToForm   whether the wizard should open on the form instead
 * @param failureReason    why extraction failed, in words a user can act on;
 *                         null when it succeeded
 */
public record ExtractionOutcome(
        CouncilRequirement requirement,
        List<String> notes,
        String modelRationale,
        String usedModelId,
        int attempts,
        Long promptTokens,
        Long completionTokens,
        long latencyMillis,
        boolean fallbackToForm,
        String failureReason
) {

    /** Defensive copy of the notes. */
    public ExtractionOutcome {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /**
     * Build the outcome for a failed extraction.
     *
     * <p>Carries the default requirement rather than nothing, so the wizard's
     * form has something to render and the user edits rather than starts.
     *
     * @param reason         why it failed, phrased for a user
     * @param attempts       how many times the model was called
     * @param latencyMillis  wall-clock time spent trying
     * @return an outcome that sends the wizard to the form
     */
    public static ExtractionOutcome fallback(String reason, int attempts, long latencyMillis) {
        return new ExtractionOutcome(CouncilRequirement.defaults(), List.of(), null, null,
                                     attempts, null, null, latencyMillis, true, reason);
    }
}
