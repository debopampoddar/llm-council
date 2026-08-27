package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The advisor over HTTP.
 *
 * <p>Ollama is pointed at a port nothing listens on, so this class describes a
 * machine with no local models whatever is running on the host. Without that,
 * "no council could be seated" would pass on a build agent and fail on a
 * developer's laptop — the kind of test that gets deleted rather than fixed.
 *
 * <p>The consequence is that the success path is not reachable here, and it is
 * not faked either. Synthesis against a real environment is covered by
 * {@code AdvisorEndToEndTest} and the proposal lifecycle by
 * {@code ProposalLifecycleTest}, both of which supply an installed list
 * directly. What this class is for is the surface: the refusals, the response
 * shapes, and what must never appear in either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.ai.ollama.base-url=http://127.0.0.1:1",
        // These refusals describe a machine without cloud providers.  Pin the
        // credentials so a developer's real shell credentials neither make a
        // billable extraction nor change the advertised extraction allowlist.
        "spring.ai.openai.api-key=unused-development-placeholder",
        "spring.ai.anthropic.api-key=unused-development-placeholder",
        "spring.ai.vertex.ai.gemini.project-id=",
        "council.userConfigPath=target/advisor-api/council-user.yml"
})
class AdvisorApiTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void clearAnyProposal() throws Exception {
        mockMvc.perform(delete("/api/council/advisor/proposal"));
    }

    // ── Environment ─────────────────────────────────────────────────────

    @Test
    void theEnvironmentReportsWhatIsInstalledAndWhatToDoAboutIt() throws Exception {
        mockMvc.perform(get("/api/council/advisor/environment"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.installedLocalModels").isArray())
               .andExpect(jsonPath("$.providers").isArray())
               .andExpect(jsonPath("$.extractionModels").isArray())
               .andExpect(jsonPath("$.remediation[0]", containsString("ollama pull")))
               .andExpect(jsonPath("$.probedAt").exists());
    }

    @Test
    void theEnvironmentNeverPreSelectsACloudModel() throws Exception {
        // With nothing installed there is no local model to default to, and a
        // cloud model must not fill the gap: pre-selecting one would turn the
        // acknowledgement into a click-through.
        mockMvc.perform(get("/api/council/advisor/environment"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.defaultExtractionModelId").doesNotExist());
    }

    @Test
    void theEnvironmentIsStructurallyIncapableOfCarryingACredential() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/council/advisor/environment"))
                                  .andExpect(status().isOk())
                                  .andReturn();

        String body = result.getResponse().getContentAsString().toLowerCase(Locale.ROOT);
        for (String forbidden : new String[]{"api-key", "apikey", "secret", "token", "password"}) {
            assertFalse(body.contains(forbidden),
                        "the environment response must not mention " + forbidden + ": " + body);
        }
    }

    @Test
    void aMockModelIsNeverOfferedForExtraction() throws Exception {
        // mock-chair is in the catalog and is always callable. It is excluded
        // because its output is fabricated, not because it is unavailable.
        mockMvc.perform(get("/api/council/advisor/environment"))
               .andExpect(status().isOk())
               .andExpect(content().string(not(containsString("mock-chair"))));
    }

    // ── Extraction refusals ─────────────────────────────────────────────

    @Test
    void anUnknownModelIdIsRefusedWithTheListOfWhatMayBeUsed() throws Exception {
        mockMvc.perform(post("/api/council/advisor/extract")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"text":"a careful local council","modelId":"gpt-4o"}
                                         """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("gpt-4o")))
               .andExpect(jsonPath("$.remediation").exists());
    }

    @Test
    void aMockModelIdIsRefusedForExtraction() throws Exception {
        mockMvc.perform(post("/api/council/advisor/extract")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"text":"anything","modelId":"mock-chair"}
                                         """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("mock-chair")));
    }

    @Test
    void anAcknowledgementDoesNotMakeAnUnofferedModelUsable() throws Exception {
        // The acknowledgement is about where a description goes, not about
        // widening the allowlist.
        mockMvc.perform(post("/api/council/advisor/extract")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"text":"anything","modelId":"openai-gpt",
                                          "acknowledgeCloudProvider":true}
                                         """))
               .andExpect(status().isBadRequest());
    }

    @Test
    void aRefusalNeverEchoesTheDescription() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/council/advisor/extract")
                                                   .contentType(MediaType.APPLICATION_JSON)
                                                   .content("""
                                                            {"text":"zx9-distinctive-marker",
                                                             "modelId":"nope"}
                                                            """))
                                  .andExpect(status().isBadRequest())
                                  .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("zx9-distinctive-marker"),
                    "the description is data; it does not travel back through error bodies");
    }

    @Test
    void anUnknownFieldOnAnExtractRequestIsRejected() throws Exception {
        // Strict binding all the way in. A caller that thinks it can pass a
        // provider or a protocol here should find out rather than have it
        // silently ignored.
        mockMvc.perform(post("/api/council/advisor/extract")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"text":"x","modelId":"y","protocolId":"rigorous"}
                                         """))
               .andExpect(status().isBadRequest());
    }

    // ── Synthesis ───────────────────────────────────────────────────────

    @Test
    void synthesisingWithNothingInstalledAnswersRatherThanFailing() throws Exception {
        // A machine with nothing to seat is a well-formed question with a real
        // answer, not a malformed request.
        mockMvc.perform(post("/api/council/advisor/synthesize")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"requirement":{"privacy":"LOCAL_ONLY","latency":"MODERATE",
                                          "cost":"FREE_ONLY","rigor":"BALANCED","councilSize":3,
                                          "domains":["CODE"],"adversarialEmphasis":true}}
                                         """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.profileId").doesNotExist())
               .andExpect(jsonPath("$.issues[0].severity", is("ERROR")))
               .andExpect(jsonPath("$.issues[0].remediation", containsString("ollama pull")))
               .andExpect(jsonPath("$.validation").exists())
               .andExpect(jsonPath("$.preview").exists());
    }

    @Test
    void synthesisNeverReturnsAProtocolIdToRun() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/council/advisor/synthesize")
                                                   .contentType(MediaType.APPLICATION_JSON)
                                                   .content("{}"))
                                  .andExpect(status().isOk())
                                  .andReturn();

        // Profiles and depths are what a caller selects. A protocol id reachable
        // from here would be a way around quorum, validation, and cost controls.
        assertFalse(result.getResponse().getContentAsString().contains("\"protocolId\":\"rigorous\""),
                    "the advisor defines profiles; it does not hand out protocols to run");
    }

    @Test
    void anEmptySynthesisRequestUsesTheDefaultRequirement() throws Exception {
        mockMvc.perform(post("/api/council/advisor/synthesize")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.issues").isArray());
    }

    @Test
    void anUnknownEnumValueInARequirementIsRejected() throws Exception {
        mockMvc.perform(post("/api/council/advisor/synthesize")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"requirement":{"privacy":"WHENEVER"}}
                                         """))
               .andExpect(status().isBadRequest());
    }

    // ── Proposals ───────────────────────────────────────────────────────

    @Test
    void thereIsNoProposalUntilOneIsSaved() throws Exception {
        mockMvc.perform(get("/api/council/advisor/proposal"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.present", is(false)))
               .andExpect(jsonPath("$.location").exists());
    }

    @Test
    void savingWhatCannotBeSeatedIsRefusedWithSomethingToDo() throws Exception {
        mockMvc.perform(put("/api/council/advisor/proposal")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"requirement":{"privacy":"LOCAL_ONLY","cost":"FREE_ONLY"}}
                                         """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("no local models installed")))
               .andExpect(jsonPath("$.remediation", containsString("ollama pull")));
    }

    @Test
    void discardingNothingIsStillSuccess() throws Exception {
        // The caller wanted no proposal to exist. It does not.
        mockMvc.perform(delete("/api/council/advisor/proposal"))
               .andExpect(status().isNoContent());
    }

    @Test
    void aProposalRequestCannotSmuggleInADocument() throws Exception {
        // Intent goes in and configuration comes out. A body carrying a document
        // is rejected rather than partly honoured, which is what keeps a
        // hand-assembled configuration out of the proposal store.
        mockMvc.perform(put("/api/council/advisor/proposal")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                         {"requirement":{"privacy":"LOCAL_ONLY"},
                                          "document":{"version":1,"models":[]}}
                                         """))
               .andExpect(status().isBadRequest());
    }
}
