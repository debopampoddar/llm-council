// ── AggregationStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * AGGREGATE stage: implements the MoA aggregator layer (Wang et al., 2024).
 * Each member model sees all initial drafts and produces a refined answer.
 * Refined drafts replace initial ones in CouncilContext.
 */
@Component
public class AggregationStageExecutor implements StageExecutor {
    private static final Logger log = LoggerFactory.getLogger(AggregationStageExecutor.class);
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final EventPublisher events;

    public AggregationStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder, EventPublisher events) {
        this.registry = registry; this.promptBuilder = promptBuilder; this.events = events;
    }

    @Override public StageType stage() { return StageType.AGGREGATE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        List<Draft> initialDrafts = List.copyOf(ctx.drafts()); // snapshot before fan-out
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = ctx.policy().memberModelIds().stream()
                             .map(modelId -> executor.submit(() -> aggregate(ctx, modelId, initialDrafts)))
                             .toList();
            ctx.clearDrafts();
            for (var f : futures) {
                try { Draft d = f.get(); if (d != null) ctx.addDraft(d); }
                catch (Exception ex) { log.warn("Aggregation collection failed", ex); }
            }
        }
        return ctx;
    }

    private Draft aggregate(CouncilContext ctx, String modelId, List<Draft> allDrafts) {
        ModelProfile model = registry.model(modelId);
        events.publish(ctx.session().id(), stage().name(), "AGGREGATE_STARTED", modelId, Map.of());
        try {
            ModelCallResult result = registry.clientForModel(modelId).call(
                    new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                         model.providerModelId(),
                                         promptBuilder.aggregationMessages(ctx.session().question(),
                                                                           ctx.session().context(), allDrafts, modelId),
                                         model.defaultOutputTokens(), model.temperature(), false, model.defaultTimeout()));
            events.publish(ctx.session().id(), stage().name(), "AGGREGATE_COMPLETED", modelId, Map.of());
            return new Draft(modelId + "_agg", modelId, result.text());
        } catch (ModelCallException ex) {
            events.publish(ctx.session().id(), stage().name(), "AGGREGATE_FAILED", modelId,
                           Map.of("error", ex.getMessage()));
            return null;
        }
    }
}
