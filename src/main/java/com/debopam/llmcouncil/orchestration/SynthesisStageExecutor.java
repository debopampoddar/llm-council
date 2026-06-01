package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class SynthesisStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final ArtifactStore artifactStore;
    private final EventPublisher events;

    public SynthesisStageExecutor(ModelRegistry registry,
                                  ArtifactStore artifactStore,
                                  EventPublisher events) {
        this.registry = registry;
        this.artifactStore = artifactStore;
        this.events = events;
    }

    @Override
    public String stage() {
        return "SYNTHESIZE";
    }

    @Override
    public CouncilContext execute(CouncilContext context) {
        ModelProfile chair = registry.model(context.profile().chairModelId());
        ModelCallResult result = registry.clientForModel(chair.id()).call(new ModelCallRequest(
                context.session().id(),
                stage(),
                chair.id(),
                chair.providerModelId(),
                promptBuilder.synthesisMessages(context.session().question(), context.drafts(), context.scoreSummary(), context.reviews()),
                chair.defaultOutputTokens(),
                0.2,
                false,
                Duration.ofSeconds(180)
        ));
        context.setFinalAnswer(result.text());
        artifactStore.writeText(context.session().id(), "final/answer.md", result.text());
        events.publish(context.session().id(), stage(), "SYNTHESIS_COMPLETED", chair.id(), Map.of("characters", result.text().length()));
        return context;
    }
}
