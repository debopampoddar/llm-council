package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rigorous protocol runs SCORE twice, and the two passes must stay apart.
 *
 * <p>Both passes previously read {@code artifact-label: initial} from
 * configuration, because stage options are keyed by {@link StageType} and SCORE
 * appears twice in {@code orderedStages}. Two consequences followed:
 *
 * <ul>
 *   <li>the second pass overwrote {@code normalized/scores-initial.json}, losing
 *       the before/after comparison that is the most direct evidence debate
 *       changed anyone's mind;</li>
 *   <li>more seriously, {@code isPostDebate} was derived from that same label,
 *       so it was permanently false and <b>disagreement escalation could never
 *       fire</b> — an anti-sycophancy guarantee that was unreachable in both
 *       shipped protocols.</li>
 * </ul>
 */
@SpringBootTest(properties = "council.persistence.artifact-base-path=/private/tmp/llm-council-score-label-test")
class ScorePassLabellingTest {

    @Autowired
    private CouncilService councilService;

    @Autowired
    private EventPublisher events;

    @Autowired
    private ArtifactStore artifacts;

    @Test
    void bothScoringPassesKeepTheirOwnArtifact() {
        String sessionId = runRigorous();

        List<String> written = artifacts.listArtifacts(sessionId).stream()
                                        .filter(path -> path.startsWith("normalized/scores-"))
                                        .toList();

        assertEquals(2, written.size(),
                "each SCORE pass needs its own artifact: " + written);
        assertTrue(written.contains("normalized/scores-initial.json"), written.toString());
        assertTrue(written.contains("normalized/scores-post-debate.json"), written.toString());
    }

    @Test
    void thePassesReportDistinctLabels() {
        String sessionId = runRigorous();

        List<String> labels = events.history(sessionId).stream()
                                    .filter(event -> "SCORE_COMPLETED".equals(event.type()))
                                    .map(event -> String.valueOf(event.payload().get("label")))
                                    .toList();

        assertEquals(List.of("initial", "post-debate"), labels,
                "the UI keys the before/after comparison on these labels");
    }

    @Test
    void reviewsAndScoresAgreeOnHowManyPassesRan() {
        // Reviews were already kept either side of debate; scores now match, so
        // the two halves of the evidence line up.
        String sessionId = runRigorous();
        List<String> written = artifacts.listArtifacts(sessionId);

        assertTrue(written.contains("normalized/reviews.json"), written.toString());
        assertTrue(written.contains("normalized/reviews-post-debate.json"), written.toString());
        assertTrue(written.contains("normalized/scores-initial.json"), written.toString());
        assertTrue(written.contains("normalized/scores-post-debate.json"), written.toString());
    }

    @Test
    void onlyTheLaterPassIsTreatedAsPostDebate() {
        // Escalation fires only on the post-debate pass. That decision now
        // follows the protocol rather than a cosmetic artifact label.
        String sessionId = runRigorous();

        List<CouncilEvent> scoring = events.history(sessionId).stream()
                                           .filter(event -> "SCORE_COMPLETED".equals(event.type()))
                                           .toList();

        assertEquals(2, scoring.size());
        assertEquals("initial", scoring.getFirst().payload().get("label"));
        assertEquals("post-debate", scoring.getLast().payload().get("label"));
    }

    private String runRigorous() {
        String sessionId = UUID.randomUUID().toString();
        councilService.createSession(CouncilSession.create(
                sessionId, "Do both scoring passes survive?", null, DepthMode.RIGOROUS, "mock"));
        councilService.runCouncil(sessionId);
        return sessionId;
    }
}
