package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.config.TestCatalogs;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.MockModelClient;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.orchestration.ProtocolDefinition;
import com.debopam.llmcouncil.orchestration.ProtocolStageOptions;
import com.debopam.llmcouncil.orchestration.StageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the capability boundary: what a user may change and what they may not.
 *
 * <p>Each rejection here protects something specific. Provider restrictions stop
 * a config file claiming an integration that does not exist; the {@code mock}
 * ban stops a real council producing fabricated output; the stage-option
 * allowlist stops a typo being accepted as a setting; the {@code orderedStages}
 * ban keeps anonymised review in the pipeline.
 */
class UserConfigValidatorTest {

    private final UserConfigValidator validator = new UserConfigValidator();

    // ── Models ──────────────────────────────────────────────────────────

    @Test
    void acceptsAWellFormedModel() {
        UserConfigDocument document = doc(List.of(model("my-qwen", "ollama", "qwen2.5:14b")),
                                          Map.of(), Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.errors().isEmpty(), () -> "unexpected errors: " + report.errors());
        assertEquals(1, report.sanitised().models().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bad-Id", "x", "has space", "UPPER", "trailing-", ""})
    void rejectsMalformedModelIds(String id) {
        UserConfigDocument document = doc(List.of(model(id, "ollama", "qwen2.5:14b")),
                                          Map.of(), Map.of(), Map.of());

        assertTrue(hasError(validator.validate(document, builtIn()), "id"),
                   "id '" + id + "' should have been rejected");
    }

    @Test
    void rejectsProvidersThatWouldNeedNewJavaCode() {
        UserConfigDocument document = doc(List.of(model("my-model", "cohere", "command-r")),
                                          Map.of(), Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(hasError(report, "provider"));
        assertTrue(report.errors().getFirst().remediation().contains("ModelClient"),
                   "the message should say adding a provider needs application code");
    }

    @Test
    void rejectsTheMockProviderSoRealCouncilsCannotFabricateOutput() {
        UserConfigDocument document = doc(List.of(model("fake", "mock", "fake")),
                                          Map.of(), Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(hasError(report, "provider"));
        assertTrue(report.errors().getFirst().remediation().contains("fabricated"));
    }

    @ParameterizedTest
    @CsvSource({
            "defaultOutputTokens, 63,      true",
            "defaultOutputTokens, 64,      false",
            "defaultOutputTokens, 32000,   false",
            "defaultOutputTokens, 32001,   true",
            "timeoutSeconds,      4,       true",
            "timeoutSeconds,      5,       false",
            "timeoutSeconds,      900,     false",
            "timeoutSeconds,      901,     true",
            "contextWindowTokens, 1023,    true",
            "contextWindowTokens, 1024,    false",
            "contextWindowTokens, 1000000, false",
            "contextWindowTokens, 1000001, true",
            "retryMaxAttempts,    -1,      true",
            "retryMaxAttempts,    0,       false",
            "retryMaxAttempts,    5,       false",
            "retryMaxAttempts,    6,       true"
    })
    void enforcesNumericClampsAtTheirBoundaries(String field, int value, boolean shouldReject) {
        UserConfigDocument.UserModel base = model("my-model", "ollama", "qwen2.5:14b");
        UserConfigDocument.UserModel candidate = switch (field) {
            case "defaultOutputTokens" -> withOutputTokens(base, value);
            case "timeoutSeconds" -> withTimeout(base, value);
            case "contextWindowTokens" -> withContextWindow(base, value);
            default -> withRetryAttempts(base, value);
        };

        UserConfigValidator.ValidationReport report =
                validator.validate(doc(List.of(candidate), Map.of(), Map.of(), Map.of()), builtIn());

        assertEquals(shouldReject, hasError(report, field),
                     field + "=" + value + (shouldReject ? " should be rejected" : " should be accepted"));
    }

    @ParameterizedTest
    @CsvSource({
            // Token prices. The upper bound catches a per-million figure pasted
            // into a per-thousand field, which would inflate every reported cost
            // a thousandfold and make the spend signal worse than absent.
            "costPer1kInputTokens,  -0.001,   true",
            "costPer1kInputTokens,  0.0,      false",
            "costPer1kInputTokens,  0.003,    false",
            "costPer1kInputTokens,  1000.0,   false",
            "costPer1kInputTokens,  1000.001, true",
            "costPer1kOutputTokens, -0.001,   true",
            "costPer1kOutputTokens, 0.0,      false",
            "costPer1kOutputTokens, 0.015,    false",
            "costPer1kOutputTokens, 1000.0,   false",
            "costPer1kOutputTokens, 1000.001, true"
    })
    void enforcesCostClampsAtTheirBoundaries(String field, double value, boolean shouldReject) {
        UserConfigDocument.UserModel base = model("my-model", "ollama", "qwen2.5:14b");
        UserConfigDocument.UserModel candidate = field.equals("costPer1kInputTokens")
                                                 ? withInputCost(base, value)
                                                 : withOutputCost(base, value);

        UserConfigValidator.ValidationReport report =
                validator.validate(doc(List.of(candidate), Map.of(), Map.of(), Map.of()), builtIn());

        assertEquals(shouldReject, hasError(report, field),
                     field + "=" + value + (shouldReject ? " should be rejected" : " should be accepted"));
    }

    @Test
    void aRejectedPriceDropsTheWholeModelRatherThanSilentlyZeroingThePrice() {
        // A model kept with its price quietly reset to zero would report as
        // unpriced, which reads as "no price configured" rather than "the price
        // you wrote was refused" — the user would never find out.
        UserConfigDocument.UserModel overpriced =
                withInputCost(model("pricey", "openai", "gpt-4o"), 5000.0);

        UserConfigValidator.ValidationReport report =
                validator.validate(doc(List.of(overpriced), Map.of(), Map.of(), Map.of()), builtIn());

        assertTrue(hasError(report, "costPer1kInputTokens"));
        assertTrue(report.sanitised().models().isEmpty(),
                   "A model whose price was refused must not survive validation");
    }

    @Test
    void rejectsTemperatureOutsideTheUsableRange() {
        UserConfigDocument.UserModel hot = new UserConfigDocument.UserModel(
                "hot", "ollama", "m", 1000, 2.5, 60, null, "MEMBER", "PROPOSER", "llama", null, null,
                null, null);

        assertTrue(hasError(validator.validate(doc(List.of(hot), Map.of(), Map.of(), Map.of()), builtIn()),
                            "temperature"));
    }

    @Test
    void warnsButAcceptsAModelWithNoFamilyTag() {
        UserConfigDocument.UserModel untagged = new UserConfigDocument.UserModel(
                "untagged", "ollama", "m", 1000, 0.3, 60, null, "MEMBER", "PROPOSER", null, null, null,
                null, null);

        UserConfigValidator.ValidationReport report =
                validator.validate(doc(List.of(untagged), Map.of(), Map.of(), Map.of()), builtIn());

        assertTrue(report.errors().isEmpty());
        assertTrue(report.warnings().stream().anyMatch(i -> "modelFamily".equals(i.field())));
    }

    // ── Protocols ───────────────────────────────────────────────────────

    @Test
    void rejectsAProtocolThatTriesToSetStageOrder() {
        // orderedStages is not a field on UserProtocol at all, so strict binding
        // rejects it at parse time. This asserts the type-level guarantee holds.
        assertFalse(java.util.Arrays.stream(UserConfigDocument.UserProtocol.class.getRecordComponents())
                                    .anyMatch(component -> component.getName().equals("orderedStages")),
                    "stage order must not be expressible in user configuration");
    }

    @Test
    void requiresDerivedFrom() {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of(),
                Map.of("mine", new UserConfigDocument.UserProtocol(null, null, Map.of())));

        assertTrue(hasError(validator.validate(document, builtIn()), "derivedFrom"));
    }

    @Test
    void refusesToRedefineABuiltInProtocol() {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of(),
                Map.of("balanced", new UserConfigDocument.UserProtocol("balanced", null, Map.of())));

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(hasError(report, "id"));
        assertTrue(report.errors().getFirst().remediation().contains("derivedFrom"));
    }

    @Test
    void rejectsStageOptionsThatWouldBeSilentlyIgnored() {
        // ProtocolStageOptions ignores unknown keys at run time, so accepting one
        // means accepting a typo the user believes took effect.
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of(),
                Map.of("mine", new UserConfigDocument.UserProtocol("balanced", null,
                        Map.of("DEBATE", Map.of("max-round", 2)))));

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(hasError(report, "stageOptions.DEBATE.max-round"));
        assertTrue(report.errors().getFirst().remediation().contains("max-rounds"),
                   "the message should list the options that do exist");
    }

    @ParameterizedTest
    @CsvSource({
            "DEBATE, min-rounds,                0,    true",
            "DEBATE, min-rounds,                1,    false",
            "DEBATE, min-rounds,                3,    false",
            "DEBATE, min-rounds,                4,    true",
            "DEBATE, max-rounds,                5,    false",
            "DEBATE, max-rounds,                6,    true",
            "DEBATE, ks-convergence-threshold,  0.005, true",
            "DEBATE, ks-convergence-threshold,  0.10, false",
            "DEBATE, ks-convergence-threshold,  0.51, true",
            "DEBATE, sycophancy-threshold,      0.29, true",
            "DEBATE, sycophancy-threshold,      0.70, false",
            "DEBATE, sycophancy-threshold,      0.96, true"
    })
    void enforcesStageOptionClamps(String stage, String option, String value, boolean shouldReject) {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of(),
                Map.of("mine", new UserConfigDocument.UserProtocol("rigorous", null,
                        Map.of(stage, Map.of(option, value)))));

        assertEquals(shouldReject,
                     hasError(validator.validate(document, builtIn()), "stageOptions." + stage + "." + option),
                     stage + "." + option + "=" + value);
    }

    @Test
    void rejectsMaxRoundsBelowMinRounds() {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of(),
                Map.of("mine", new UserConfigDocument.UserProtocol("rigorous", null,
                        Map.of("DEBATE", Map.of("min-rounds", 3, "max-rounds", 2)))));

        assertTrue(hasError(validator.validate(document, builtIn()), "stageOptions.DEBATE.max-rounds"));
    }

    @Test
    void warnsWhenAnOptionSuppressesSycophancyDetection() {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of(),
                Map.of("mine", new UserConfigDocument.UserProtocol("rigorous", null,
                        Map.of("DEBATE", Map.of("sycophancy-threshold", 0.90)))));

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.errors().isEmpty(), "0.90 is within range and must be permitted");
        assertTrue(report.warnings().stream().anyMatch(i -> i.message().contains("suppresses")),
                   "but it must be reported, since it hides the signal rather than improving it");
    }

    @Test
    void warnsWhenDissentPreservationIsTurnedOff() {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of(),
                Map.of("mine", new UserConfigDocument.UserProtocol("balanced", null,
                        Map.of("SYNTHESIZE", Map.of("preserve-dissent", false)))));

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.errors().isEmpty());
        assertTrue(report.warnings().stream().anyMatch(
                i -> i.message().contains("unresolved disagreement")
                     && i.remediation().contains("more confident")));
    }

    // ── Policies ────────────────────────────────────────────────────────

    @Test
    void rejectsAPolicyReferencingAnUnknownModel() {
        UserConfigDocument document = doc(List.of(), Map.of("mine",
                policy("balanced", List.of("does-not-exist"), "built-in-chair", null)), Map.of(), Map.of());

        assertTrue(hasError(validator.validate(document, builtIn()), "memberModelIds[0]"));
    }

    @Test
    void acceptsAPolicyReferencingBuiltInModels() {
        UserConfigDocument document = doc(List.of(), Map.of("mine",
                policy("balanced", List.of("built-in-member"), "built-in-chair", null)), Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.errors().isEmpty(), () -> "unexpected errors: " + report.errors());
    }

    @Test
    void rejectsARealPolicyDrawingOnATestOnlyModel() {
        UserConfigDocument document = doc(List.of(), Map.of("mine",
                policy("balanced", List.of("mock-only"), "built-in-chair", null)), Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(hasError(report, "memberModelIds[0]"));
        assertTrue(report.errors().getFirst().remediation().contains("fabricated"));
    }

    @Test
    void rejectsDuplicateMembers() {
        UserConfigDocument document = doc(List.of(), Map.of("mine",
                policy("balanced", List.of("built-in-member", "built-in-member"), "built-in-chair", null)),
                Map.of(), Map.of());

        assertTrue(hasError(validator.validate(document, builtIn()), "memberModelIds"));
    }

    @Test
    void rejectsACouncilLargerThanTheSupportedMaximum() {
        List<String> tooMany = List.of("built-in-member", "a", "b", "c", "d", "e", "f", "g", "h");
        UserConfigDocument document = doc(List.of(), Map.of("mine",
                policy("balanced", tooMany, "built-in-chair", null)), Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.errors().stream().anyMatch(i -> i.message().contains("at most 8")));
    }

    @Test
    void rejectsAQuorumLargerThanTheCouncil() {
        UserConfigDocument.UserPolicy policy = new UserConfigDocument.UserPolicy(
                "balanced", List.of("built-in-member"), "built-in-chair", null, 5, null, null, null, null);

        assertTrue(hasError(validator.validate(doc(List.of(), Map.of("mine", policy), Map.of(), Map.of()),
                                               builtIn()),
                            "minimumSuccessfulDrafts"));
    }

    @Test
    void rejectsValidationRequiredWithNoValidator() {
        UserConfigDocument.UserPolicy policy = new UserConfigDocument.UserPolicy(
                "balanced", List.of("built-in-member"), "built-in-chair", null, 1, 0, true, true, null);

        assertTrue(hasError(validator.validate(doc(List.of(), Map.of("mine", policy), Map.of(), Map.of()),
                                               builtIn()),
                            "validatorModelId"));
    }

    @Test
    void warnsWhenAUserPolicyLetsTheChairValidateItself() {
        UserConfigDocument document = doc(List.of(), Map.of("mine",
                policy("balanced", List.of("built-in-member"), "built-in-chair", "built-in-chair")),
                Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.errors().isEmpty(), "self-validation is permitted, not fatal");
        assertTrue(report.warnings().stream().anyMatch(i -> i.message().contains("blind spots")));
    }

    @Test
    void acknowledgementSilencesTheSelfValidationWarning() {
        UserConfigDocument.UserPolicy acknowledged = new UserConfigDocument.UserPolicy(
                "balanced", List.of("built-in-member"), "built-in-chair", "built-in-chair",
                1, 0, false, true, true);

        UserConfigValidator.ValidationReport report =
                validator.validate(doc(List.of(), Map.of("mine", acknowledged), Map.of(), Map.of()), builtIn());

        assertTrue(report.warnings().stream().noneMatch(i -> i.message().contains("blind spots")));
    }

    // ── Profiles ────────────────────────────────────────────────────────

    @Test
    void rejectsAProfileMappingAnUnknownPolicy() {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of("mine",
                new UserConfigDocument.UserProfile("Mine", "BALANCED",
                        Map.of("BALANCED", "no-such-policy"))), Map.of());

        assertTrue(hasError(validator.validate(document, builtIn()), "depthPolicies.BALANCED"));
    }

    @Test
    void rejectsOverridingABuiltInTestProfile() {
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of("mock",
                new UserConfigDocument.UserProfile("Hijacked", "QUICK",
                        Map.of("QUICK", "built-in-policy"))), Map.of());

        assertTrue(hasError(validator.validate(document, builtIn()), "id"));
    }

    @Test
    void allowsOverridingARealBuiltInProfile() {
        // Repointing the default profile at your own policies is the primary
        // intended use of the overlay.
        UserConfigDocument document = doc(List.of(), Map.of(), Map.of("default",
                new UserConfigDocument.UserProfile(null, null,
                        Map.of("BALANCED", "built-in-policy"))), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.errors().isEmpty(), () -> "unexpected errors: " + report.errors());
        assertTrue(report.warnings().stream().anyMatch(i -> i.message().contains("overrides a built-in")));
    }

    // ── Runtime ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"0, true", "1, false", "8, false", "9, true"})
    void clampsMaxConcurrentRuns(int value, boolean shouldReject) {
        UserConfigDocument document = new UserConfigDocument(1, List.of(), Map.of(), Map.of(), Map.of(),
                new UserConfigDocument.UserRuntime(value, null, null));

        assertEquals(shouldReject, hasError(validator.validate(document, builtIn()), "maxConcurrentRuns"));
    }

    @Test
    void rejectsARelativeArtifactPath() {
        UserConfigDocument document = new UserConfigDocument(1, List.of(), Map.of(), Map.of(), Map.of(),
                new UserConfigDocument.UserRuntime(null, null, "runs/here"));

        assertTrue(hasError(validator.validate(document, builtIn()), "artifactBasePath"));
    }

    // ── Cascade ─────────────────────────────────────────────────────────

    @Test
    void droppingAModelAlsoDropsWhateverDependedOnIt() {
        // A surviving policy pointing at a rejected model would fail at request
        // time, long after anyone was reading the startup log.
        UserConfigDocument document = doc(
                List.of(model("Bad-Id", "ollama", "m")),
                Map.of("uses-bad", policy("balanced", List.of("Bad-Id"), "built-in-chair", null)),
                Map.of("uses-policy", new UserConfigDocument.UserProfile("P", "BALANCED",
                        Map.of("BALANCED", "uses-bad"))),
                Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertTrue(report.sanitised().models().isEmpty());
        assertTrue(report.sanitised().policies().isEmpty(), "the policy referencing it must go too");
        assertTrue(report.sanitised().profiles().isEmpty(), "and the profile referencing that policy");
        // Models are validated before policies, so the policy is rejected
        // directly for naming an unknown model rather than by the cascade sweep.
        // Either way nothing survives pointing at a hole.
        assertTrue(report.errors().stream().anyMatch(i -> i.entityKey().equals("policy:uses-bad")));
        assertTrue(report.errors().stream().anyMatch(i -> i.entityKey().equals("profile:uses-policy")));
    }

    @Test
    void oneBadEntityDoesNotTakeGoodOnesWithIt() {
        UserConfigDocument document = doc(
                List.of(model("good-model", "ollama", "qwen2.5:14b"), model("Bad-Id", "ollama", "m")),
                Map.of(), Map.of(), Map.of());

        UserConfigValidator.ValidationReport report = validator.validate(document, builtIn());

        assertEquals(1, report.sanitised().models().size());
        assertEquals("good-model", report.sanitised().models().getFirst().id());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private boolean hasError(UserConfigValidator.ValidationReport report, String field) {
        return report.errors().stream().anyMatch(issue -> field.equals(issue.field()));
    }

    private UserConfigDocument doc(List<UserConfigDocument.UserModel> models,
                                   Map<String, UserConfigDocument.UserPolicy> policies,
                                   Map<String, UserConfigDocument.UserProfile> profiles,
                                   Map<String, UserConfigDocument.UserProtocol> protocols) {
        return new UserConfigDocument(1, models, policies, profiles, protocols, null);
    }

    private UserConfigDocument.UserModel model(String id, String provider, String providerModelId) {
        return new UserConfigDocument.UserModel(id, provider, providerModelId, 1200, 0.3, 120,
                                                null, "MEMBER", "PROPOSER", "qwen", null, null,
                                                null, null);
    }

    private UserConfigDocument.UserModel withOutputTokens(UserConfigDocument.UserModel m, int v) {
        return new UserConfigDocument.UserModel(m.id(), m.provider(), m.providerModelId(), v,
                m.temperature(), m.timeoutSeconds(), m.contextWindowTokens(), m.role(),
                m.councilRole(), m.modelFamily(), m.retryMaxAttempts(), m.retryBaseDelayMs(),
                m.costPer1kInputTokens(), m.costPer1kOutputTokens());
    }

    private UserConfigDocument.UserModel withTimeout(UserConfigDocument.UserModel m, int v) {
        return new UserConfigDocument.UserModel(m.id(), m.provider(), m.providerModelId(),
                m.defaultOutputTokens(), m.temperature(), v, m.contextWindowTokens(), m.role(),
                m.councilRole(), m.modelFamily(), m.retryMaxAttempts(), m.retryBaseDelayMs(),
                m.costPer1kInputTokens(), m.costPer1kOutputTokens());
    }

    private UserConfigDocument.UserModel withContextWindow(UserConfigDocument.UserModel m, int v) {
        return new UserConfigDocument.UserModel(m.id(), m.provider(), m.providerModelId(),
                m.defaultOutputTokens(), m.temperature(), m.timeoutSeconds(), v, m.role(),
                m.councilRole(), m.modelFamily(), m.retryMaxAttempts(), m.retryBaseDelayMs(),
                m.costPer1kInputTokens(), m.costPer1kOutputTokens());
    }

    private UserConfigDocument.UserModel withRetryAttempts(UserConfigDocument.UserModel m, int v) {
        return new UserConfigDocument.UserModel(m.id(), m.provider(), m.providerModelId(),
                m.defaultOutputTokens(), m.temperature(), m.timeoutSeconds(), m.contextWindowTokens(),
                m.role(), m.councilRole(), m.modelFamily(), v, m.retryBaseDelayMs(),
                m.costPer1kInputTokens(), m.costPer1kOutputTokens());
    }

    private UserConfigDocument.UserModel withInputCost(UserConfigDocument.UserModel m, double v) {
        return new UserConfigDocument.UserModel(m.id(), m.provider(), m.providerModelId(),
                m.defaultOutputTokens(), m.temperature(), m.timeoutSeconds(), m.contextWindowTokens(),
                m.role(), m.councilRole(), m.modelFamily(), m.retryMaxAttempts(), m.retryBaseDelayMs(),
                v, m.costPer1kOutputTokens());
    }

    private UserConfigDocument.UserModel withOutputCost(UserConfigDocument.UserModel m, double v) {
        return new UserConfigDocument.UserModel(m.id(), m.provider(), m.providerModelId(),
                m.defaultOutputTokens(), m.temperature(), m.timeoutSeconds(), m.contextWindowTokens(),
                m.role(), m.councilRole(), m.modelFamily(), m.retryMaxAttempts(), m.retryBaseDelayMs(),
                m.costPer1kInputTokens(), v);
    }

    private UserConfigDocument.UserPolicy policy(String protocolId, List<String> members,
                                                 String chair, String validator) {
        return new UserConfigDocument.UserPolicy(protocolId, members, chair, validator,
                                                 1, 0, false, true, null);
    }

    /** A built-in catalog with one member, one chair, one mock, and one policy. */
    private CouncilCatalog builtIn() {
        Map<String, ModelProfile> models = Map.of(
                "built-in-member", profile("built-in-member", "ollama", ModelRole.MEMBER, "llama"),
                "built-in-chair", profile("built-in-chair", "ollama", ModelRole.CHAIR, "llama"),
                "mock-only", profile("mock-only", "mock", ModelRole.MEMBER, "mock"));
        Map<String, ModelClient> clients = Map.of(
                "built-in-member", new MockModelClient("built-in-member"),
                "built-in-chair", new MockModelClient("built-in-chair"),
                "mock-only", new MockModelClient("mock-only"));

        Map<String, CouncilPolicy> policies = Map.of("built-in-policy",
                new CouncilPolicy("built-in-policy", "balanced", List.of("built-in-member"),
                                  "built-in-chair", null, 1, 0, false, true));
        Map<String, CouncilProfile> profiles = Map.of(
                "default", new CouncilProfile("default", "Default", false, DepthMode.BALANCED,
                                              Map.of(DepthMode.BALANCED, "built-in-policy")),
                "mock", new CouncilProfile("mock", "Mock", true, DepthMode.QUICK,
                                           Map.of(DepthMode.QUICK, "built-in-policy")));
        Map<String, ProtocolDefinition> protocols = Map.of(
                "balanced", new ProtocolDefinition("balanced", "Balanced",
                        List.of(StageType.GENERATE, StageType.SYNTHESIZE), Map.of()),
                "rigorous", new ProtocolDefinition("rigorous", "Rigorous",
                        List.of(StageType.GENERATE, StageType.DEBATE, StageType.SYNTHESIZE),
                        Map.of(StageType.DEBATE, new ProtocolStageOptions(Map.of("max-rounds", 3)))));

        return TestCatalogs.catalog(new ModelRegistry(models, clients), profiles, policies, protocols);
    }

    private ModelProfile profile(String id, String provider, ModelRole role, String family) {
        return new ModelProfile(id, provider, id + "-model", 1200, 0.3, Duration.ofSeconds(60),
                                role, CouncilRole.PROPOSER, family, 4096);
    }
}
