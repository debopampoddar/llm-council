// ── ReviewPostDebateStageExecutor.java 
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
 * REVIEW_POST_DEBATE stage: post-debate peer review incorporating debate arguments.
 *
 * <p><b>(Post-Debate Re-Review):</b> the second SCORE stage in the
 * rigorous protocol previously re-scored the same pre-debate reviews, making
 * it effectively redundant. This stage asks reviewers to re-evaluate drafts
 * after considering debate arguments, so the second scoring pass operates on
 * genuinely updated evidence.
 *
 * <p>Post-debate reviews are retained alongside the pre-debate audit trail, but
 * the second SCORE pass uses this pass alone. Blending old and new reviews would
 * dilute the very change the debate is intended to measure and could hide
 * persistent post-debate disagreement.
 */
@Component
public class ReviewPostDebateStageExecutor implements StageExecutor {

    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final StructuredOutputParser parser;
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public ReviewPostDebateStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder,
                                          StructuredOutputParser parser, EventPublisher events,
                                          ArtifactStore artifactStore) {
        this.registry = registry;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.events = events;
        this.artifactStore = artifactStore;
    }

    @Override
    public StageType stage() {
        return StageType.REVIEW_POST_DEBATE;
    }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        if (ctx.debateRounds().isEmpty()) {
            events.publish(ctx.session().id(), stage().name(),
                    "POST_DEBATE_REVIEW_SKIPPED", null,
                    Map.of("reason", "No debate occurred; initial reviews remain current"));
            return ctx;
        }

        for (String modelId : ctx.policy().memberModelIds()) {
            ModelProfile model = registry.model(modelId);
            events.publish(ctx.session().id(), stage().name(),
                           "POST_DEBATE_REVIEW_STARTED", modelId, Map.of());
            try {
                // Use post-debate review prompt that includes debate transcript
                PromptBudget budget = PromptBudget.forModel(model);
                List<ChatMessage> messages = promptBuilder.postDebateReviewMessages(
                        ctx.session().question(), ctx.drafts(), ctx.debateRounds(), budget);
                PromptBudgets.record(ctx, events, stage(), modelId, budget);

                ModelCallResult result = registry.clientForModel(modelId).call(
                        new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                             model.providerModelId(), messages,
                                             model.defaultOutputTokens(), model.temperature(),
                                             true, model.defaultTimeout()));
                ctx.recordUsage(model.id(), stage(), result.promptTokens(), result.completionTokens(), result.latency());

                artifactStore.writeText(ctx.session().id(),
                        "raw/review-post-debate-" + modelId + ".json", result.text());

                StructuredOutputParser.ReviewEnvelope envelope = parser.parseReviews(result.text());
                ReviewEvidence.Batch batch = ReviewEvidence.normalize(
                        ctx, modelId, envelope.reviews(), result.text());
                batch.reviews().forEach(ctx::addPostDebateReview);
                publishParserDiagnostics(ctx, modelId, envelope.diagnostics());
                recordCoverage(ctx, modelId, batch);

                events.publish(ctx.session().id(), stage().name(),
                               "POST_DEBATE_REVIEW_COMPLETED", modelId,
                               Map.of("reviewCount", batch.reviews().size(),
                                      "expectedReviewCount", batch.expectedDraftIds().size(),
                                      "complete", batch.complete()));
            } catch (ModelCallException ex) {
                events.publish(ctx.session().id(), stage().name(),
                               "POST_DEBATE_REVIEW_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ctx.excludeModel(modelId, "post-debate review failed: " + ex.getMessage());
                recordCoverage(ctx, modelId,
                        ReviewEvidence.normalize(ctx, modelId, List.of(), ""));
            } catch (IllegalArgumentException ex) {
                events.publish(ctx.session().id(), stage().name(),
                               "POST_DEBATE_REVIEW_PARSE_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ctx.excludeModel(modelId, "post-debate review parse failed: " + ex.getMessage());
                recordCoverage(ctx, modelId,
                        ReviewEvidence.normalize(ctx, modelId, List.of(), ""));
            }
        }

        artifactStore.writeJson(ctx.session().id(), "normalized/reviews-post-debate.json",
                                ctx.postDebateReviews());
        return ctx;
    }

    private void publishParserDiagnostics(CouncilContext ctx, String modelId,
                                          List<String> diagnostics) {
        if (!diagnostics.isEmpty()) {
            events.publish(ctx.session().id(), stage().name(),
                           "POST_DEBATE_REVIEW_OUTPUT_RECOVERED", modelId,
                           Map.of("diagnostics", diagnostics,
                                  "diagnosticCount", diagnostics.size()));
        }
    }

    private void recordCoverage(CouncilContext ctx, String modelId, ReviewEvidence.Batch batch) {
        if (batch.duplicateCount() > 0 || batch.unknownDraftCount() > 0) {
            events.publish(ctx.session().id(), stage().name(),
                           "POST_DEBATE_REVIEW_OUTPUT_FILTERED", modelId,
                           Map.of("duplicateCount", batch.duplicateCount(),
                                  "unknownDraftCount", batch.unknownDraftCount(),
                                  "selfReviewCount", batch.selfReviewCount()));
        }
        if (batch.complete()) {
            return;
        }
        String warning = "Post-debate review coverage incomplete for " + modelId + ": missing "
                + batch.missingDraftIds().size() + " of " + batch.expectedDraftIds().size()
                + " required non-self reviews " + batch.missingDraftIds();
        ctx.markDegraded(warning);
        events.publish(ctx.session().id(), stage().name(),
                       "POST_DEBATE_REVIEW_INCOMPLETE", modelId,
                       Map.of("expectedReviewCount", batch.expectedDraftIds().size(),
                              "reviewCount", batch.reviews().size(),
                              "missingDraftIds", batch.missingDraftIds(),
                              "reason", warning));
    }
}
