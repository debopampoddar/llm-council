package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.api.dto.CatalogResponse;
import com.debopam.llmcouncil.application.CatalogService;
import com.debopam.llmcouncil.model.ValidationIndependence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Fresh Eyes independence of the shipped policies.
 *
 * <p>Repointing a {@code validatorModelId} back at its chair would silently
 * reduce validation to self-review while every run still reported "validated".
 * That is invisible in behaviour and expensive in trust, so it is pinned here.
 */
@SpringBootTest
class ShippedValidationIndependenceTest {

    @Autowired
    private CatalogService catalogService;

    /** Policies that exist purely as single-model test fixtures. */
    private static final Set<String> TEST_FIXTURE_POLICIES =
            Set.of("mock-quick", "mock-balanced", "mock-rigorous");

    @ParameterizedTest
    @CsvSource({
            // Local councils validate on mistral against a llama chair. This
            // costs no extra download or memory: mistral:7b is already pulled
            // for local-mistral, and VALIDATE runs alone after SYNTHESIZE.
            "local-balanced,    INDEPENDENT",
            "local-rigorous,    INDEPENDENT",
            // Hybrid has Ollama in play already, so a local validator against a
            // GPT-family chair is both independent and the cheaper option.
            "hybrid-balanced,   INDEPENDENT",
            "hybrid-rigorous,   INDEPENDENT",
            "multi-cloud-balanced, INDEPENDENT",
            "multi-cloud-rigorous, INDEPENDENT",
            // Single-provider profiles cannot reach INDEPENDENT. Flash validating
            // Pro still shares a training lineage; it is the best available
            // without leaving the provider.
            "gemini-balanced,   CORRELATED",
            "gemini-rigorous,   CORRELATED",
            // OCA_LLM_REVIEW_MODEL defaults to the same model as OCA_LLM_MODEL.
            // An operator fixes this by pointing them at different models.
            "oci-balanced,      CORRELATED",
            "oci-rigorous,      CORRELATED"
    })
    void shippedPolicyHasExpectedValidationIndependence(String policyId, ValidationIndependence expected) {
        assertEquals(expected, tiersByPolicyId().get(policyId),
                     "validation independence changed for policy " + policyId);
    }

    @Test
    void noRealPolicyLetsTheChairValidateItsOwnSynthesis() {
        // The invariant this whole test exists for. A chair reviewing its own
        // synthesis shares all of its own blind spots, so a "validated" marker
        // on such a run overstates what was actually checked.
        List<String> selfValidating = tiersByPolicyId().entrySet().stream()
                .filter(entry -> !TEST_FIXTURE_POLICIES.contains(entry.getKey()))
                .filter(entry -> entry.getValue() == ValidationIndependence.SELF_VALIDATION)
                .map(Map.Entry::getKey)
                .toList();

        assertTrue(selfValidating.isEmpty(),
                   "these shipped policies let the chair validate itself: " + selfValidating);
    }

    @Test
    void quickPoliciesDeclareNoValidatorRatherThanAWeakOne() {
        // QUICK deliberately skips validation. Declaring no validator is honest;
        // naming the chair would manufacture a validation claim from nothing.
        Map<String, ValidationIndependence> tiers = tiersByPolicyId();

        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("local-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("oci-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("gemini-quick"));
    }

    private Map<String, ValidationIndependence> tiersByPolicyId() {
        CatalogResponse catalog = catalogService.catalog(Set.of("policies"), true);
        return catalog.policies().stream()
                      .collect(Collectors.toMap(CatalogResponse.PolicySummary::id,
                                                CatalogResponse.PolicySummary::validationIndependence,
                                                (first, second) -> first));
    }
}
