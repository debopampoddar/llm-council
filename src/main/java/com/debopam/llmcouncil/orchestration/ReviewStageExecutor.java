package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ChatMessage;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * REVIEW stage: each member model writes a qualitative peer review of all
 * anonymised drafts. Implements the LLM-as-Judge peer review pattern.
 */
@Component
public class ReviewStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final StructuredOutputParser parser;
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public ReviewStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder,
                               StructuredOutputParser parser, EventPublisher events,
                               ArtifactStore artifactStore) {
        this.registry = registry; this.promptBuilder = promptBuilder;
        this.parser = parser; this.events = events; this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.REVIEW; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        for (String modelId : ctx.policy().memberModelIds()) {
            ModelProfile model = ctx.executionRegistry(registry).model(modelId);
            events.publish(ctx.session().id(), stage().name(), "REVIEW_STARTED", modelId, Map.of());
            try {
                PromptBudget budget = PromptBudget.forModel(model);
                List<ChatMessage> messages =
                        promptBuilder.reviewMessages(ctx.session().question(), ctx.session().context(),
                                                     ctx.drafts(), budget);
                PromptBudgets.record(ctx, events, stage(), modelId, budget);

                ModelCallResult result = ctx.executionRegistry(registry).clientForModel(modelId).call(
                        new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                             model.providerModelId(), messages,
                                             model.defaultOutputTokens(), model.temperature(), true, model.defaultTimeout()));
                ctx.recordUsage(model.id(), stage(), result.promptTokens(), result.completionTokens(), result.latency());
                artifactStore.writeText(ctx.session().id(), "raw/review-" + modelId + ".json", result.text());
                StructuredOutputParser.ReviewEnvelope envelope = parser.parseReviews(result.text());
                ReviewEvidence.Batch batch = ReviewEvidence.normalize(
                        ctx, modelId, envelope.reviews(), result.text());
                batch.reviews().forEach(ctx::addReview);
                publishParserDiagnostics(ctx, modelId, envelope.diagnostics());
                if (!batch.complete()) {
                    batch = recoverMissing(ctx, model, batch);
                }
                recordCoverage(ctx, modelId, batch);
                publishCompleted(ctx, modelId, batch);
            } catch (ModelCallException ex) {
                events.publish(ctx.session().id(), stage().name(), "REVIEW_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ctx.excludeModel(modelId, "review failed: " + ex.getMessage());
                recordCoverage(ctx, modelId,
                        ReviewEvidence.normalize(ctx, modelId, List.of(), ""));
            } catch (IllegalArgumentException ex) {
                events.publish(ctx.session().id(), stage().name(), "REVIEW_PARSE_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ReviewEvidence.Batch batch = recoverMissing(ctx, model,
                        ReviewEvidence.normalize(ctx, modelId, List.of(), ""));
                if (batch.reviews().isEmpty()) {
                    ctx.excludeModel(modelId, "review parse failed after recovery: "
                            + ex.getMessage());
                }
                recordCoverage(ctx, modelId, batch);
                publishCompleted(ctx, modelId, batch);
            }
        }
        artifactStore.writeJson(ctx.session().id(), "normalized/reviews.json", ctx.reviews());
        return ctx;
    }

    /**
     * Makes one bounded semantic-recovery call for exactly the reviews omitted
     * from a successful response, or for all required non-self reviews when the
     * first response is malformed. Transport retries remain the model client's
     * responsibility.
     */
    private ReviewEvidence.Batch recoverMissing(CouncilContext ctx, ModelProfile model,
                                                 ReviewEvidence.Batch initial) {
        String modelId = model.id();
        List<Draft> missingDrafts = ctx.drafts().stream()
                .filter(draft -> initial.missingDraftIds().contains(draft.draftId()))
                .toList();
        events.publish(ctx.session().id(), stage().name(), "REVIEW_RECOVERY_STARTED", modelId,
                Map.of("missingDraftIds", initial.missingDraftIds(),
                        "missingReviewCount", initial.missingDraftIds().size()));
        try {
            PromptBudget budget = PromptBudget.forModel(model);
            List<ChatMessage> messages = promptBuilder.reviewMessages(
                    ctx.session().question(), TrustBoundaryGuard.sanitize(ctx.session().context()),
                    missingDrafts, budget);
            PromptBudgets.record(ctx, events, stage(), modelId, budget);
            ModelCallResult result = ctx.executionRegistry(registry).clientForModel(modelId).call(
                    new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                            model.providerModelId(), messages, model.defaultOutputTokens(),
                            model.temperature(), true, model.defaultTimeout()));
            ctx.recordUsage(model.id(), stage(), result.promptTokens(), result.completionTokens(), result.latency());
            artifactStore.writeText(ctx.session().id(),
                    "raw/review-recovery-" + modelId + "-attempt-1.json", result.text());
            StructuredOutputParser.ReviewEnvelope envelope = parser.parseReviews(result.text());
            ReviewEvidence.Batch recovery = ReviewEvidence.normalize(ctx, modelId,
                    envelope.reviews(), result.text(), initial.missingDraftIds());
            recovery.reviews().forEach(ctx::addReview);
            publishParserDiagnostics(ctx, modelId, envelope.diagnostics());
            ReviewEvidence.Batch merged = ReviewEvidence.merge(initial, recovery);
            events.publish(ctx.session().id(), stage().name(), "REVIEW_RECOVERY_COMPLETED", modelId,
                    Map.of("recoveredReviewCount", recovery.reviews().size(),
                            "remainingDraftIds", merged.missingDraftIds(),
                            "complete", merged.complete()));
            return merged;
        } catch (ModelCallException | IllegalArgumentException ex) {
            events.publish(ctx.session().id(), stage().name(), "REVIEW_RECOVERY_FAILED", modelId,
                    Map.of("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                            "missingDraftIds", initial.missingDraftIds()));
            return initial;
        }
    }

    private void publishCompleted(CouncilContext ctx, String modelId,
                                  ReviewEvidence.Batch batch) {
        events.publish(ctx.session().id(), stage().name(), "REVIEW_COMPLETED", modelId,
                Map.of("reviewCount", batch.reviews().size(),
                        "expectedReviewCount", batch.expectedDraftIds().size(),
                        "complete", batch.complete()));
    }

    private void publishParserDiagnostics(CouncilContext ctx, String modelId,
                                          List<String> diagnostics) {
        if (!diagnostics.isEmpty()) {
            events.publish(ctx.session().id(), stage().name(),
                           "REVIEW_OUTPUT_RECOVERED", modelId,
                           Map.of("diagnostics", diagnostics,
                                  "diagnosticCount", diagnostics.size()));
        }
    }

    private void recordCoverage(CouncilContext ctx, String modelId, ReviewEvidence.Batch batch) {
        if (batch.duplicateCount() > 0 || batch.unknownDraftCount() > 0) {
            events.publish(ctx.session().id(), stage().name(),
                           "REVIEW_OUTPUT_FILTERED", modelId,
                           Map.of("duplicateCount", batch.duplicateCount(),
                                  "unknownDraftCount", batch.unknownDraftCount(),
                                  "selfReviewCount", batch.selfReviewCount()));
        }
        if (batch.complete()) {
            return;
        }
        String warning = "Review coverage incomplete for " + modelId + ": missing "
                + batch.missingDraftIds().size() + " of " + batch.expectedDraftIds().size()
                + " required non-self reviews " + batch.missingDraftIds();
        ctx.markDegraded(warning);
        events.publish(ctx.session().id(), stage().name(), "REVIEW_INCOMPLETE", modelId,
                       Map.of("expectedReviewCount", batch.expectedDraftIds().size(),
                              "reviewCount", batch.reviews().size(),
                              "missingDraftIds", batch.missingDraftIds(),
                              "reason", warning));
    }
}
