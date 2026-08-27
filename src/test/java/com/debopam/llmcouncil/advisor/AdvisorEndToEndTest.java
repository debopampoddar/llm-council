package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import com.debopam.llmcouncil.api.dto.CatalogResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserPolicy;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the synthesizer produces has to survive the real validator.
 *
 * <p>{@code ConfigSynthesizerTest} proves the algorithm in isolation; this
 * proves the output is configuration this application will actually accept.
 * The two can pass separately and still leave the feature broken, because the
 * synthesizer knows nothing about the rules in {@code UserConfigValidator} — it
 * is written to satisfy them, which is not the same as satisfying them.
 *
 * <p>The environment comes from the real catalog with a <b>supplied</b>
 * installed list. Real model ids, so the validator has something genuine to
 * resolve; supplied tags, so the test does not depend on what happens to be
 * pulled on the machine running it and makes no network call.
 */
@SpringBootTest
class AdvisorEndToEndTest {

    private static final List<String> TWO_FAMILIES = List.of("llama3.1:8b", "mistral:7b");
    private static final List<String> LOCAL_CHAIR_INSTALLED =
            List.of("llama3.1:8b", "mistral:7b", "granite3.3:8b");

    @Autowired
    private AdvisorService advisor;

    @Autowired
    private ConfigSynthesizer synthesizer;

    @Autowired
    private AdvisorEnvironmentService environmentService;

    @Test
    void everyRequirementCombinationSynthesisesConfigurationTheValidatorAccepts() {
        AdvisorEnvironment environment = environmentService.describe(TWO_FAMILIES);
        int checked = 0;

        for (CouncilRequirement.Privacy privacy : CouncilRequirement.Privacy.values()) {
            for (CouncilRequirement.Latency latency : CouncilRequirement.Latency.values()) {
                for (CouncilRequirement.Rigor rigor : CouncilRequirement.Rigor.values()) {
                    for (int size = 1; size <= 4; size++) {
                        CouncilRequirement requirement = new CouncilRequirement(
                                privacy, latency, CouncilRequirement.Cost.LOW, rigor, size,
                                Set.of(CouncilRequirement.Domain.ANALYSIS), true);

                        SynthesisResult result = synthesizer.synthesize(
                                requirement, environment, UserConfigDocument.empty());
                        ValidationReportResponse report = advisor.validate(result.document());

                        assertTrue(report.valid(),
                                   privacy + "/" + latency + "/" + rigor + "/size=" + size
                                   + " produced configuration the validator rejects: "
                                   + report.issues());
                        checked++;
                    }
                }
            }
        }
        assertEquals(108, checked, "the sweep should have covered every combination");
    }

    @Test
    void theValidatorWouldHaveRejectedABadDocument() {
        // Positive control for the sweep above. Without it, that test passes just
        // as well if validate() returned valid for everything.
        UserConfigDocument broken = new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION, List.of(),
                Map.of("advisor-balanced", new UserPolicy(
                        "balanced", List.of("no-such-model"), "no-such-model", null,
                        1, 0, false, true, null)),
                Map.of(), Map.of(), null);

        ValidationReportResponse report = advisor.validate(broken);
        assertFalse(report.valid(), "a policy naming an unknown model must not validate");
    }

    @Test
    void theSynthesisedProfileBecomesSelectableAtEveryDepth() {
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                       CouncilRequirement.Latency.MODERATE,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.RIGOROUS, 3,
                                       Set.of(CouncilRequirement.Domain.CODE), false),
                environmentService.describe(TWO_FAMILIES), UserConfigDocument.empty());

        CatalogResponse.ProfileSummary profile = advisor.preview(result.document()).profiles()
                .stream()
                .filter(summary -> summary.id().equals(AdvisorIds.PROFILE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the advisor profile should be selectable"));

        assertEquals(DepthMode.RIGOROUS, profile.defaultDepth());
        assertEquals(List.of(DepthMode.QUICK, DepthMode.BALANCED, DepthMode.RIGOROUS),
                     profile.availableDepths());
        assertFalse(profile.testOnly(), "a synthesised profile is never test-only");
    }

    @Test
    void theCarefulDepthsKeepAnonymisedReviewWhateverWasAskedFor() {
        // The advisor cannot express orderedStages, so it cannot remove a stage.
        // What it can do is point a policy at the protocol that skips them, which
        // is why rigor picks the default depth rather than the only one.
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                       CouncilRequirement.Latency.FAST,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.QUICK, 2,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                environmentService.describe(TWO_FAMILIES), UserConfigDocument.empty());

        assertEquals("balanced",
                     result.document().policies().get(AdvisorIds.BALANCED_POLICY).protocolId());
        assertEquals("rigorous",
                     result.document().policies().get(AdvisorIds.RIGOROUS_POLICY).protocolId());
    }

    @Test
    void aSynthesisedConfigurationNeverReportsReducedIntegrity() {
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                       CouncilRequirement.Latency.FAST,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.RIGOROUS, 3,
                                       Set.of(CouncilRequirement.Domain.GENERAL), true),
                environmentService.describe(TWO_FAMILIES), UserConfigDocument.empty());

        assertNotNull(result.document().protocols().get(AdvisorIds.FAST_RIGOROUS_PROTOCOL),
                      "this requirement should derive a tuned protocol, or the assertion "
                      + "below proves nothing");
        assertFalse(advisor.validate(result.document()).integrityReduced(),
                    "tuning debate rounds must not count as weakening a guarantee");
    }

    @Test
    void theEnvironmentNeverOffersAModelThatCannotBeCalled() {
        AdvisorEnvironment environment = environmentService.describe(TWO_FAMILIES);

        assertFalse(environment.catalogModels().isEmpty(), "the shipped catalog should not be empty");
        assertTrue(environment.catalogModels().stream()
                              .anyMatch(model -> model.availability()
                                      == com.debopam.llmcouncil.model.ClientAvailability.MOCK),
                   "the shipped catalog contains mock models, so the exclusion below is "
                   + "exercised rather than vacuous");

        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.CLOUD_OK,
                                       CouncilRequirement.Latency.PATIENT,
                                       CouncilRequirement.Cost.UNCONSTRAINED,
                                       CouncilRequirement.Rigor.RIGOROUS, 8,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                environment, UserConfigDocument.empty());

        result.document().policies().values().forEach(policy ->
                policy.memberModelIds().forEach(id -> assertFalse(
                        id.startsWith("mock-"),
                        "a real council must never draw on fabricated output: " + id)));
    }

    @Test
    void theExtractionDefaultIsTheLocalProfilesChairWhenItIsInstalled() {
        AdvisorEnvironment environment = environmentService.describe(LOCAL_CHAIR_INSTALLED);
        assertEquals("local-chair", environment.defaultExtractionModelId());
    }

    @Test
    void thereIsNoExtractionDefaultWhenNothingLocalIsInstalled() {
        // Control: the default above is chosen, not hard-coded. With nothing
        // pulled there is no local model to pre-select, and the wizard has to say
        // so rather than offering a model that cannot answer.
        assertEquals(null, environmentService.describe(List.of()).defaultExtractionModelId());
    }

    @Test
    void previewingASynthesisedConfigurationRemovesNothing() {
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                       CouncilRequirement.Latency.MODERATE,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.BALANCED, 2,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                environmentService.describe(TWO_FAMILIES), UserConfigDocument.empty());

        List<CatalogDiffResponse.EntityChange> removals = advisor.preview(result.document())
                .changes().stream()
                .filter(change -> change.change() == CatalogDiffResponse.Change.REMOVED)
                .toList();

        assertTrue(removals.isEmpty(), "the advisor only adds: " + removals);
    }
}
