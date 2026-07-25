package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.api.dto.CouncilRunResponse;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.user.IntegrityAssessment;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run says how hard it looked, not only what it found.
 *
 * <p>Every other trust signal on a result reports a finding. None of them can
 * distinguish "the council was checked and came back clean" from "the checks
 * were configured so loosely they could not fire" — and the second reads as the
 * first on every field. That is the gap this reports.
 *
 * <p>The assertions are paired throughout. An integrity flag that never fires
 * would satisfy every "not reduced" expectation here, so each one is matched by
 * a case that must set it.
 */
@SpringBootTest
class RunIntegrityReportingTest {

    @Autowired
    private CouncilCatalogHolder catalogHolder;

    // ── Shipped configuration ───────────────────────────────────────────

    @Test
    void noShippedProtocolGivesAnythingUp() {
        // A guard on application.yml as much as on this code: raising the
        // shipped sycophancy threshold, or turning off dissent preservation,
        // should be a deliberate act that fails a test rather than a quiet edit.
        catalogHolder.builtIn().protocols().forEach((id, protocol) -> {
            IntegrityAssessment assessment = IntegrityAssessment.of(protocol);
            assertFalse(assessment.reduced(),
                        "shipped protocol '" + id + "' weakens a guarantee: " + assessment.notes());
        });
    }

    @Test
    void theShippedRigorousProtocolReportsItsEffectiveValues() {
        IntegrityAssessment assessment =
                IntegrityAssessment.of(catalogHolder.builtIn().protocols().get("rigorous"));

        assertEquals(Boolean.TRUE, assessment.preserveDissent());
        assertEquals(0.70, assessment.sycophancyThreshold(), 0.0001);
    }

    @Test
    void aProtocolWithoutDebateReportsNoThresholdRatherThanTheDefault() {
        // quick has no DEBATE stage, so it never measures sycophancy. Reporting
        // 0.70 for it would imply a measurement that does not happen.
        IntegrityAssessment assessment =
                IntegrityAssessment.of(catalogHolder.builtIn().protocols().get("quick"));

        assertNull(assessment.sycophancyThreshold(),
                   "a protocol that never debates has no detection threshold to report");
        assertEquals(Boolean.TRUE, assessment.preserveDissent(),
                     "it does synthesise, so dissent preservation is a real, reportable setting");
    }

    // ── Weakened configuration ──────────────────────────────────────────

    @Test
    void switchingOffDissentPreservationIsReported() {
        IntegrityAssessment assessment = IntegrityAssessment.of(
                tuned(StageType.SYNTHESIZE, "preserve-dissent", false));

        assertTrue(assessment.reduced());
        assertEquals(Boolean.FALSE, assessment.preserveDissent());
        assertTrue(assessment.notes().stream().anyMatch(note -> note.contains("Dissent preservation")),
                   "the note must say what was given up, not merely that something was: " + assessment.notes());
    }

    @Test
    void aSuppressingSycophancyThresholdIsReported() {
        IntegrityAssessment assessment = IntegrityAssessment.of(
                tuned(StageType.DEBATE, "sycophancy-threshold", 0.92));

        assertTrue(assessment.reduced());
        assertEquals(0.92, assessment.sycophancyThreshold(), 0.0001);
    }

    @Test
    void aThresholdInsideTheUsefulRangeIsNotReportedAsWeakened() {
        // The distinction the flag exists for: the option can weaken the
        // guarantee, this value does not. Without this, "integrityReduced"
        // would fire on every protocol that mentions the option at all.
        IntegrityAssessment assessment = IntegrityAssessment.of(
                tuned(StageType.DEBATE, "sycophancy-threshold", 0.75));

        assertFalse(assessment.reduced());
        assertEquals(0.75, assessment.sycophancyThreshold(), 0.0001);
    }

    // ── On the run result ───────────────────────────────────────────────

    @Test
    void theResultCarriesTheProtocolTheRunActuallyExecuted() {
        CouncilRunResponse weakened = response(tuned(StageType.SYNTHESIZE, "preserve-dissent", false));
        CouncilRunResponse intact = response(catalogHolder.builtIn().protocols().get("rigorous"));

        assertTrue(weakened.integrity().reduced());
        assertEquals(Boolean.FALSE, weakened.integrity().preserveDissent());

        assertFalse(intact.integrity().reduced());
        assertEquals(Boolean.TRUE, intact.integrity().preserveDissent());
    }

    @Test
    void aRunThatDiedBeforeStartingClaimsNothingEitherWay() {
        // Not "nothing was weakened" — that is a claim this result cannot
        // support, because no protocol ever ran.
        CouncilSession session = CouncilSession.create("dead", "q", null, DepthMode.QUICK, "mock");

        assertNull(CouncilRunResponse.failed(session, "died").integrity());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** The shipped rigorous protocol with one option changed. */
    private ProtocolDefinition tuned(StageType stage, String key, Object value) {
        ProtocolDefinition base = catalogHolder.builtIn().protocols().get("rigorous");
        Map<StageType, ProtocolStageOptions> options = new LinkedHashMap<>();
        base.stageOptions().forEach((s, o) ->
                options.put(s, new ProtocolStageOptions(new LinkedHashMap<>(o.values()))));

        Map<String, Object> merged = new LinkedHashMap<>(
                options.containsKey(stage) ? options.get(stage).values() : Map.of());
        merged.put(key, value);
        options.put(stage, new ProtocolStageOptions(merged));

        return new ProtocolDefinition("tuned", "Tuned copy", base.orderedStages(), options);
    }

    private CouncilRunResponse response(ProtocolDefinition protocol) {
        CouncilSession session = CouncilSession.create("s", "q", null, DepthMode.RIGOROUS, "mock");
        CouncilProfile profile = new CouncilProfile("mock", "Mock", true, DepthMode.RIGOROUS,
                                                    Map.of(DepthMode.RIGOROUS, "mock-rigorous"));
        CouncilPolicy policy = new CouncilPolicy("mock-rigorous", protocol.id(),
                                                 List.of("mock-member"), "mock-chair", null,
                                                 1, 0, false, true);
        CouncilContext ctx = new CouncilContext(session, profile, policy, protocol);
        return CouncilRunResponse.from("s", ctx);
    }
}
