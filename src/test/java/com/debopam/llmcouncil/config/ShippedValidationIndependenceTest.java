package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.api.dto.CatalogResponse;
import com.debopam.llmcouncil.application.CatalogService;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
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

    @Autowired
    private CouncilCatalogHolder catalogHolder;

    /** Policies that exist purely as single-model test fixtures. */
    private static final Set<String> TEST_FIXTURE_POLICIES =
            Set.of("mock-quick", "mock-balanced", "mock-rigorous");

    @ParameterizedTest
    @CsvSource({
            // Local councils validate on Gemma, which is distinct from the
            // Llama, Mistral, and Qwen models that can produce the answer.
            "local-balanced,    INDEPENDENT",
            "local-rigorous,    INDEPENDENT",
            "hybrid-openai-balanced, INDEPENDENT",
            "hybrid-openai-rigorous, INDEPENDENT",
            "hybrid-claude-balanced, INDEPENDENT",
            "hybrid-claude-rigorous, INDEPENDENT",
            "multi-cloud-balanced, INDEPENDENT",
            "multi-cloud-rigorous, INDEPENDENT",
            // Single-provider profiles cannot reach INDEPENDENT. Flash validating
            // Pro still shares a training lineage; it is the best available
            // without leaving the provider.
            "gemini-balanced,   CORRELATED",
            "gemini-rigorous,   CORRELATED",
            // OpenAI and Claude use distinct provider model IDs for member,
            // chair, and validator duties, but remain correlated within their
            // provider/model family. Use multi-cloud for provider independence.
            "openai-balanced,   CORRELATED",
            "openai-rigorous,   CORRELATED",
            "claude-balanced,   CORRELATED",
            "claude-rigorous,   CORRELATED"
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
    void noRealPolicySeatsItsChairAsAMember() {
        List<String> conflictedPolicies = catalogService.catalog(Set.of("policies"), true)
                .policies().stream()
                .filter(policy -> !TEST_FIXTURE_POLICIES.contains(policy.id()))
                .filter(policy -> policy.memberModelIds().contains(policy.chairModelId()))
                .map(CatalogResponse.PolicySummary::id)
                .toList();

        assertTrue(conflictedPolicies.isEmpty(),
                   "these shipped policies seat their chair as a member: " + conflictedPolicies);
    }

    @Test
    void localPoliciesUseAChairFromOutsideEveryDraftingFamily() {
        var catalog = catalogHolder.get();
        List<String> correlatedPolicies = List.of("local-quick", "local-balanced", "local-rigorous")
                .stream()
                .filter(policyId -> {
                    var policy = catalog.policies().get(policyId);
                    String chairFamily = catalog.modelRegistry().model(policy.chairModelId()).modelFamily();
                    return policy.memberModelIds().stream()
                            .map(modelId -> catalog.modelRegistry().model(modelId).modelFamily())
                            .anyMatch(chairFamily::equalsIgnoreCase);
                })
                .toList();

        assertTrue(correlatedPolicies.isEmpty(),
                   "these local policies use a chair from a drafting family: " + correlatedPolicies);
    }

    @Test
    void quickPoliciesDeclareNoValidatorRatherThanAWeakOne() {
        // QUICK deliberately skips validation. Declaring no validator is honest;
        // naming the chair would manufacture a validation claim from nothing.
        Map<String, ValidationIndependence> tiers = tiersByPolicyId();

        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("local-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("openai-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("claude-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("gemini-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("hybrid-openai-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("hybrid-claude-quick"));
        assertEquals(ValidationIndependence.NOT_APPLICABLE, tiers.get("multi-cloud-quick"));
    }

    @Test
    void publicProfilesContainOnlySupportedProviderChoices() {
        Set<String> profileIds = catalogService.catalog(Set.of("profiles"), false)
                                               .profiles().stream()
                                               .map(CatalogResponse.ProfileSummary::id)
                                               .collect(Collectors.toSet());

        assertEquals(Set.of("default", "local", "openai", "claude", "hybrid-openai",
                            "hybrid-claude", "gemini", "multi-cloud"),
                     profileIds);
    }

    private Map<String, ValidationIndependence> tiersByPolicyId() {
        CatalogResponse catalog = catalogService.catalog(Set.of("policies"), true);
        return catalog.policies().stream()
                      .collect(Collectors.toMap(CatalogResponse.PolicySummary::id,
                                                CatalogResponse.PolicySummary::validationIndependence,
                                                (first, second) -> first));
    }
}
