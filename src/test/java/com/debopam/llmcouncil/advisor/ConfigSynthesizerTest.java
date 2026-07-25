package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.advisor.AdvisorEnvironment.CandidateModel;
import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.user.IntegrityAssessment;
import com.debopam.llmcouncil.config.user.ConfigLimits;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserModel;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserPolicy;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserProfile;
import com.debopam.llmcouncil.model.ClientAvailability;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.orchestration.StageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic half of the advisor.
 *
 * <p>Every "the advisor did not do X" assertion below is paired with a control
 * showing the detector can fire. That pairing is not ceremony: an assertion that
 * no mock model was seated passes perfectly on an empty candidate pool, and an
 * assertion that an uninstalled tag was skipped passes just as well if selection
 * is broken and seats nothing at all. Each control changes exactly one fact
 * about the environment and asserts the opposite outcome.
 *
 * <p>No Spring context, no network, no filesystem. The environment is a value,
 * which is the entire reason {@code OllamaModelDiscoveryService} is called
 * somewhere else.
 */
class ConfigSynthesizerTest {

    private final ConfigSynthesizer synthesizer = new ConfigSynthesizer();

    // ── The shipped-shape case ──────────────────────────────────────────

    @Test
    void aTwoModelLocalMachineReproducesTheShippedLocalCouncil() {
        // The strongest available check that selection is sane: given the models
        // the shipped local profile was hand-written for, it should arrive at the
        // hand-written answer rather than at something merely valid.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine(), UserConfigDocument.empty());

        UserPolicy balanced = result.document().policies().get(AdvisorIds.BALANCED_POLICY);
        assertEquals(List.of("local-llama3", "local-mistral"), balanced.memberModelIds());
        assertEquals("local-chair", balanced.chairModelId());
        assertEquals("local-validator", balanced.validatorModelId());
        assertEquals(2, balanced.minimumSuccessfulDrafts());
        assertEquals(1, balanced.minimumReviewsPerDraft());
        assertTrue(balanced.allowPartial());
    }

    @Test
    void builtInModelsAreReferencedAndNeverRedefined() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine(), UserConfigDocument.empty());

        assertTrue(result.document().models().isEmpty(),
                   "a machine whose installed tags are all bound by built-ins needs no new "
                   + "model definitions, got " + ids(result.document().models()));

        // Control: the same machine with one extra tag nothing binds does define one.
        SynthesisResult withUnbound = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine("qwen2.5:14b"), UserConfigDocument.empty());
        assertEquals(List.of("advisor-qwen2-5-14b"), ids(withUnbound.document().models()));
    }

    // ── Nothing to seat ─────────────────────────────────────────────────

    @Test
    void zeroInstalledModelsIsAnActionableErrorNotAnEmptyCouncil() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                environment(List.of(), Map.of("ollama", ClientAvailability.LIVE), builtInModels()),
                UserConfigDocument.empty());

        assertFalse(result.successful(), "no council should be produced with nothing installed");
        assertNull(result.profileId());
        assertTrue(result.document().profiles().isEmpty(),
                   "an empty council must not be written as a profile");

        ConfigIssue issue = onlyError(result);
        assertTrue(issue.message().contains("no local models installed"),
                   "the error should say what is wrong: " + issue.message());
        assertTrue(issue.remediation().contains("ollama pull"),
                   "and what to do about it: " + issue.remediation());
    }

    @Test
    void oneInstalledModelDoesProduceACouncil() {
        // Control for the test above: the failure path is reachable only because
        // there was nothing to seat, not because synthesis never produces a profile.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine(List.of("llama3.1:8b")), UserConfigDocument.empty());

        assertTrue(result.successful());
        assertEquals(AdvisorIds.PROFILE, result.profileId());
        assertFalse(result.hasErrors(), "a one-model council is a warning, not an error: "
                                        + result.issues());
    }

    @Test
    void aFailedSynthesisLeavesExistingConfigurationExactlyAsItWas() {
        UserConfigDocument existing = existingUserConfiguration();

        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                environment(List.of(), Map.of(), builtInModels()), existing);

        assertEquals(existing, result.document(),
                     "a re-run that cannot seat a council must not take away what is there");
    }

    // ── Availability filtering ──────────────────────────────────────────

    @Test
    void aCloudModelIsNotSeatedWhenItsProviderIsNotLive() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.CLOUD_OK, CouncilRequirement.Rigor.BALANCED, 3),
                environment(List.of("llama3.1:8b", "mistral:7b"),
                            Map.of("ollama", ClientAvailability.LIVE,
                                   "openai", ClientAvailability.UNAVAILABLE),
                            builtInModels(ClientAvailability.UNAVAILABLE)),
                UserConfigDocument.empty());

        assertFalse(seatedIds(result).contains("openai-gpt"),
                    "an unconfigured provider must never be proposed, got " + seatedIds(result));
    }

    @Test
    void aCloudModelIsSeatedWhenItsProviderIsLive() {
        // Control: the exclusion above is the availability check firing, not the
        // cloud model being absent from the pool altogether.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.CLOUD_OK, CouncilRequirement.Rigor.BALANCED, 3),
                environment(List.of("llama3.1:8b", "mistral:7b"),
                            Map.of("ollama", ClientAvailability.LIVE,
                                   "openai", ClientAvailability.LIVE),
                            builtInModels(ClientAvailability.LIVE)),
                UserConfigDocument.empty());

        assertTrue(seatedIds(result).contains("openai-gpt"),
                   "a live cloud provider should be usable, got " + seatedIds(result));
    }

    @Test
    void anUninstalledOllamaTagIsNeverProposed() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine(List.of("llama3.1:8b")), UserConfigDocument.empty());

        assertFalse(seatedIds(result).contains("local-mistral"),
                    "mistral:7b is not pulled, so nothing binding it may be seated: "
                    + seatedIds(result));
    }

    @Test
    void anInstalledOllamaTagIsProposed() {
        // Control for the test above, changing only the installed list.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine(List.of("llama3.1:8b", "mistral:7b")), UserConfigDocument.empty());

        assertTrue(seatedIds(result).contains("local-mistral"), "got " + seatedIds(result));
    }

    @Test
    void theImplicitLatestTagCountsAsInstalled() {
        // `ollama pull llama3.1` and an /api/tags entry of `llama3.1:latest` are
        // the same model; comparing raw strings tells the user they have nothing.
        AdvisorEnvironment environment = environment(
                List.of("hermes3:latest"), Map.of("ollama", ClientAvailability.LIVE), List.of());
        assertTrue(environment.isInstalled("hermes3"));
        assertTrue(environment.isInstalled("hermes3:latest"));
        assertFalse(environment.isInstalled("hermes3:70b"));
    }

    // ── Mock exclusion ──────────────────────────────────────────────────

    @Test
    void aMockModelIsNeverSeated() {
        AdvisorEnvironment environment = localMachine();

        // Without this the assertion below would pass on an environment that
        // simply never offered a mock model.
        assertTrue(environment.catalogModels().stream().anyMatch(model -> model.id().equals("mock-chair")),
                   "the environment must actually contain a mock model for this to prove anything");

        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.CLOUD_OK, CouncilRequirement.Rigor.RIGOROUS, 8),
                environment, UserConfigDocument.empty());

        assertTrue(seatedIds(result).stream().noneMatch(id -> id.startsWith("mock-")),
                   "a real council must never draw on fabricated output: " + seatedIds(result));
    }

    @Test
    void aMockModelIsExcludedEvenWhenItsClientLooksLive() {
        // The provider check and the availability check are deliberately
        // redundant. This proves the provider check alone is sufficient, so a
        // test-only model bound to a real client cannot slip through.
        List<CandidateModel> models = new ArrayList<>(builtInModels());
        models.add(model("mock-chair-live", "mock", "mock-chair", "mock", ModelRole.CHAIR,
                         ClientAvailability.LIVE));

        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.CLOUD_OK, CouncilRequirement.Rigor.BALANCED, 3),
                environment(List.of("llama3.1:8b", "mistral:7b"),
                            Map.of("ollama", ClientAvailability.LIVE), models),
                UserConfigDocument.empty());

        assertFalse(seatedIds(result).contains("mock-chair-live"), "got " + seatedIds(result));
    }

    // ── Cost ────────────────────────────────────────────────────────────

    @Test
    void freeOnlyDoesNotSeatAnUnpricedCloudModel() {
        // A cloud model with no configured price is unpriced, not free. Filtering
        // on the price field would seat one here.
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.CLOUD_OK,
                                       CouncilRequirement.Latency.MODERATE,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.BALANCED, 4,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                environment(List.of("llama3.1:8b", "mistral:7b"),
                            Map.of("ollama", ClientAvailability.LIVE,
                                   "openai", ClientAvailability.LIVE),
                            builtInModels(ClientAvailability.LIVE)),
                UserConfigDocument.empty());

        assertFalse(seatedIds(result).contains("openai-gpt"),
                    "free means local; an unpriced cloud model is not free: " + seatedIds(result));
    }

    @Test
    void unconstrainedCostDoesSeatTheSameCloudModel() {
        // Control: the exclusion above is the cost rule, not the provider being
        // unusable in this environment.
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.CLOUD_OK,
                                       CouncilRequirement.Latency.MODERATE,
                                       CouncilRequirement.Cost.UNCONSTRAINED,
                                       CouncilRequirement.Rigor.BALANCED, 4,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                environment(List.of("llama3.1:8b", "mistral:7b"),
                            Map.of("ollama", ClientAvailability.LIVE,
                                   "openai", ClientAvailability.LIVE),
                            builtInModels(ClientAvailability.LIVE)),
                UserConfigDocument.empty());

        assertTrue(seatedIds(result).contains("openai-gpt"), "got " + seatedIds(result));
    }

    // ── Diversity ───────────────────────────────────────────────────────

    @Test
    void aSingleFamilyCouncilWarns() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine(List.of("llama3.1:8b", "llama3.2:3b")), UserConfigDocument.empty());

        assertTrue(hasWarning(result, "family"),
                   "reduced heterogeneity must be surfaced, not silent: " + result.issues());
    }

    @Test
    void aMultiFamilyCouncilDoesNotWarnAboutFamilies() {
        // Control: the warning above tracks the pool, not every synthesis.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), UserConfigDocument.empty());

        assertFalse(hasWarning(result, "family"),
                    "a two-family council should not be warned about: " + result.issues());
    }

    @Test
    void membersNeverShareOneSetOfWeights() {
        // local-mistral and local-validator both bind mistral:7b. Seating both
        // would draft twice and review itself while looking like two opinions.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.RIGOROUS, 8),
                localMachine(), UserConfigDocument.empty());

        List<String> members = result.document().policies()
                                     .get(AdvisorIds.RIGOROUS_POLICY).memberModelIds();
        assertEquals(2, members.size(),
                     "two distinct tags can seat two members, not four ids: " + members);
    }

    @Test
    void askingForMoreMembersThanExistWarnsRatherThanInventing() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 6),
                localMachine(), UserConfigDocument.empty());

        assertTrue(hasWarning(result, "can run 2 distinct models"),
                   "the shortfall must be reported: " + result.issues());
    }

    // ── Validation independence ─────────────────────────────────────────

    @Test
    void theValidatorPrefersADifferentFamilyFromTheChair() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), UserConfigDocument.empty());

        UserPolicy balanced = result.document().policies().get(AdvisorIds.BALANCED_POLICY);
        assertEquals("local-chair", balanced.chairModelId());
        assertEquals("local-validator", balanced.validatorModelId());
        assertTrue(balanced.validationRequired(),
                   "an independent validator makes validation worth requiring");
    }

    @Test
    void aSingleModelMachineSelfValidatesLoudlyAndNeverAcknowledgesIt() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine(List.of("llama3.1:8b")), UserConfigDocument.empty());

        UserPolicy balanced = result.document().policies().get(AdvisorIds.BALANCED_POLICY);
        assertEquals(balanced.chairModelId(), balanced.validatorModelId());
        assertFalse(balanced.validationRequired(),
                    "a self-validating council must not be made to fail on its own validation");
        assertTrue(hasWarning(result, "validates its own synthesis"),
                   "self-validation must be reported: " + result.issues());

        // The flag exists to silence that warning. Setting it would be the
        // advisor accepting a trade-off on the user's behalf.
        assertTrue(result.document().policies().values().stream()
                         .noneMatch(policy -> Boolean.TRUE.equals(policy.acknowledgeSelfValidation())),
                   "the advisor must never acknowledge self-validation for the user");
    }

    // ── Integrity ───────────────────────────────────────────────────────

    @Test
    void noSynthesisedProtocolEverWeakensAnAntiSycophancyGuarantee() {
        for (CouncilRequirement.Privacy privacy : CouncilRequirement.Privacy.values()) {
            for (CouncilRequirement.Latency latency : CouncilRequirement.Latency.values()) {
                for (CouncilRequirement.Rigor rigor : CouncilRequirement.Rigor.values()) {
                    SynthesisResult result = synthesizer.synthesize(
                            new CouncilRequirement(privacy, latency, CouncilRequirement.Cost.LOW,
                                                   rigor, 3, Set.of(CouncilRequirement.Domain.CODE),
                                                   true),
                            localMachine(), UserConfigDocument.empty());

                    result.document().protocols().forEach((id, protocol) -> assertFalse(
                            IntegrityAssessment.reducedIn(protocol.stageOptions()),
                            "protocol " + id + " for " + privacy + "/" + latency + "/" + rigor
                            + " weakens a guarantee: " + protocol.stageOptions()));
                }
            }
        }
    }

    @Test
    void theIntegrityDetectorCanActuallyFire() {
        // Positive control for the sweep above: without this, that test passes
        // just as well if reducedIn always returned false.
        assertTrue(IntegrityAssessment.reducedIn(
                Map.of(StageType.SYNTHESIZE.name(), Map.of("preserve-dissent", false))));
        assertTrue(IntegrityAssessment.reducedIn(
                Map.of(StageType.DEBATE.name(), Map.of("sycophancy-threshold", 0.95))));
    }

    @Test
    void aFastRigorousCouncilTradesDebateRoundsAndSaysSo() {
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.LOCAL_ONLY,
                                       CouncilRequirement.Latency.FAST,
                                       CouncilRequirement.Cost.FREE_ONLY,
                                       CouncilRequirement.Rigor.RIGOROUS, 3,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                localMachine(), UserConfigDocument.empty());

        UserConfigDocument.UserProtocol tuned =
                result.document().protocols().get(AdvisorIds.FAST_RIGOROUS_PROTOCOL);
        assertNotNull(tuned, "a fast rigorous council should derive a protocol");
        assertEquals("rigorous", tuned.derivedFrom());
        assertEquals(ConfigSynthesizer.FAST_DEBATE_ROUNDS,
                     tuned.stageOptions().get(StageType.DEBATE.name()).get("max-rounds"));
        assertEquals(AdvisorIds.FAST_RIGOROUS_PROTOCOL,
                     result.document().policies().get(AdvisorIds.RIGOROUS_POLICY).protocolId());
        assertTrue(result.rationale().stream().anyMatch(line -> line.contains("trade-off")),
                   "the trade-off must be stated: " + result.rationale());
    }

    @Test
    void aPatientRigorousCouncilDerivesNoProtocolAtAll() {
        // Control: the derived protocol above is the FAST rule, not something
        // every rigorous council picks up.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.RIGOROUS, 3),
                localMachine(), UserConfigDocument.empty());

        assertTrue(result.document().protocols().isEmpty(), "got " + result.document().protocols());
        assertEquals("rigorous", result.document().policies()
                                       .get(AdvisorIds.RIGOROUS_POLICY).protocolId());
    }

    @Test
    void aFastCouncilCapsItsSize() {
        SynthesisResult result = synthesizer.synthesize(
                new CouncilRequirement(CouncilRequirement.Privacy.CLOUD_OK,
                                       CouncilRequirement.Latency.FAST,
                                       CouncilRequirement.Cost.UNCONSTRAINED,
                                       CouncilRequirement.Rigor.BALANCED, 8,
                                       Set.of(CouncilRequirement.Domain.GENERAL), false),
                localMachine(List.of("llama3.1:8b", "mistral:7b", "qwen2.5:14b", "gemma2:9b",
                                     "phi3:14b")),
                UserConfigDocument.empty());

        assertEquals(ConfigSynthesizer.FAST_MAX_COUNCIL_SIZE,
                     result.document().policies().get(AdvisorIds.BALANCED_POLICY)
                           .memberModelIds().size());
    }

    // ── All three depths ────────────────────────────────────────────────

    @Test
    void everyProfileMapsAllThreeDepthsWhateverRigorWasAsked() {
        for (CouncilRequirement.Rigor rigor : CouncilRequirement.Rigor.values()) {
            SynthesisResult result = synthesizer.synthesize(
                    requirement(CouncilRequirement.Privacy.LOCAL_ONLY, rigor, 3),
                    localMachine(), UserConfigDocument.empty());

            UserProfile profile = result.document().profiles().get(AdvisorIds.PROFILE);
            assertEquals(Set.of("QUICK", "BALANCED", "RIGOROUS"), profile.depthPolicies().keySet(),
                         "describing a " + rigor + " council must not remove the other depths");
            assertEquals(rigor.name(), profile.defaultDepth(),
                         "rigor picks the default depth, not the only one");
        }
    }

    // ── Additive behaviour ──────────────────────────────────────────────

    @Test
    void existingUserConfigurationIsCarriedThroughUntouched() {
        UserConfigDocument existing = existingUserConfiguration();

        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), existing);

        assertTrue(ids(result.document().models()).contains("my-critic"),
                   "a hand-written model must survive: " + ids(result.document().models()));
        assertEquals(existing.policies().get("my-policy"),
                     result.document().policies().get("my-policy"));
        assertEquals(existing.profiles().get("my-council"),
                     result.document().profiles().get("my-council"));
        assertEquals(existing.runtime(), result.document().runtime(),
                     "the advisor has no opinion about runtime knobs");
    }

    @Test
    void aSecondRunReplacesItsOwnOutputAndOnlyItsOwn() {
        UserConfigDocument existing = existingUserConfiguration();

        SynthesisResult first = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.RIGOROUS, 2),
                localMachine(), existing);
        SynthesisResult second = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.QUICK, 2),
                localMachine(), first.document());

        assertEquals("QUICK", second.document().profiles().get(AdvisorIds.PROFILE).defaultDepth(),
                     "the advisor's own profile is replaced, not duplicated");
        assertEquals(1, second.document().profiles().keySet().stream()
                              .filter(AdvisorIds::owns).count(),
                     "exactly one advisor profile should exist: "
                     + second.document().profiles().keySet());
        assertTrue(ids(second.document().models()).contains("my-critic"),
                   "and the user's own entities still survive the second run");
    }

    @Test
    void aStaleAdvisorModelIsNotCarriedForwardAsIfItWereTheUsers() {
        // The advisor's previous output is re-derived from today's environment.
        // Carrying it forward blindly would keep a binding for a tag that has
        // since been deleted, and the council would fail at run time instead.
        UserConfigDocument previous = new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION,
                List.of(new UserModel("advisor-gone-9b", "ollama", "gone:9b", 1200, 0.3, 240,
                                      null, "MEMBER", "PROPOSER", "gone", null, null, null, null)),
                Map.of(), Map.of(), Map.of(), null);

        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), previous);

        assertFalse(ids(result.document().models()).contains("advisor-gone-9b"),
                    "a model uninstalled since the last run must not survive: "
                    + ids(result.document().models()));
    }

    @Test
    void shadowingTheDefaultProfileNeverOverwritesTheUsersOwn() {
        UserConfigDocument existing = new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION, List.of(), Map.of(),
                Map.of("default", new UserProfile("Mine", "QUICK", Map.of("QUICK", "local-quick"))),
                Map.of(), null);

        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), existing, true);

        assertEquals("Mine", result.document().profiles().get("default").displayName(),
                     "the user's own default profile is not the advisor's to replace");
        assertTrue(hasWarning(result, "already defines a 'default' profile"),
                   "and the user is told why it was left alone: " + result.issues());
    }

    @Test
    void shadowingTheDefaultProfileWorksWhenTheUserHasNone() {
        // Control: the refusal above is about the user's own entity, not about
        // shadowing being disabled.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), UserConfigDocument.empty(), true);

        assertEquals(AdvisorIds.BALANCED_POLICY,
                     result.document().profiles().get("default").depthPolicies().get("BALANCED"));
    }

    @Test
    void shadowingIsOffUnlessAskedFor() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), UserConfigDocument.empty());

        assertFalse(result.document().profiles().containsKey("default"));
    }

    // ── Determinism and ids ─────────────────────────────────────────────

    @Test
    void synthesisIsDeterministic() {
        CouncilRequirement requirement =
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.RIGOROUS, 4);
        AdvisorEnvironment environment = localMachine("qwen2.5:14b", "gemma2:9b");

        assertEquals(synthesizer.synthesize(requirement, environment, UserConfigDocument.empty()).document(),
                     synthesizer.synthesize(requirement, environment, UserConfigDocument.empty()).document(),
                     "the same inputs must produce the same configuration");
    }

    @Test
    void everyGeneratedIdSatisfiesTheValidatorsPattern() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.RIGOROUS, 8),
                localMachine("qwen2.5:14b", "deepseek-r1:32b", "library/hermes3:latest",
                             "phi3.5:3.8b-mini-instruct-q4_0"),
                UserConfigDocument.empty());

        List<String> generated = new ArrayList<>(ids(result.document().models()));
        generated.addAll(result.document().policies().keySet());
        generated.addAll(result.document().profiles().keySet());
        generated.addAll(result.document().protocols().keySet());

        assertFalse(generated.isEmpty(), "nothing was generated, so nothing was checked");
        generated.forEach(id -> assertTrue(ConfigLimits.ID_PATTERN.matcher(id).matches(),
                                           "generated id '" + id + "' would be rejected by the validator"));
    }

    @Test
    void theIdPatternWouldRejectAnUnsluggedTag() {
        // Control for the test above: the pattern is not vacuously satisfied.
        assertFalse(ConfigLimits.ID_PATTERN.matcher("advisor-qwen2.5:14b").matches());
    }

    @Test
    void aFamilyInferredFromAModelNameIsReportedAsInferred() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 3),
                localMachine("qwen2.5:14b"), UserConfigDocument.empty());

        assertTrue(hasWarning(result, "was inferred from the name"),
                   "a guessed family is a guessed trust signal and must say so: " + result.issues());
    }

    @Test
    void aDeclaredFamilyIsNotReportedAsInferred() {
        // Control: the warning tracks inference, not every synthesised council.
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), UserConfigDocument.empty());

        assertFalse(hasWarning(result, "was inferred from the name"), "got " + result.issues());
    }

    @Test
    void theRationaleSaysDomainsChangedNothing() {
        SynthesisResult result = synthesizer.synthesize(
                requirement(CouncilRequirement.Privacy.LOCAL_ONLY, CouncilRequirement.Rigor.BALANCED, 2),
                localMachine(), UserConfigDocument.empty());

        assertTrue(result.rationale().stream()
                         .anyMatch(line -> line.contains("did not change model selection")),
                   "an inert control has to admit it is inert: " + result.rationale());
    }

    // ── Requirement normalisation ───────────────────────────────────────

    @Test
    void anUnspecifiedCouncilSizeIsTheDefaultRatherThanOne() {
        // Jackson binds an absent field to zero on a primitive int. A model that
        // omitted the field has not asked for a one-member council.
        assertEquals(CouncilRequirement.DEFAULT_COUNCIL_SIZE,
                     new CouncilRequirement(null, null, null, null, 0, null, false).councilSize());
    }

    @Test
    void anOversizedCouncilIsClampedToTheValidatorsBound() {
        assertEquals(ConfigLimits.MAX_MEMBERS,
                     new CouncilRequirement(null, null, null, null, 99, null, false).councilSize());
    }

    @Test
    void freeOnlyImpliesLocalOnlyWhateverThePrivacyChoiceSays() {
        assertTrue(new CouncilRequirement(CouncilRequirement.Privacy.CLOUD_OK, null,
                                          CouncilRequirement.Cost.FREE_ONLY, null, 3, null, false)
                           .localOnly());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private CouncilRequirement requirement(CouncilRequirement.Privacy privacy,
                                           CouncilRequirement.Rigor rigor, int size) {
        return new CouncilRequirement(privacy, CouncilRequirement.Latency.MODERATE,
                                      CouncilRequirement.Cost.LOW, rigor, size,
                                      Set.of(CouncilRequirement.Domain.GENERAL), false);
    }

    /** A machine with llama3.1:8b and mistral:7b pulled, plus any extra tags given. */
    private AdvisorEnvironment localMachine(String... extraTags) {
        List<String> installed = new ArrayList<>(List.of("llama3.1:8b", "mistral:7b"));
        installed.addAll(Arrays.asList(extraTags));
        return localMachine(installed);
    }

    private AdvisorEnvironment localMachine(List<String> installed) {
        return environment(installed, Map.of("ollama", ClientAvailability.LIVE), builtInModels());
    }

    private AdvisorEnvironment environment(List<String> installed,
                                           Map<String, ClientAvailability> providers,
                                           List<CandidateModel> models) {
        // Extraction candidates are irrelevant to synthesis and deliberately left
        // empty here: if selection ever started reading them, these tests would
        // notice by producing a different council.
        return new AdvisorEnvironment(installed, providers, models, List.of(), "local-chair",
                                      Instant.EPOCH);
    }

    /** The shipped catalog, with cloud models unavailable unless stated otherwise. */
    private List<CandidateModel> builtInModels() {
        return builtInModels(ClientAvailability.UNAVAILABLE);
    }

    private List<CandidateModel> builtInModels(ClientAvailability cloud) {
        return List.of(
                model("local-llama3", "ollama", "llama3.1:8b", "llama", ModelRole.MEMBER,
                      ClientAvailability.LIVE),
                model("local-mistral", "ollama", "mistral:7b", "mistral", ModelRole.MEMBER,
                      ClientAvailability.LIVE),
                model("local-validator", "ollama", "mistral:7b", "mistral", ModelRole.VALIDATOR,
                      ClientAvailability.LIVE),
                model("local-chair", "ollama", "llama3.1:8b", "llama", ModelRole.CHAIR,
                      ClientAvailability.LIVE),
                model("openai-gpt", "openai", "gpt-4o", "gpt", ModelRole.MEMBER, cloud),
                model("mock-member", "mock", "mock-member", "mock", ModelRole.MEMBER,
                      ClientAvailability.MOCK),
                model("mock-chair", "mock", "mock-chair", "mock", ModelRole.CHAIR,
                      ClientAvailability.MOCK));
    }

    private CandidateModel model(String id, String provider, String providerModelId, String family,
                                 ModelRole role, ClientAvailability availability) {
        return new CandidateModel(id, provider, providerModelId, family, false, role,
                                  CouncilRole.PROPOSER, 0, availability,
                                  CandidateModel.Source.BUILT_IN);
    }

    private UserConfigDocument existingUserConfiguration() {
        Map<String, UserPolicy> policies = new LinkedHashMap<>();
        policies.put("my-policy", new UserPolicy("balanced", List.of("my-critic"), "my-critic",
                                                 null, 1, 0, false, true, null));
        Map<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("my-council", new UserProfile("Mine", "BALANCED",
                                                   Map.of("BALANCED", "my-policy")));
        return new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION,
                List.of(new UserModel("my-critic", "ollama", "mistral:7b", 1200, 0.4, 240, null,
                                      "MEMBER", "CRITIC", "mistral", null, null, null, null)),
                policies, profiles, Map.of(),
                new UserConfigDocument.UserRuntime(2, 6, null, null));
    }

    // ── Assertions ──────────────────────────────────────────────────────

    private List<String> ids(List<UserModel> models) {
        return models.stream().map(UserModel::id).toList();
    }

    /** Every model id the synthesised council actually references. */
    private Set<String> seatedIds(SynthesisResult result) {
        Set<String> seated = new java.util.LinkedHashSet<>();
        result.document().policies().forEach((id, policy) -> {
            seated.addAll(policy.memberModelIds());
            seated.add(policy.chairModelId());
            if (policy.validatorModelId() != null) {
                seated.add(policy.validatorModelId());
            }
        });
        return seated;
    }

    private boolean hasWarning(SynthesisResult result, String fragment) {
        return result.issues().stream()
                     .anyMatch(issue -> issue.severity() == ConfigIssue.Severity.WARNING
                                        && issue.message().contains(fragment));
    }

    private ConfigIssue onlyError(SynthesisResult result) {
        List<ConfigIssue> errors = result.issues().stream()
                .filter(issue -> issue.severity() == ConfigIssue.Severity.ERROR)
                .toList();
        assertEquals(1, errors.size(), "expected exactly one error, got " + errors);
        return errors.getFirst();
    }
}
