package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the DEBATE stage decides, and what it admits it could not decide.
 *
 * <p>Debate used to be gated on the variance of weighted totals <em>across
 * drafts</em>. That quantity is largest when the reviewers agree which draft is
 * best — 90 against 40 is a decisive ranking — and smallest when the drafts are
 * indistinguishable. The trigger therefore fired on consensus and stood down on
 * conflict, and because LLM judges compress scores into a narrow band it stood
 * down almost always: two drafts at 82 and 78 give a variance of 4 against a
 * threshold of 120.
 *
 * <p>The signal that means "the council disagrees" is inter-rater variance about
 * the <em>same</em> draft. It needs two reviewers on one draft, which a
 * two-member council never has once self-review is excluded — so a council that
 * cannot measure disagreement must say so rather than report the same quiet
 * "below threshold" as a council that measured and found none.
 */
@SpringBootTest(properties =
        "council.persistence.artifact-base-path=/private/tmp/llm-council-debate-trigger-test")
class DebateTriggerTest {

    @Autowired
    private CouncilService councilService;

    @Autowired
    private EventPublisher events;

    @Test
    @DisplayName("a two-member council reports that disagreement was never measurable")
    void twoMemberCouncilCannotMeasureDisagreement() {
        // mock-rigorous seats two members. Self-review is excluded, so each draft
        // collects exactly one review and inter-rater variance does not exist.
        CouncilEvent skip = debateSkip(runRigorous());

        assertEquals("reviewer disagreement not measurable", skip.payload().get("reason"));
        assertEquals(false, skip.payload().get("measurable"),
                     "the run must not claim it looked for disagreement");
    }

    @Test
    @DisplayName("the unmeasurable case reaches the reader as a run warning")
    void unmeasurableDisagreementIsWarnedAbout() {
        CouncilContext ctx = runRigorousContext();

        assertTrue(ctx.warnings().stream()
                      .anyMatch(w -> w.contains("without measuring disagreement")),
                   "a skipped measurement is a fact about the answer, not an internal detail: "
                   + ctx.warnings());
    }

    @Test
    @DisplayName("the skip payload still carries the draft-separation variance")
    void skipPayloadStillCarriesVariance() {
        // Positive control on the payload shape: the UI and
        // SkippedStageEventContractTest both read this key, and it keeps its
        // original meaning — how far apart the drafts scored.
        CouncilEvent skip = debateSkip(runRigorous());

        assertTrue(skip.payload().containsKey("variance"), skip.payload().toString());
        assertTrue(skip.payload().containsKey("threshold"), skip.payload().toString());
    }

    @Test
    @DisplayName("a skipped debate still completes its stage")
    void skippedDebateStillCompletesTheStage() {
        // The convention the event stream depends on: a stage that did nothing
        // still emits STAGE_COMPLETED, with the *_SKIPPED event inside it
        // overriding the completion. Guarding it here because the trigger
        // rewrite added a second early return.
        List<String> debateEvents = events.history(runRigorous()).stream()
                .filter(e -> "DEBATE".equals(e.stage()))
                .map(CouncilEvent::type)
                .toList();

        assertEquals(List.of("STAGE_STARTED", "DEBATE_SKIPPED", "STAGE_COMPLETED"), debateEvents,
                     "both early returns must leave the stage looking completed, not skipped");
    }

    private CouncilEvent debateSkip(String sessionId) {
        return events.history(sessionId).stream()
                     .filter(e -> "DEBATE_SKIPPED".equals(e.type()))
                     .findFirst()
                     .orElseThrow(() -> new AssertionError(
                             "no DEBATE_SKIPPED event in " + events.history(sessionId)));
    }

    private String runRigorous() {
        return runRigorousContext().session().id();
    }

    private CouncilContext runRigorousContext() {
        String sessionId = UUID.randomUUID().toString();
        councilService.createSession(CouncilSession.create(
                sessionId, "Does the debate trigger measure what it claims?", null,
                DepthMode.RIGOROUS, "mock"));
        return councilService.runCouncil(sessionId);
    }
}
