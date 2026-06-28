// ── SynthesisStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SYNTHESIZE stage: the chair model integrates all drafts, reviews, scores,
 * and debate history into the final council answer.
 */
@Component
public class SynthesisStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public SynthesisStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder,
                                  EventPublisher events, ArtifactStore artifactStore) {
        this.registry = registry; this.promptBuilder = promptBuilder;
        this.events = events; this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.SYNTHESIZE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) throws Exception {
        if (ctx.drafts().size() < ctx.policy().minimumSuccessfulDrafts()) {
            ctx.markFailed(stage(), new IllegalStateException(
                    "Cannot synthesize because draft quorum is not met"));
            return ctx;
        }

        String chairId = ctx.policy().chairModelId();
        ModelProfile chair = registry.model(chairId);
        boolean preserveDissent = opts.getBoolean("preserve-dissent", true);
        events.publish(ctx.session().id(), stage().name(), "SYNTHESIS_STARTED", chairId, Map.of());

        ModelCallResult result = registry.clientForModel(chairId).call(
                new ModelCallRequest(ctx.session().id(), stage(), chair.id(),
                                     chair.providerModelId(),
                                     promptBuilder.synthesisMessages(ctx.session().question(),
                                                                     ctx.session().context(), ctx.drafts(), ctx.reviews(),
                                                                     ctx.scores(), ctx.debateRounds(), preserveDissent),
                                     chair.defaultOutputTokens(), chair.temperature(), false, chair.defaultTimeout()));

        ctx.setSynthesisResult(result.text());
        artifactStore.writeText(ctx.session().id(), "final/answer.md", result.text());
        events.publish(ctx.session().id(), stage().name(), "SYNTHESIS_COMPLETED", chairId,
                       Map.of("chars", result.text().length()));
        return ctx;
    }
}
