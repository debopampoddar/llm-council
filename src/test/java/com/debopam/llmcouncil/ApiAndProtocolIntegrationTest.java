package com.debopam.llmcouncil;

import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "council.persistence.artifact-base-path=/tmp/llm-council-regenerated-tests",
        "logging.level.com.debopam.llmcouncil.application.DefaultEventPublisher=WARN"
})
@AutoConfigureMockMvc
class ApiAndProtocolIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CouncilService councils;
    @Autowired EventPublisher events;
    @Autowired ArtifactStore artifacts;

    @Test
    void applicationAndStaticInterfacesBoot() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LLM Council")));
        mvc.perform(get("/setup.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LLM Council")));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void catalogIsSectionedAndHidesTestProfilesByDefault() throws Exception {
        mvc.perform(get("/api/council/catalog").param("include", "profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[?(@.id == 'mock')]").isEmpty())
                .andExpect(jsonPath("$.models").doesNotExist());
        mvc.perform(get("/api/council/catalog")
                        .param("include", "profiles")
                        .param("includeTestOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles[?(@.id == 'mock')]").isNotEmpty());
    }

    @Test
    void requestValidationRejectsBlankAndOversizedQuestions() throws Exception {
        mvc.perform(post("/api/council/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest());
        String body = json.createObjectNode().put("question", "x".repeat(5001)).toString();
        mvc.perform(post("/api/council/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quickMockRunCompletesAndIsSingleUse() throws Exception {
        String sessionId = createApiSession("QUICK");
        mvc.perform(post("/api/council/sessions/{id}/run", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.answer").isNotEmpty())
                .andExpect(jsonPath("$.draftCount").value(1));
        assertTrue(artifacts.listArtifacts(sessionId).contains("final/result.json"));

        mvc.perform(post("/api/council/sessions/{id}/run", sessionId))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownProfileFailsTheSessionInsteadOfLeavingItCreated() throws Exception {
        String response = mvc.perform(post("/api/council/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"test\",\"profileId\":\"missing\",\"depthMode\":\"QUICK\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("sessionId").asText();

        mvc.perform(post("/api/council/sessions/{id}/run", id)).andExpect(status().isNotFound());
        assertEquals(CouncilStatus.FAILED, councils.getSession(id).status());
        assertTrue(councils.getSession(id).failureReason().contains("Profile not found"));
    }

    @Test
    void balancedMockRunProducesReviewsScoresValidationAndUsage() {
        CouncilContext ctx = run(DepthMode.BALANCED);
        CouncilSession session = councils.getSession(ctx.session().id());

        assertEquals(CouncilStatus.COMPLETED, session.status());
        assertFalse(ctx.reviews().isEmpty());
        assertTrue(ctx.scoreSummary().isPresent());
        assertTrue(ctx.validation().isPresent());
        assertFalse(ctx.usage().isEmpty());
    }

    @Test
    void rigorousMockRunDoesNotPayForASecondEvidencePassWhenDebateWasSkipped() {
        CouncilContext ctx = run(DepthMode.RIGOROUS);
        List<String> paths = artifacts.listArtifacts(ctx.session().id());

        assertEquals(1, ctx.scores().stream().map(score -> score.label()).distinct().count());
        assertTrue(paths.contains("normalized/scores-initial.json"));
        assertFalse(paths.contains("normalized/scores-post-debate.json"));
        assertTrue(paths.contains("normalized/reviews.json"));
        assertFalse(paths.contains("normalized/reviews-post-debate.json"));
        assertTrue(paths.contains("final/answer.md"));
        assertTrue(paths.contains("exports/manifest.json"));
    }

    @Test
    void eventSequenceAndProtocolLifecycleAreObservable() {
        CouncilContext ctx = run(DepthMode.BALANCED);
        var history = events.history(ctx.session().id());

        assertEquals("PROTOCOL_STARTED", history.getFirst().type());
        assertEquals("PROTOCOL_COMPLETED", history.getLast().type());
        assertTrue(history.stream().allMatch(event -> event.seq() > 0));
        for (int i = 1; i < history.size(); i++) {
            assertTrue(history.get(i).seq() > history.get(i - 1).seq());
        }
    }

    @Test
    void chatApiDefaultsAndInputValidationAreUseful() throws Exception {
        String raw = mvc.perform(post("/api/council/chats")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profileId").value("default"))
                .andExpect(jsonPath("$.depthMode").value("BALANCED"))
                .andReturn().getResponse().getContentAsString();
        String chatId = json.readTree(raw).path("chatId").asText();
        mvc.perform(post("/api/council/chats/{id}/messages", chatId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/council/chats/{id}", chatId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.turns").isArray());
    }

    @Test
    void artifactEndpointRejectsTraversal() throws Exception {
        String id = run(DepthMode.QUICK).session().id();
        mvc.perform(get("/api/council/sessions/{id}/artifacts/%2e%2e/%2e%2e/etc/passwd", id))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void deletingAChatDeletesItsCouncilSessionsAndArtifacts() throws Exception {
        String created = mvc.perform(post("/api/council/chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\":\"mock\",\"depthMode\":\"QUICK\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String chatId = json.readTree(created).path("chatId").asText();
        String asked = mvc.perform(post("/api/council/chats/{id}/messages", chatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"delete me\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionId = json.readTree(asked).path("turns").get(0)
                .path("councilSessionId").asText();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while ((councils.getSession(sessionId).status() == CouncilStatus.CREATED
                || councils.getSession(sessionId).status() == CouncilStatus.RUNNING)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(List.of(CouncilStatus.CREATED, CouncilStatus.RUNNING)
                .contains(councils.getSession(sessionId).status()));
        assertFalse(artifacts.listArtifacts(sessionId).isEmpty());

        mvc.perform(delete("/api/council/chats/{id}", chatId))
                .andExpect(status().isNoContent());

        assertThrows(java.util.NoSuchElementException.class,
                () -> councils.getSession(sessionId));
        assertTrue(artifacts.listArtifacts(sessionId).isEmpty());
    }

    private String createApiSession(String depth) throws Exception {
        String body = json.createObjectNode()
                .put("question", "What does this council verify?")
                .put("profileId", "mock").put("depthMode", depth).toString();
        String raw = mvc.perform(post("/api/council/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(raw).path("sessionId").asText();
    }

    private CouncilContext run(DepthMode depth) {
        String id = UUID.randomUUID().toString();
        councils.createSession(CouncilSession.create(id, "Evaluate this implementation", null, depth, "mock"));
        return councils.runCouncil(id);
    }
}
