package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.application.CouncilRunExecutor;
import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code council.runtime.max-concurrent-runs} on the <b>synchronous</b>
 * run endpoint.
 *
 * <p>The limit used to live only in {@link CouncilRunExecutor#submit}, which only
 * the asynchronous chat path calls. {@code POST /sessions/{id}/run} executes the
 * protocol inline on the request thread, so it consumed provider quota and tokens
 * without ever taking a permit: a caller looping over that endpoint could start
 * unbounded concurrent councils while the setting read 1.
 *
 * <p>{@code activeSessionIds} in {@code CouncilService} did not close this — it
 * rejects the <i>same</i> session running twice, which is a different question
 * from how many <i>different</i> sessions may run at once. The last test here is
 * the control that keeps those two mechanisms distinguishable.
 */
@SpringBootTest(properties = {
        "council.runtime.max-concurrent-runs=1"
})
@AutoConfigureMockMvc
class RunConcurrencyLimitTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void artifactPath(DynamicPropertyRegistry registry) {
        registry.add("council.persistence.artifact-base-path",
                () -> tempDir.resolve("artifacts").toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CouncilService councilService;

    @Autowired
    private CouncilRunExecutor runExecutor;

    @Test
    @DisplayName("the synchronous endpoint refuses a run once the limit is reached")
    void synchronousRunIsRefusedWhenNoPermitIsAvailable() throws Exception {
        String sessionId = createSession();

        // Stand in for another run holding the single configured permit.
        assertTrue(runExecutor.tryAcquireRunPermit(), "the only permit must be free to start");
        try {
            mockMvc.perform(post("/api/council/sessions/" + sessionId + "/run"))
                   .andExpect(status().isTooManyRequests());
        } finally {
            runExecutor.releaseRunPermit();
        }

        // The refusal must not have consumed the session: it never ran.
        assertEquals(CouncilStatus.CREATED, councilService.getSession(sessionId).status(),
                "a rejected run must leave the session runnable");
    }

    @Test
    @DisplayName("positive control: the same request succeeds when a permit is free")
    void synchronousRunSucceedsWhenAPermitIsAvailable() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/api/council/sessions/" + sessionId + "/run"))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the permit is returned after the run, so the next caller is not refused")
    void permitIsReleasedAfterEachRun() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/council/sessions/" + createSession() + "/run"))
                   .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("re-running one session is a conflict, which is a different rule")
    void rerunningTheSameSessionStillConflicts() throws Exception {
        String sessionId = createSession();
        mockMvc.perform(post("/api/council/sessions/" + sessionId + "/run"))
               .andExpect(status().isOk());

        // 409, not 429: the permit was released, so this is the single-use
        // session rule rather than the concurrency limit.
        mockMvc.perform(post("/api/council/sessions/" + sessionId + "/run"))
               .andExpect(status().isConflict());
    }

    private String createSession() {
        String sessionId = UUID.randomUUID().toString();
        councilService.createSession(CouncilSession.create(
                sessionId, "Does the concurrency limit apply here?", null,
                DepthMode.QUICK, "mock"));
        return sessionId;
    }
}
