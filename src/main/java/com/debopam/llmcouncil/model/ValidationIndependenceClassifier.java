package com.debopam.llmcouncil.model;

import java.util.Collection;
import java.util.List;

/** Classifies validator independence against every model that produced the answer. */
public final class ValidationIndependenceClassifier {

    private ValidationIndependenceClassifier() {
    }

    /** Minimal resolved identity needed for correlation checks. */
    public record Identity(String id, String family, String providerModelId) {
        public static Identity from(ModelProfile model) {
            return new Identity(model.id(), model.modelFamily(), model.providerModelId());
        }
    }

    /**
     * A validator is independent only when it is independent of the chair and
     * every member whose draft could influence synthesis.
     */
    public static ValidationIndependence classify(
            Identity chair, Collection<Identity> members, Identity validator) {
        if (validator == null || validator.id() == null || validator.id().isBlank()) {
            return ValidationIndependence.NOT_APPLICABLE;
        }
        if (chair == null) {
            return ValidationIndependence.NOT_APPLICABLE;
        }
        ValidationIndependence chairTier = ValidationIndependence.between(
                chair.id(), chair.family(), chair.providerModelId(),
                validator.id(), validator.family(), validator.providerModelId());
        if (chairTier == ValidationIndependence.SELF_VALIDATION) {
            return chairTier;
        }
        if (chairTier == ValidationIndependence.CORRELATED) {
            return chairTier;
        }
        for (Identity member : members == null ? List.<Identity>of() : members) {
            ValidationIndependence memberTier = ValidationIndependence.between(
                    member.id(), member.family(), member.providerModelId(),
                    validator.id(), validator.family(), validator.providerModelId());
            if (memberTier == ValidationIndependence.SELF_VALIDATION
                    || memberTier == ValidationIndependence.CORRELATED) {
                // The validator did not synthesize the final answer, so overlap
                // with a member is correlated participant validation rather than
                // chair self-validation.
                return ValidationIndependence.CORRELATED;
            }
        }
        return ValidationIndependence.INDEPENDENT;
    }
}
