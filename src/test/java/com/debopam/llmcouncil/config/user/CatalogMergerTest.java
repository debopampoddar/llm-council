package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigOrigin;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how an overlay combines with built-in configuration.
 *
 * <p>The merge rules differ by shape on purpose: scalars replace, maps merge per
 * key, lists replace wholesale. Getting this wrong is quiet — a user who edits
 * one depth of a profile and silently loses the other two would not find out
 * until they selected one of them.
 */
class CatalogMergerTest {

    private final CatalogMerger merger = new CatalogMerger(profile -> new MockModelClient(profile.id()));

    @Test
    void addsANewModelAndStampsItAsUserDefined() {
        UserConfigDocument overlay = overlay(List.of(model("my-qwen")), Map.of(), Map.of(), Map.of());

        CouncilCatalog merged = merger.merge(builtIn(), overlay, List.of(), 2);

        assertTrue(merged.modelRegistry().findModel("my-qwen").isPresent());
        assertEquals(ConfigOrigin.USER, merged.originOf("model", "my-qwen"));
        assertNotNull(merged.modelRegistry().clientForModel("my-qwen"));
    }

    @Test
    void builtInEntitiesSurviveAnOverlayThatDoesNotMentionThem() {
        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(model("my-qwen")), Map.of(), Map.of(), Map.of()), List.of(), 2);

        assertTrue(merged.modelRegistry().findModel("built-in-chair").isPresent());
        assertTrue(merged.policies().containsKey("built-in-policy"));
        assertEquals(ConfigOrigin.BUILT_IN, merged.originOf("model", "built-in-chair"));
    }

    @Test
    void overridingAModelKeepsFieldsTheUserDidNotMention() {
        // Changing temperature alone should not reset the model's tokens, role,
        // or provider binding to defaults.
        UserConfigDocument.UserModel patch = new UserConfigDocument.UserModel(
                "built-in-chair", null, null, null, 0.9, null, null, null, null, null, null, null);

        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(patch), Map.of(), Map.of(), Map.of()), List.of(), 2);

        ModelProfile chair = merged.modelRegistry().model("built-in-chair");
        assertEquals(0.9, chair.temperature(), "the field the user set should change");
        assertEquals("ollama", chair.provider(), "and everything else should be preserved");
        assertEquals(1200, chair.defaultOutputTokens());
        assertEquals(ModelRole.CHAIR, chair.role());
        assertEquals(ConfigOrigin.USER_OVERRIDE, merged.originOf("model", "built-in-chair"));
    }

    @Test
    void overridingOneDepthOfAProfileKeepsTheOthers() {
        // The primary intended use of the overlay: repoint the default profile's
        // BALANCED depth without restating QUICK and RIGOROUS.
        UserConfigDocument.UserProfile patch = new UserConfigDocument.UserProfile(
                null, null, Map.of("BALANCED", "built-in-policy-2"));

        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(), Map.of(), Map.of("default", patch), Map.of()), List.of(), 2);

        CouncilProfile profile = merged.profiles().get("default");
        assertEquals("built-in-policy-2", profile.depthPolicyIds().get(DepthMode.BALANCED));
        assertEquals("built-in-policy", profile.depthPolicyIds().get(DepthMode.QUICK),
                     "QUICK was not mentioned and must be preserved");
        assertEquals("Default", profile.displayName(), "displayName was not mentioned either");
    }

    @Test
    void memberListsAreReplacedNotMerged() {
        // There is no partial merge of an ordered roster that a user could predict.
        UserConfigDocument.UserPolicy patch = new UserConfigDocument.UserPolicy(
                null, List.of("built-in-member"), null, null, null, null, null, null, null);

        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(), Map.of("built-in-policy", patch), Map.of(), Map.of()), List.of(), 2);

        assertEquals(List.of("built-in-member"), merged.policies().get("built-in-policy").memberModelIds());
    }

    @Test
    void aDerivedProtocolInheritsStageOrderAndTunesOptions() {
        UserConfigDocument.UserProtocol tuned = new UserConfigDocument.UserProtocol(
                "rigorous", null, Map.of("DEBATE", Map.of("max-rounds", 2)));

        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(), Map.of(), Map.of(), Map.of("my-rigorous", tuned)), List.of(), 2);

        ProtocolDefinition derived = merged.protocols().get("my-rigorous");
        assertEquals(merged.protocols().get("rigorous").orderedStages(), derived.orderedStages(),
                     "stage order is inherited, never authored");
        assertEquals(2, derived.optionsFor(StageType.DEBATE).getInt("max-rounds", -1));
        assertEquals(0.1, derived.optionsFor(StageType.DEBATE).getDouble("ks-convergence-threshold", -1),
                     "options the user did not tune keep the base protocol's values");
    }

    @Test
    void derivingAProtocolLeavesTheOriginalUntouched() {
        UserConfigDocument.UserProtocol tuned = new UserConfigDocument.UserProtocol(
                "rigorous", null, Map.of("DEBATE", Map.of("max-rounds", 2)));

        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(), Map.of(), Map.of(), Map.of("my-rigorous", tuned)), List.of(), 2);

        assertEquals(3, merged.protocols().get("rigorous").optionsFor(StageType.DEBATE)
                                .getInt("max-rounds", -1));
    }

    @Test
    void aUserProfileCannotBecomeTestOnly() {
        // testOnly is what keeps mock models out of real councils, so it is not
        // something an overlay can set.
        UserConfigDocument.UserProfile patch = new UserConfigDocument.UserProfile(
                "Mine", "BALANCED", Map.of("BALANCED", "built-in-policy"));

        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(), Map.of(), Map.of("mine", patch), Map.of()), List.of(), 2);

        assertEquals(false, merged.profiles().get("mine").testOnly());
    }

    @Test
    void generationAdvancesSoReadersCanSeeTheCatalogChanged() {
        CouncilCatalog merged = merger.merge(builtIn(),
                overlay(List.of(model("my-qwen")), Map.of(), Map.of(), Map.of()), List.of(), 7);

        assertEquals(7, merged.generation());
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private UserConfigDocument overlay(List<UserConfigDocument.UserModel> models,
                                       Map<String, UserConfigDocument.UserPolicy> policies,
                                       Map<String, UserConfigDocument.UserProfile> profiles,
                                       Map<String, UserConfigDocument.UserProtocol> protocols) {
        return new UserConfigDocument(1, models, policies, profiles, protocols, null);
    }

    private UserConfigDocument.UserModel model(String id) {
        return new UserConfigDocument.UserModel(id, "ollama", "qwen2.5:14b", 1600, 0.3, 120,
                                                8192, "MEMBER", "CRITIC", "qwen", null, null);
    }

    private CouncilCatalog builtIn() {
        Map<String, ModelProfile> models = Map.of(
                "built-in-member", profile("built-in-member", ModelRole.MEMBER),
                "built-in-chair", profile("built-in-chair", ModelRole.CHAIR));
        Map<String, ModelClient> clients = Map.of(
                "built-in-member", new MockModelClient("built-in-member"),
                "built-in-chair", new MockModelClient("built-in-chair"));

        Map<String, CouncilPolicy> policies = Map.of(
                "built-in-policy", new CouncilPolicy("built-in-policy", "balanced",
                        List.of("built-in-member", "built-in-chair"), "built-in-chair", null,
                        1, 0, false, true),
                "built-in-policy-2", new CouncilPolicy("built-in-policy-2", "balanced",
                        List.of("built-in-member"), "built-in-chair", null, 1, 0, false, true));

        Map<String, CouncilProfile> profiles = Map.of("default",
                new CouncilProfile("default", "Default", false, DepthMode.BALANCED,
                        Map.of(DepthMode.QUICK, "built-in-policy",
                               DepthMode.BALANCED, "built-in-policy",
                               DepthMode.RIGOROUS, "built-in-policy")));

        Map<String, ProtocolDefinition> protocols = Map.of(
                "balanced", new ProtocolDefinition("balanced", "Balanced",
                        List.of(StageType.GENERATE, StageType.SYNTHESIZE), Map.of()),
                "rigorous", new ProtocolDefinition("rigorous", "Rigorous",
                        List.of(StageType.GENERATE, StageType.DEBATE, StageType.SYNTHESIZE),
                        Map.of(StageType.DEBATE, new ProtocolStageOptions(
                                Map.of("max-rounds", 3, "ks-convergence-threshold", 0.1)))));

        return TestCatalogs.catalog(new ModelRegistry(models, clients), profiles, policies, protocols);
    }

    private ModelProfile profile(String id, ModelRole role) {
        return new ModelProfile(id, "ollama", id + "-model", 1200, 0.3, Duration.ofSeconds(60),
                                role, CouncilRole.PROPOSER, "llama", 4096);
    }
}
