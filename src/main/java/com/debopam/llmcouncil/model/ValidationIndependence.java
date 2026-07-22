package com.debopam.llmcouncil.model;

/**
 * How independent a policy's Fresh Eyes validator is from its chair.
 *
 * <p>The council's validation stage exists to catch errors the chair made while
 * synthesising. That only works when the validator does not share the chair's
 * blind spots. A validator running on the same weights as the chair shares
 * every one of them, so it can catch self-contradiction, unsupported leaps, and
 * rubric violations but cannot catch a factual error both models believe.
 *
 * <p>Low independence is never a hard failure — a machine that can only run one
 * model must still be able to run a council. What must not happen is a run
 * reporting validated output without saying how independent that validation
 * actually was: a "validated" marker makes a reader trust an answer more, so
 * rubber-stamped validation is worse than none. The pipeline therefore degrades
 * the <em>claim</em>, never silently the guarantee.
 */
public enum ValidationIndependence {

    /** Chair and validator come from different model families. */
    INDEPENDENT,

    /**
     * Different model ids, but the same family or the same resolved provider
     * model. Errors are likely to be correlated.
     */
    CORRELATED,

    /** Chair and validator are the same model. The chair validates itself. */
    SELF_VALIDATION,

    /** The policy declares no validator, so no validation claim is made. */
    NOT_APPLICABLE;

    /**
     * Classify the independence between a chair and a validator.
     *
     * <p>Provider model ids are compared as <em>resolved</em> values, so two
     * distinct logical models that both point at the same underlying provider
     * model (a common result of shared configuration defaults) are correctly
     * reported as {@link #CORRELATED} rather than {@link #INDEPENDENT}.
     *
     * @param chairId                  logical id of the chair model; must not be null
     * @param chairFamily              chair's model family tag, may be null
     * @param chairProviderModelId     chair's resolved provider model id, may be null
     * @param validatorId              logical id of the validator model, or null/blank when
     *                                 the policy declares no validator
     * @param validatorFamily          validator's model family tag, may be null
     * @param validatorProviderModelId validator's resolved provider model id, may be null
     * @return the independence tier for this chair/validator pair
     */
    public static ValidationIndependence between(String chairId,
                                                 String chairFamily,
                                                 String chairProviderModelId,
                                                 String validatorId,
                                                 String validatorFamily,
                                                 String validatorProviderModelId) {
        if (validatorId == null || validatorId.isBlank()) {
            return NOT_APPLICABLE;
        }
        if (validatorId.equals(chairId)) {
            return SELF_VALIDATION;
        }
        if (equalsIgnoringBlank(chairProviderModelId, validatorProviderModelId)) {
            return CORRELATED;
        }
        if (equalsIgnoringBlank(chairFamily, validatorFamily)) {
            return CORRELATED;
        }
        // Either the families differ, or at least one is untagged. An untagged
        // model cannot be proven correlated, so it is reported as independent
        // and the separate untagged-model warning covers the ambiguity.
        return INDEPENDENT;
    }

    /**
     * @return {@code true} when this tier means the validation claim is weaker
     *         than a reader would assume and should be surfaced to them
     */
    public boolean isReduced() {
        return this == CORRELATED || this == SELF_VALIDATION;
    }

    private static boolean equalsIgnoringBlank(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }
}
