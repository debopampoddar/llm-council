package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.application.RunRegistry;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers {@code DELETE /api/council/sessions/{id}/run}.
 *
 * <p>Cancellation matters more than it looks: {@code max-concurrent-runs}
 * defaults to 1, so a single unwanted run blocks every other run until it
 * finishes.
 */
@SpringBootTest(properties = {
        "council.runtime.max-concurrent-runs=1",
        "council.persistence.artifact-base-path=/private/tmp/llm-council-cancel-test"
})
@AutoConfigureMockMvc
class CancellationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CouncilService councilService;

    @Autowired
    private RunRegistry runRegistry;

    @Autowired
    private EventPublisher events;

    @Test
    void cancellingAFinishedRunIsANoOpNotAnError() throws Exception {
        // The user cannot avoid racing their own click against the run
        // finishing, so this has to be a no-op reporting the current status.
        String sessionId = createSession();
        mockMvc.perform(post("/api/council/sessions/" + sessionId + "/run"))
               .andExpect(status().isOk());

        mockMvc.perform(delete("/api/council/sessions/" + sessionId + "/run"))
               .andExpect(status().isAccepted())
               .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void cancellingAnUnknownSessionIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/council/sessions/does-not-exist/run"))
               .andExpect(status().isNotFound());
    }

    @Test
    void aCancelledRunSkipsItsRemainingStages() {
        // Cancellation is honoured at stage boundaries only, so whichever stage
        // was already running finishes first. What must hold is that no stage
        // starts afterwards, and that the skips say why.
        CouncilContext context = runWithCancellationBeforeStart();
        String sessionId = context.session().id();

        assertTrue(context.isCancelled());
        assertFalse(context.isTerminal(), "cancelling is not a failure");

        List<CouncilEvent> stream = events.history(sessionId);

        long cancelledSkips = stream.stream()
                .filter(event -> "STAGE_SKIPPED".equals(event.type()))
                .filter(event -> "cancelled by user".equals(event.payload().get("reason")))
                .count();
        assertTrue(cancelledSkips > 0,
                "the rigorous protocol has 11 stages; cancelling must skip the rest");

        assertTrue(stream.stream().anyMatch(event -> "PROTOCOL_CANCELLED".equals(event.type())),
                "a cancelled run must not report itself as completed or failed");
        assertFalse(stream.stream().anyMatch(event -> "PROTOCOL_COMPLETED".equals(event.type())));

        // Nothing starts after the first cancellation-induced skip.
        int firstSkip = indexOfFirstCancelledSkip(stream);
        assertFalse(stream.subList(firstSkip, stream.size()).stream()
                          .anyMatch(event -> "STAGE_STARTED".equals(event.type())),
                "no stage may start once cancellation has been observed");
    }

    private int indexOfFirstCancelledSkip(List<CouncilEvent> stream) {
        for (int i = 0; i < stream.size(); i++) {
            CouncilEvent event = stream.get(i);
            if ("STAGE_SKIPPED".equals(event.type())
                    && "cancelled by user".equals(event.payload().get("reason"))) {
                return i;
            }
        }
        throw new AssertionError("no cancellation skip found");
    }

    @Test
    void aCancelledRunLandsInTheCancelledStatus() {
        CouncilContext context = runWithCancellationBeforeStart();
        CouncilSession session = councilService.getSession(context.session().id());

        // Not FAILED: the run did what it was told. Not COMPLETED: it did not
        // finish its protocol.
        assertTrue(session.status() == CouncilStatus.CANCELLED,
                "expected CANCELLED but was " + session.status());
        assertTrue(CouncilService.CANCELLED_BY_USER.equals(session.failureReason()),
                "the reason a run stopped is what the UI shows");
    }

    /**
     * Run a council whose context is cancelled before the first stage.
     *
     * <p>The registry publishes the context as soon as the protocol starts, so
     * cancelling from another thread the moment it appears reproduces a user
     * clicking cancel immediately after sending.
     */
    private CouncilContext runWithCancellationBeforeStart() {
        String sessionId = createSession();
        Thread canceller = Thread.startVirtualThread(() -> {
            while (runRegistry.find(sessionId).isEmpty()) {
                Thread.onSpinWait();
            }
            runRegistry.cancel(sessionId);
        });
        try {
            return councilService.runCouncil(sessionId);
        } finally {
            canceller.interrupt();
        }
    }

    private String createSession() {
        String sessionId = UUID.randomUUID().toString();
        councilService.createSession(CouncilSession.create(
                sessionId, "Can this run be stopped?", null, DepthMode.RIGOROUS, "mock"));
        return sessionId;
    }
}
