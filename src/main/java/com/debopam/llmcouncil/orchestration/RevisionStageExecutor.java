// ── RevisionStageExecutor.java 
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

import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REVISE stage: each member model revises its draft incorporating debate insights.
 *
 * <p><b>Gap 4.3 (Post-Debate Draft Revision):</b> after the DEBATE stage, each
 * model produces a revised answer that addresses legitimate criticisms, incorporates
 * strong counterarguments, and retains its original position where evidence supports
 * it. This gives drafts a chance to evolve before the second scoring pass.
 *
 * <p>If a model fails to revise, its original draft is retained to avoid losing
 * evidence. The stage is skipped entirely if no debate rounds occurred.
 */
@Component
public class RevisionStageExecutor implements StageExecutor {

    private static final Logger log = LoggerFactory.getLogger(RevisionStageExecutor.class);

    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public RevisionStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder,
                                  EventPublisher events, ArtifactStore artifactStore) {
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.events = events;
        this.artifactStore = artifactStore;
    }

    @Override
    public StageType stage() {
        return StageType.REVISE;
    }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        // Only revise if debate actually happened — otherwise there's nothing
        // new to incorporate and the original drafts are fine.
        if (ctx.debateRounds().isEmpty()) {
            events.publish(ctx.session().id(), stage().name(), "REVISION_SKIPPED", null,
                           Map.of("reason", "no debate rounds to incorporate"));
            return ctx;
        }

        // Map each member's original draft for lookup by model ID.
        // If a model produced multiple drafts (shouldn't happen), keep the last.
        Map<String, Draft> originalDraftsByModel = ctx.drafts().stream()
                .collect(Collectors.toMap(Draft::modelId, Function.identity(), (a, b) -> b));

        // Fan-out revision calls using virtual threads (same pattern as GENERATE).
        List<Draft> revisedDrafts = Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = ctx.policy().memberModelIds().stream()
                    .map(modelId -> executor.submit(() ->
                            reviseDraft(ctx, modelId, originalDraftsByModel.get(modelId))))
                    .toList();
            for (var f : futures) {
                try {
                    Draft d = f.get();
                    if (d != null) revisedDrafts.add(d);
                } catch (Exception ex) {
                    log.warn("Draft revision task failed", ex);
                }
            }
        }

        // For models whose revision failed, retain the original draft so the
        // downstream stages don't lose evidence.
        Set<String> revisedModelIds = revisedDrafts.stream()
                .map(Draft::modelId).collect(Collectors.toSet());
        for (Draft original : ctx.drafts()) {
            if (!revisedModelIds.contains(original.modelId())) {
                revisedDrafts.add(original);
                log.info("Retaining original draft for model {} (revision not available)", original.modelId());
            }
        }

        // Replace all drafts with revised versions.
        ctx.clearDrafts();
        revisedDrafts.forEach(ctx::addDraft);
        artifactStore.writeJson(ctx.session().id(), "normalized/drafts-revised.json", ctx.drafts());

        events.publish(ctx.session().id(), stage().name(), "REVISION_STAGE_COMPLETED", null,
                       Map.of("revisedCount", revisedModelIds.size(),
                              "retainedCount", revisedDrafts.size() - revisedModelIds.size()));
        return ctx;
    }

    /**
     * Revise a single model's draft using debate insights.
     *
     * @param ctx           The council context.
     * @param modelId       The model to call for revision.
     * @param originalDraft The model's original draft (may be null if it didn't produce one).
     * @return A revised Draft, or {@code null} on failure.
     */
    private Draft reviseDraft(CouncilContext ctx, String modelId, Draft originalDraft) {
        // If this model didn't produce a draft, nothing to revise.
        if (originalDraft == null) {
            log.debug("Model {} has no original draft to revise, skipping", modelId);
            return null;
        }

        ModelProfile model = registry.model(modelId);
        events.publish(ctx.session().id(), stage().name(), "REVISION_STARTED", modelId, Map.of());

        try {
            ModelCallResult result = registry.clientForModel(modelId).call(
                    new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                         model.providerModelId(),
                                         promptBuilder.revisionMessages(ctx.session().question(),
                                                                         ctx.session().context(),
                                                                         originalDraft,
                                                                         ctx.debateRounds()),
                                         model.defaultOutputTokens(), model.temperature(),
                                         false, model.defaultTimeout()));

            events.publish(ctx.session().id(), stage().name(), "REVISION_COMPLETED", modelId,
                           Map.of("chars", result.text().length(),
                                  "latencyMs", result.latency() != null ? result.latency().toMillis() : -1));

            // Reuse the same draftId so downstream stages can track the draft lineage.
            return new Draft(originalDraft.draftId(), modelId, result.text());
        } catch (ModelCallException ex) {
            events.publish(ctx.session().id(), stage().name(), "REVISION_FAILED", modelId,
                           Map.of("error", ex.getMessage()));
            ctx.addWarning("Revision failed for model " + modelId + ": " + ex.getMessage());
            return null; // original draft will be retained by the caller
        }
    }
}
