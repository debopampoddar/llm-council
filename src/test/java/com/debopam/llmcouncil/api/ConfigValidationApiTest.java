package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading, validating, and previewing configuration — none of which may write.
 *
 * <p>The application boots with a real overlay in force, so the diff assertions
 * have something to be a diff <em>from</em>. Against an installation with no
 * overlay, "this draft removes nothing" would hold whatever the code did.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "council.userConfigPath=src/test/resources/user-config/partially-invalid.yml")
class ConfigValidationApiTest {

    private static final Path OVERLAY =
            Path.of("src/test/resources/user-config/partially-invalid.yml");

    /** A configuration the validator accepts, used as the baseline for the rest. */
    private static final String VALID = """
            {
              "version": 1,
              "models": [{
                "id": "my-critic",
                "provider": "ollama",
                "providerModelId": "qwen2.5:14b",
                "temperature": 0.35,
                "role": "MEMBER",
                "councilRole": "CRITIC",
                "modelFamily": "qwen"
              }]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    // ── Draft ───────────────────────────────────────────────────────────

    @Test
    void readsTheOverlayCurrentlyOnDisk() throws Exception {
        mockMvc.perform(get("/api/council/config/draft"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.version").value(1))
               .andExpect(jsonPath("$.models[?(@.id == 'my-critic')]").isNotEmpty())
               .andExpect(jsonPath("$.profiles['my-council']").exists());
    }

    // ── Validate ────────────────────────────────────────────────────────

    @Test
    void acceptsAValidConfiguration() throws Exception {
        mockMvc.perform(validate(VALID))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(true))
               .andExpect(jsonPath("$.errorCount").value(0));
    }

    @Test
    void rejectsAProviderTheApplicationCannotCall() throws Exception {
        mockMvc.perform(validate("""
                       {"models": [{"id": "x-model", "provider": "cohere",
                                    "providerModelId": "command-r", "role": "MEMBER"}]}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(jsonPath("$.issues[?(@.field == 'provider')]").isNotEmpty())
               .andExpect(jsonPath("$.issues[?(@.entityKey == 'model:x-model')]").isNotEmpty());
    }

    @Test
    void rejectsMockAsAUserProviderAndSaysWhy() throws Exception {
        mockMvc.perform(validate("""
                       {"models": [{"id": "fake-model", "provider": "mock",
                                    "providerModelId": "anything", "role": "MEMBER"}]}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(content().string(containsString("fabricated output")));
    }

    @Test
    void rejectsAValueOutsideItsClamp() throws Exception {
        mockMvc.perform(validate("""
                       {"models": [{"id": "hot-model", "provider": "ollama",
                                    "providerModelId": "llama3.1:8b", "temperature": 7.5,
                                    "role": "MEMBER"}]}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(jsonPath("$.issues[?(@.field == 'temperature')]").isNotEmpty());
    }

    @Test
    void rejectsAPolicyReferencingAModelThatDoesNotExist() throws Exception {
        mockMvc.perform(validate("""
                       {"policies": {"my-policy": {"protocolId": "balanced",
                                                   "memberModelIds": ["nope"],
                                                   "chairModelId": "local-chair"}}}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(jsonPath("$.issues[?(@.field == 'memberModelIds[0]')]").isNotEmpty());
    }

    @Test
    void refusesAProtocolThatSuppliesItsOwnStageOrder() throws Exception {
        // Not a silent ignore: stage order is the deliberation design, and a user
        // who wrote it and saw it disappear would reasonably assume it applied.
        mockMvc.perform(validate("""
                       {"protocols": {"my-protocol": {"derivedFrom": "rigorous",
                                                      "orderedStages": ["GENERATE", "SYNTHESIZE"]}}}
                       """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(content().string(containsString("orderedStages")));
    }

    @Test
    void rejectsAStageOptionThatIsNotTunable() throws Exception {
        mockMvc.perform(validate("""
                       {"protocols": {"my-protocol": {"derivedFrom": "rigorous",
                                       "stageOptions": {"DEBATE": {"max-round": 2}}}}}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(jsonPath("$.issues[?(@.field == 'stageOptions.DEBATE.max-round')]")
                                  .isNotEmpty());
    }

    @Test
    void rejectsAProtocolThatWouldReplaceABuiltIn() throws Exception {
        mockMvc.perform(validate("""
                       {"protocols": {"rigorous": {"derivedFrom": "rigorous"}}}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(jsonPath("$.issues[?(@.entityKey == 'protocol:rigorous')]").isNotEmpty());
    }

    @Test
    void warnsWithoutRejectingWhenDissentPreservationIsSwitchedOff() throws Exception {
        // Permitted, and flagged. A council that hides its own disagreement
        // produces an answer that reads as more confident than it was.
        mockMvc.perform(validate("""
                       {"protocols": {"confident": {"derivedFrom": "rigorous",
                                       "stageOptions": {"SYNTHESIZE": {"preserve-dissent": false}}}}}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(true))
               .andExpect(jsonPath("$.integrityReduced").value(true))
               .andExpect(jsonPath("$.warningCount").value(1));
    }

    @Test
    void aSycophancyThresholdAtTheDefaultReducesNothing() throws Exception {
        // Positive control for the flag above: it must distinguish a value that
        // weakens the guarantee from one that merely could.
        mockMvc.perform(validate("""
                       {"protocols": {"tuned": {"derivedFrom": "rigorous",
                                       "stageOptions": {"DEBATE": {"sycophancy-threshold": 0.7}}}}}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.valid").value(true))
               .andExpect(jsonPath("$.integrityReduced").value(false));

        mockMvc.perform(validate("""
                       {"protocols": {"tuned": {"derivedFrom": "rigorous",
                                       "stageOptions": {"DEBATE": {"sycophancy-threshold": 0.92}}}}}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.integrityReduced").value(true));
    }

    // ── Credentials ─────────────────────────────────────────────────────

    @Test
    void refusesADocumentCarryingAnApiKeyWithoutEchoingIt() throws Exception {
        String secret = "sk-ant-do-not-echo-this-value-0123456789";

        mockMvc.perform(validate("""
                       {"models": [{"id": "leaky", "provider": "anthropic",
                                    "providerModelId": "claude-sonnet-4",
                                    "apiKey": "%s"}]}
                       """.formatted(secret)))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.valid").value(false))
               .andExpect(content().string(containsString("apiKey")))
               .andExpect(content().string(not(containsString(secret))));
    }

    @Test
    void refusesAKeyHiddenInAFieldThatTakesFreeText() throws Exception {
        String secret = "sk-ant-hidden-in-a-model-name-0123456789";

        mockMvc.perform(validate("""
                       {"models": [{"id": "leaky", "provider": "anthropic",
                                    "providerModelId": "%s"}]}
                       """.formatted(secret)))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(not(containsString(secret))));
    }

    // ── Preview ─────────────────────────────────────────────────────────

    @Test
    void previewsWhatAConfigurationWouldAdd() throws Exception {
        mockMvc.perform(preview("""
                       {"models": [{"id": "new-model", "provider": "ollama",
                                    "providerModelId": "phi4", "role": "MEMBER",
                                    "modelFamily": "phi"}]}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.changes[?(@.id == 'new-model' && @.change == 'ADDED')]")
                                  .isNotEmpty())
               .andExpect(jsonPath("$.profiles[?(@.id == 'mock')]").isNotEmpty());
    }

    @Test
    void previewsShadowingABuiltInAsAnOverrideRatherThanAnAddition() throws Exception {
        mockMvc.perform(preview("""
                       {"profiles": {"local": {"displayName": "My local council"}}}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.changes[?(@.id == 'local' && @.change == 'OVERRIDDEN')]")
                                  .isNotEmpty());
    }

    @Test
    void previewsWhatAnEmptyConfigurationWouldTakeAway() throws Exception {
        // The running overlay defines these. Dropping them from the draft is the
        // only way a user can lose an entity, and it must be visible before saving.
        mockMvc.perform(preview("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.changes[?(@.id == 'my-critic' && @.change == 'REMOVED')]")
                                  .isNotEmpty())
               .andExpect(jsonPath("$.changes[?(@.id == 'my-council' && @.change == 'REMOVED')]")
                                  .isNotEmpty())
               // A built-in is never lost, however empty the draft.
               .andExpect(jsonPath("$.changes[?(@.id == 'mock')]").isEmpty())
               .andExpect(jsonPath("$.profiles[?(@.id == 'local')]").isNotEmpty());
    }

    @Test
    void previewsOnlyWhatWouldSurviveValidation() throws Exception {
        // The bad model is dropped, so it is absent from the diff — and the
        // report travelling with the diff is what says so.
        mockMvc.perform(preview("""
                       {"models": [
                         {"id": "good-model", "provider": "ollama", "providerModelId": "phi4",
                          "role": "MEMBER", "modelFamily": "phi"},
                         {"id": "bad-model", "provider": "ollama", "providerModelId": "phi4",
                          "temperature": 9.9, "role": "MEMBER", "modelFamily": "phi"}]}
                       """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.changes[?(@.id == 'good-model')]").isNotEmpty())
               .andExpect(jsonPath("$.changes[?(@.id == 'bad-model')]").isEmpty())
               .andExpect(jsonPath("$.validation.valid").value(false))
               .andExpect(jsonPath("$.validation.issues[?(@.entityKey == 'model:bad-model')]")
                                  .isNotEmpty());
    }

    // ── No writes ───────────────────────────────────────────────────────

    @Test
    void neitherValidateNorPreviewTouchesTheFile() throws Exception {
        byte[] before = Files.readAllBytes(OVERLAY);

        mockMvc.perform(validate(VALID)).andExpect(status().isOk());
        mockMvc.perform(preview(VALID)).andExpect(status().isOk());
        mockMvc.perform(preview("{}")).andExpect(status().isOk());

        assertArrayEquals(before, Files.readAllBytes(OVERLAY),
                          "validate and preview are pure functions and must not write");
    }

    @Test
    void theRunningCatalogIsUnaffectedByAPreview() throws Exception {
        mockMvc.perform(preview("{}")).andExpect(status().isOk());

        // The preview merged a catalog without the overlay. If that catalog had
        // been installed rather than discarded, this would be gone.
        mockMvc.perform(get("/api/council/catalog")
                                .param("include", "models")
                                .param("includeTestOnly", "true"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.models[?(@.id == 'my-critic')]").isNotEmpty());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.RequestBuilder validate(String body) {
        return post("/api/council/config/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.RequestBuilder preview(String body) {
        return post("/api/council/config/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
    }
}
