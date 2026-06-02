package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ScoringStageExecutor implements StageExecutor {
    private final ScoringService scoringService;
    private final ArtifactStore artifactStore;
    private final EventPublisher events;

    public ScoringStageExecutor(ScoringService scoringService, ArtifactStore artifactStore, EventPublisher events) {
        this.scoringService = scoringService;
        this.artifactStore = artifactStore;
        this.events = events;
    }

    @Override
    public StageType stage() {
        return StageType.SCORE;
    }

    @Override
    public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
        String label = nextScoreLabel(context, options);
        ScoreSummary summary = scoringService.score(context.reviews(), context.debateSummary());
        context.setScoreSummary(label, summary);
        artifactStore.writeJson(context.session().id(), "normalized/scores-" + label + ".json", summary);
        artifactStore.writeJson(context.session().id(), "normalized/score-snapshots.json", context.scoreSnapshots());
        events.publish(context.session().id(), stage().name(), "SCORE_COMPUTED", null, Map.of(
                "label", label,
                "winningDraftId", summary.winningDraftId()
        ));
        return context;
    }

    private String nextScoreLabel(CouncilContext context, ProtocolStageOptions options) {
        if (context.scoreSnapshots().isEmpty()) {
            return options.artifactLabelOrDefault("initial");
        }
        return "final";
    }
}
