package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.CouncilService;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rigorous protocol must only run a second evidence pass when a debate
 * actually produced new evidence.
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
@SpringBootTest
class ScorePassLabellingTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void artifactPath(DynamicPropertyRegistry registry) {
        registry.add("council.persistence.artifact-base-path",
                () -> tempDir.resolve("artifacts").toString());
    }

    @Autowired
    private CouncilService councilService;

    @Autowired
    private EventPublisher events;

    @Autowired
    private ArtifactStore artifacts;

    @Test
    void skippedDebateDoesNotCreateAFalsePostDebateScoreArtifact() {
        String sessionId = runRigorous();

        List<String> written = artifacts.listArtifacts(sessionId).stream()
                                        .filter(path -> path.startsWith("normalized/scores-"))
                                        .toList();

        assertEquals(1, written.size(), written.toString());
        assertTrue(written.contains("normalized/scores-initial.json"), written.toString());
        assertFalse(written.contains("normalized/scores-post-debate.json"), written.toString());
    }

    @Test
    void skippedSecondPassDoesNotPretendToHaveAComparisonLabel() {
        String sessionId = runRigorous();

        List<String> labels = events.history(sessionId).stream()
                                    .filter(event -> "SCORE_COMPLETED".equals(event.type()))
                                    .map(event -> String.valueOf(event.payload().get("label")))
                                    .toList();

        assertEquals(List.of("initial"), labels);
    }

    @Test
    void reviewsAndScoresAgreeThatOnlyTheInitialPassRan() {
        String sessionId = runRigorous();
        List<String> written = artifacts.listArtifacts(sessionId);

        assertTrue(written.contains("normalized/reviews.json"), written.toString());
        assertFalse(written.contains("normalized/reviews-post-debate.json"), written.toString());
        assertTrue(written.contains("normalized/scores-initial.json"), written.toString());
        assertFalse(written.contains("normalized/scores-post-debate.json"), written.toString());
    }

    @Test
    void skippedSecondPassPublishesAnExplicitReason() {
        String sessionId = runRigorous();

        List<CouncilEvent> skipped = events.history(sessionId).stream()
                .filter(event -> "SCORE_SKIPPED".equals(event.type()))
                .filter(event -> event.payload().containsKey("reason"))
                .toList();

        assertEquals(1, skipped.size());
        assertTrue(String.valueOf(skipped.getFirst().payload().get("reason"))
                .contains("No debate"));
    }

    private String runRigorous() {
        String sessionId = UUID.randomUUID().toString();
        councilService.createSession(CouncilSession.create(
                sessionId, "Do both scoring passes survive?", null, DepthMode.RIGOROUS, "mock"));
        councilService.runCouncil(sessionId);
        return sessionId;
    }
}
