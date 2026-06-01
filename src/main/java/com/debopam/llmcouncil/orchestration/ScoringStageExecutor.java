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

    public ScoringStageExecutor(ScoringService scoringService,
                                ArtifactStore artifactStore,
                                EventPublisher events) {
        this.scoringService = scoringService;
        this.artifactStore = artifactStore;
        this.events = events;
    }

    @Override
    public String stage() {
        return "SCORE";
    }

    @Override
    public CouncilContext execute(CouncilContext context) {
        ScoreSummary summary = scoringService.score(context.reviews());
        context.setScoreSummary(summary);
        artifactStore.writeJson(context.session().id(), "normalized/scores.json", summary);
        events.publish(context.session().id(), stage(), "SCORE_COMPUTED", null, Map.of("winningDraftId", summary.winningDraftId()));
        return context;
    }
}
