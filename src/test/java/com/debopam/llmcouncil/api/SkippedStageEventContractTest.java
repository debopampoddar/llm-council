package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.application.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the event ordering the stage timeline is built on.
 *
 * <p>A stage that does nothing still reports {@code STAGE_COMPLETED}; the skip
 * is a separate domain event published inside the stage. A timeline driven off
 * {@code STAGE_SKIPPED} alone therefore paints DEBATE green on a run where no
 * debate happened, which makes a council that never argued look exactly like
 * one that argued and agreed.
 *
 * <p>These assertions exist so that contract is explicit. If the orchestrator
 * ever starts emitting {@code STAGE_SKIPPED} for a stage that ran but did
 * nothing, this test fails and {@code timeline.js} needs updating with it.
 */
@SpringBootTest(properties = "council.persistence.artifact-base-path=/private/tmp/llm-council-skip-test")
class SkippedStageEventContractTest {

    @Autowired
    private CouncilService councilService;

    @Autowired
    private EventPublisher events;

    @Test
    void aStageThatDoesNothingStillReportsCompleted() {
        String sessionId = runRigorousMockCouncil();
        List<CouncilEvent> stream = events.history(sessionId);

        List<String> debate = typesForStage(stream, "DEBATE");
        assertEquals(List.of("STAGE_STARTED", "DEBATE_SKIPPED", "STAGE_COMPLETED"), debate,
                "DEBATE reports completion after skipping; the timeline must not read that as work done");

        List<String> revise = typesForStage(stream, "REVISE");
        assertEquals(List.of("STAGE_STARTED", "REVISION_SKIPPED", "STAGE_COMPLETED"), revise,
                "REVISE reports completion after skipping");
    }

    @Test
    void theSkipCarriesAReasonTheUiCanShowVerbatim() {
        String sessionId = runRigorousMockCouncil();

        CouncilEvent skip = events.history(sessionId).stream()
                .filter(event -> "DEBATE_SKIPPED".equals(event.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no DEBATE_SKIPPED event"));

        assertTrue(skip.payload().containsKey("reason"), "the UI renders this reason verbatim");
        assertTrue(skip.payload().containsKey("variance"));
        assertTrue(skip.payload().containsKey("threshold"));
    }

    @Test
    void noOpEventsAllShareTheSkippedSuffix() {
        // timeline.js matches on the _SKIPPED suffix rather than an enumerated
        // list, so a newly added no-op event is handled without a UI change.
        // That only holds while the naming convention does.
        String sessionId = runRigorousMockCouncil();

        List<String> noOps = events.history(sessionId).stream()
                .map(CouncilEvent::type)
                .filter(type -> type.contains("SKIP"))
                .distinct()
                .toList();

        assertFalse(noOps.isEmpty(), "the mock rigorous run skips debate and revision");
        noOps.forEach(type -> assertTrue(type.endsWith("_SKIPPED"),
                type + " does not end in _SKIPPED, so the timeline will render it as work done"));
    }

    private List<String> typesForStage(List<CouncilEvent> stream, String stage) {
        return stream.stream()
                     .filter(event -> stage.equals(event.stage()))
                     .map(CouncilEvent::type)
                     .toList();
    }

    private String runRigorousMockCouncil() {
        String sessionId = UUID.randomUUID().toString();
        councilService.createSession(CouncilSession.create(
                sessionId, "Should the council debate?", null, DepthMode.RIGOROUS, "mock"));
        councilService.runCouncil(sessionId);
        return sessionId;
    }
}
