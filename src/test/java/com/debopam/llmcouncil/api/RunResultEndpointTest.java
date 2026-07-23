package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.chat.ChatCouncilService;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code GET /api/council/sessions/{id}/result}.
 *
 * <p>The point of this endpoint is that a chat client can calibrate an answer
 * without reassembling the evidence itself, so these tests assert on the trust
 * signals specifically — not merely that some JSON came back.
 */
@SpringBootTest(properties = {
        "council.runtime.max-concurrent-runs=1",
        "council.persistence.artifact-base-path=/private/tmp/llm-council-result-test"
})
@AutoConfigureMockMvc
class RunResultEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatCouncilService chatService;

    @Test
    void synchronousRunIsReadableAfterwards() throws Exception {
        String sessionId = createSession("Should we adopt a council?", DepthMode.RIGOROUS);

        mockMvc.perform(post("/api/council/sessions/" + sessionId + "/run"))
               .andExpect(status().isOk());

        mockMvc.perform(get("/api/council/sessions/" + sessionId + "/result"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.sessionId").value(sessionId))
               .andExpect(jsonPath("$.status").value("COMPLETED"))
               .andExpect(jsonPath("$.protocolId").value("rigorous"));
    }

    @Test
    void chatRunExposesTheTrustSignals() throws Exception {
        // The chat path returns before the council finishes, so without this
        // endpoint every signal below would have to be rebuilt client-side from
        // the event stream, the catalog and the artifact files.
        ChatSession chat = chatService.createChat("mock", DepthMode.RIGOROUS, null);
        chatService.ask(chat.id(), "Should we migrate our monolith to microservices?");
        String sessionId = waitForCouncilSession(chat.id(), Duration.ofSeconds(10));

        mockMvc.perform(get("/api/council/sessions/" + sessionId + "/result"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("COMPLETED"))
               .andExpect(jsonPath("$.validationIndependence").value("SELF_VALIDATION"))
               .andExpect(jsonPath("$.sycophancyWarnings").isArray())
               .andExpect(jsonPath("$.excludedModels").isArray())
               .andExpect(jsonPath("$.warnings").isArray())
               .andExpect(jsonPath("$.modelFailures").isArray())
               .andExpect(jsonPath("$.participatingModels").isNotEmpty())
               .andExpect(jsonPath("$.validation.confidence").isNumber())
               .andExpect(jsonPath("$.scoreSummary.winningDraftId").isNotEmpty());
    }

    @Test
    void independenceTierTravelsWithTheConfidenceFigure() throws Exception {
        // A confidence number means something different when the chair graded
        // itself, so the two must never arrive separately.
        String sessionId = createSession("Is self-validation independent?", DepthMode.BALANCED);
        mockMvc.perform(post("/api/council/sessions/" + sessionId + "/run"))
               .andExpect(status().isOk());

        mockMvc.perform(get("/api/council/sessions/" + sessionId + "/result"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.validation.confidence").isNumber())
               .andExpect(jsonPath("$.validationIndependence").value("SELF_VALIDATION"));
    }

    @Test
    void resultIsAbsentUntilTheRunFinishes() throws Exception {
        String sessionId = createSession("Never run.", DepthMode.QUICK);

        mockMvc.perform(get("/api/council/sessions/" + sessionId + "/result"))
               .andExpect(status().isNotFound());
    }

    @Test
    void unknownSessionIsNotFound() throws Exception {
        mockMvc.perform(get("/api/council/sessions/does-not-exist/result"))
               .andExpect(status().isNotFound());
    }

    private String createSession(String question, DepthMode depth) throws Exception {
        String body = mockMvc.perform(post("/api/council/sessions")
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content("""
                                                       {"question":"%s","depthMode":"%s","profileId":"mock"}
                                                       """.formatted(question, depth.name())))
                             .andExpect(status().isCreated())
                             .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.sessionId");
    }

    private String waitForCouncilSession(String chatId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ChatSession chat = chatService.getChat(chatId);
            if (!chat.hasRunningTurn() && !chat.turns().isEmpty()) {
                return chat.turns().getFirst().councilSessionId();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Council run did not finish within " + timeout);
    }
}
