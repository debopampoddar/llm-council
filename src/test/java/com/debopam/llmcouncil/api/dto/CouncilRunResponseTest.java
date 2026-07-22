package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.orchestration.ProtocolDefinition;
import com.debopam.llmcouncil.orchestration.StageType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the quality signals a caller needs in order to judge a run.
 *
 * <p>Sycophancy detection is the council's central defence: a member that
 * simply agrees with the previous speaker adds the appearance of consensus
 * without the substance. Detecting that and then not reporting it would leave a
 * rubber-stamped run indistinguishable from a genuinely contested one.
 */
class CouncilRunResponseTest {

    @Test
    void reportsSycophancyWarningsDetectedDuringDebate() {
        CouncilContext ctx = context();
        ctx.setSynthesisResult("the answer");
        ctx.addSycophancyWarning("Sycophancy detected for model member-b: index=0.910");

        CouncilRunResponse response = CouncilRunResponse.from("session-1", ctx);

        assertEquals(1, response.sycophancyWarnings().size(),
                     "a detected warning must reach the caller, not only the event stream");
        assertTrue(response.sycophancyWarnings().getFirst().contains("member-b"));
    }

    @Test
    void aCleanRunReportsAnEmptyListRatherThanNull() {
        CouncilContext ctx = context();
        ctx.setSynthesisResult("the answer");

        CouncilRunResponse response = CouncilRunResponse.from("session-1", ctx);

        assertTrue(response.sycophancyWarnings().isEmpty());
    }

    @Test
    void sycophancyWarningsAreSeparateFromGeneralWarnings() {
        // They answer different questions: a general warning says the run was
        // degraded, a sycophancy warning says the agreement may be hollow.
        CouncilContext ctx = context();
        ctx.setSynthesisResult("the answer");
        ctx.addWarning("Prompt for model chair exceeded its context budget");
        ctx.addSycophancyWarning("Sycophancy detected for model member-b: index=0.910");

        CouncilRunResponse response = CouncilRunResponse.from("session-1", ctx);

        assertEquals(1, response.warnings().size());
        assertEquals(1, response.sycophancyWarnings().size());
    }

    private CouncilContext context() {
        CouncilSession session = CouncilSession.create("session-1", "q", null,
                                                       DepthMode.RIGOROUS, "local");
        CouncilProfile profile = new CouncilProfile("local", "Local", false, DepthMode.RIGOROUS,
                                                    Map.of(DepthMode.RIGOROUS, "local-rigorous"));
        CouncilPolicy policy = new CouncilPolicy("local-rigorous", "rigorous",
                                                 List.of("member-a", "member-b"), "chair", null,
                                                 1, 0, false, true);
        ProtocolDefinition protocol = new ProtocolDefinition("rigorous", "Rigorous",
                List.of(StageType.GENERATE, StageType.DEBATE, StageType.SYNTHESIZE), Map.of());
        return new CouncilContext(session, profile, policy, protocol);
    }
}
