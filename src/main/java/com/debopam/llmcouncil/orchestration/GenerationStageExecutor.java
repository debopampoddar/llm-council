// ── GenerationStageExecutor.java ──────────────────────────────────────────
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Executors;

/**
 * GENERATE stage: fan-out user question to all member models using virtual
 * threads (JDK 21+), collect independent first-draft answers.
 * Implements the MoA proposer layer (Wang et al., 2024).
 */
@Component
public class GenerationStageExecutor implements StageExecutor {
    private static final Logger log = LoggerFactory.getLogger(GenerationStageExecutor.class);
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public GenerationStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder,
                                   EventPublisher events, ArtifactStore artifactStore) {
        this.registry = registry; this.promptBuilder = promptBuilder;
        this.events = events; this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.GENERATE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = ctx.policy().memberModelIds().stream()
                             .map(modelId -> executor.submit(() -> callModel(ctx, modelId)))
                             .toList();
            for (var f : futures) {
                try { Draft d = f.get(); if (d != null) ctx.addDraft(d); }
                catch (Exception ex) { log.warn("Draft collection failed", ex); }
            }
        }
        artifactStore.writeJson(ctx.session().id(), "normalized/drafts-generation.json", ctx.drafts());
        if (ctx.drafts().size() < ctx.policy().minimumSuccessfulDrafts()) {
            ctx.markFailed(stage(), new IllegalStateException(
                    "Draft quorum not met: " + ctx.drafts().size() + "/"
                    + ctx.policy().minimumSuccessfulDrafts()));
        }
        return ctx;
    }

    private Draft callModel(CouncilContext ctx, String modelId) {
        ModelProfile model = registry.model(modelId);
        events.publish(ctx.session().id(), stage().name(), "MODEL_CALL_STARTED", modelId,
                       Map.of("providerModelId", model.providerModelId()));
        try {
            ModelCallResult result = registry.clientForModel(modelId).call(
                    new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                         model.providerModelId(),
                                         promptBuilder.generationMessagesWithCoT(ctx.session().question(), ctx.session().context()),
                                         model.defaultOutputTokens(), model.temperature(), false, model.defaultTimeout()));
            artifactStore.writeText(ctx.session().id(), "raw/generate-" + modelId + ".txt", result.text());
            events.publish(ctx.session().id(), stage().name(), "MODEL_CALL_COMPLETED", modelId,
                           Map.of("chars", result.text().length(),
                                  "latencyMs", result.latency() != null ? result.latency().toMillis() : -1));
            return new Draft(modelId, modelId, result.text());
        } catch (ModelCallException ex) {
            events.publish(ctx.session().id(), stage().name(), "MODEL_CALL_FAILED", modelId,
                           Map.of("error", ex.getMessage(), "category", ex.category().name()));
            ctx.recordModelFailure(model, ex);
            ctx.excludeModel(modelId, ex.getMessage());
            return null;
        }
    }
}
