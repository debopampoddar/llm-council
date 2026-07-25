package com.debopam.llmcouncil.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Two spellings of one model family must not read as two families.
 *
 * <p>{@code modelFamily} is a trust signal, not a label: it is what decides
 * whether a validator is reported as able to catch the chair's mistakes. Compared
 * literally, {@code Claude} and {@code claude} are different families, so a chair
 * and validator running the same weights would come back
 * {@link ValidationIndependence#INDEPENDENT} — a validated badge for a check that
 * never happened. The overlay is hand-written text, so this is reachable from the
 * configuration UI, not a theoretical case.
 */
class ModelFamilyIdentityTest {

    @Test
    void aFamilyTagIsStoredInCanonicalForm() {
        assertEquals("claude", model("chair", "  Claude  ").modelFamily());
        assertEquals("llama", model("chair", "LLAMA").modelFamily());
        assertNull(model("chair", null).modelFamily(),
                   "an absent tag stays absent; it must not become an empty family that matches");
    }

    @Test
    void differingCapitalisationIsOneFamily() {
        assertEquals(ValidationIndependence.CORRELATED,
                     between("Claude", "claude"),
                     "a chair and validator from the same family must never read as independent "
                     + "because one was capitalised");
    }

    @Test
    void surroundingWhitespaceIsOneFamily() {
        assertEquals(ValidationIndependence.CORRELATED, between("qwen", " qwen "));
    }

    @Test
    void genuinelyDifferentFamiliesAreStillIndependent() {
        // Positive control. Without it, a comparison that returned CORRELATED for
        // everything would pass every assertion above.
        assertEquals(ValidationIndependence.INDEPENDENT, between("claude", "llama"));
    }

    @Test
    void anUntaggedModelIsNotSilentlyCorrelatedWithAnother() {
        // Blank is "unknown", not "matches". Two untagged models are reported
        // independent, and the separate untagged-model warning covers the doubt.
        assertEquals(ValidationIndependence.INDEPENDENT, between(null, null));
        assertEquals(ValidationIndependence.INDEPENDENT, between("", ""));
        assertEquals(ValidationIndependence.INDEPENDENT, between("claude", null));
    }

    @Test
    void sameModelStillOutranksFamilyComparison() {
        assertEquals(ValidationIndependence.SELF_VALIDATION,
                     ValidationIndependence.between("chair", "Claude", "claude-sonnet-4",
                                                    "chair", "claude", "claude-sonnet-4"));
    }

    private ValidationIndependence between(String chairFamily, String validatorFamily) {
        return ValidationIndependence.between(
                "chair", chairFamily, "chair-provider-model",
                "validator", validatorFamily, "validator-provider-model");
    }

    private ModelProfile model(String id, String family) {
        return new ModelProfile(id, "ollama", "llama3.1:8b", 1000, 0.3,
                                Duration.ofSeconds(60), ModelRole.CHAIR,
                                CouncilRole.SYNTHESIZER, family);
    }
}
