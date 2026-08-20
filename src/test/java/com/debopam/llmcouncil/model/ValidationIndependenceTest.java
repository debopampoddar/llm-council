package com.debopam.llmcouncil.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationIndependenceTest {

    @Test
    void differentFamiliesAreIndependent() {
        assertEquals(ValidationIndependence.INDEPENDENT,
                     ValidationIndependence.between("local-chair", "llama", "llama3.1:8b",
                                                    "local-validator", "mistral", "mistral:7b"));
    }

    @Test
    void sameModelIdIsSelfValidation() {
        assertEquals(ValidationIndependence.SELF_VALIDATION,
                     ValidationIndependence.between("local-chair", "llama", "llama3.1:8b",
                                                    "local-chair", "llama", "llama3.1:8b"));
    }

    @Test
    void sameFamilyDifferentModelIsCorrelated() {
        assertEquals(ValidationIndependence.CORRELATED,
                     ValidationIndependence.between("gemini-pro", "gemini", "gemini-2.5-pro",
                                                    "gemini-flash", "gemini", "gemini-2.5-flash"));
    }

    @Test
    void distinctIdsResolvingToTheSameProviderModelAreCorrelated() {
        // Two logical OpenAI bindings can still resolve to the same underlying
        // provider model. Comparing ids alone would call
        // this independent, which is exactly the mistake worth catching.
        assertEquals(ValidationIndependence.CORRELATED,
                     ValidationIndependence.between("openai-chair", null, "gpt-4.1",
                                                    "openai-validator", null, "gpt-4.1"));
    }

    @Test
    void noValidatorIsNotApplicable() {
        assertEquals(ValidationIndependence.NOT_APPLICABLE,
                     ValidationIndependence.between("local-chair", "llama", "llama3.1:8b",
                                                    null, null, null));
        assertEquals(ValidationIndependence.NOT_APPLICABLE,
                     ValidationIndependence.between("local-chair", "llama", "llama3.1:8b",
                                                    "  ", null, null));
    }

    @Test
    void untaggedFamiliesDoNotFalselyReportCorrelation() {
        // Two blank families must not compare equal, or every untagged pair
        // would be reported as correlated.
        assertEquals(ValidationIndependence.INDEPENDENT,
                     ValidationIndependence.between("chair", null, "model-a",
                                                    "validator", null, "model-b"));
    }

    @Test
    void reducedTiersAreTheOnesWorthSurfacing() {
        assertTrue(ValidationIndependence.SELF_VALIDATION.isReduced());
        assertTrue(ValidationIndependence.CORRELATED.isReduced());
        assertFalse(ValidationIndependence.INDEPENDENT.isReduced());
        assertFalse(ValidationIndependence.NOT_APPLICABLE.isReduced());
    }

    @Test
    void validatorThatMatchesAnyMemberIsCorrelatedEvenWhenChairIsDifferent() {
        ValidationIndependence tier = ValidationIndependenceClassifier.classify(
                new ValidationIndependenceClassifier.Identity("chair", "llama", "llama3.1:8b"),
                List.of(
                        new ValidationIndependenceClassifier.Identity("member-a", "qwen", "qwen2.5:7b"),
                        new ValidationIndependenceClassifier.Identity("member-b", "mistral", "mistral:7b")),
                new ValidationIndependenceClassifier.Identity("validator", "mistral", "mistral:7b"));

        assertEquals(ValidationIndependence.CORRELATED, tier);
    }

    @Test
    void validatorDistinctFromChairAndEveryMemberIsIndependent() {
        ValidationIndependence tier = ValidationIndependenceClassifier.classify(
                new ValidationIndependenceClassifier.Identity("chair", "llama", "llama3.1:8b"),
                List.of(
                        new ValidationIndependenceClassifier.Identity("member-a", "qwen", "qwen2.5:7b"),
                        new ValidationIndependenceClassifier.Identity("member-b", "mistral", "mistral:7b")),
                new ValidationIndependenceClassifier.Identity("validator", "gemma", "gemma4:12b-it-qat"));

        assertEquals(ValidationIndependence.INDEPENDENT, tier);
    }
}
