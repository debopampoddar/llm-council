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
import java.util.Set;
import java.util.stream.Collectors;

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
        Set<String> validDraftIds = ctx.drafts().stream().map(Draft::draftId).collect(Collectors.toSet());
        for (String modelId : ctx.policy().memberModelIds()) {
            ModelProfile model = registry.model(modelId);
            events.publish(ctx.session().id(), stage().name(), "REVIEW_STARTED", modelId, Map.of());
            try {
                PromptBudget budget = PromptBudget.forModel(model);
                List<ChatMessage> messages =
                        promptBuilder.reviewMessages(ctx.session().question(), ctx.drafts(), budget);
                PromptBudgets.record(ctx, events, stage(), modelId, budget);

                ModelCallResult result = registry.clientForModel(modelId).call(
                        new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                             model.providerModelId(), messages,
                                             model.defaultOutputTokens(), model.temperature(), false, model.defaultTimeout()));
                artifactStore.writeText(ctx.session().id(), "raw/review-" + modelId + ".json", result.text());
                List<ReviewArtifact> parsed = parser.parseReviews(result.text()).reviews().stream()
                        .filter(review -> validDraftIds.contains(review.draftId()))
                        .filter(review -> !isSelfReview(ctx, modelId, review.draftId()))
                        .map(review -> new ReviewArtifact(modelId, review.draftId(),
                                                          safeList(review.strengths()),
                                                          safeList(review.issues()),
                                                          safeList(review.criteria()),
                                                          review.overallScore(),
                                                          review.confidence(),
                                                          result.text()))
                        .toList();
                parsed.forEach(ctx::addReview);
                events.publish(ctx.session().id(), stage().name(), "REVIEW_COMPLETED", modelId,
                               Map.of("reviewCount", parsed.size()));
            } catch (ModelCallException ex) {
                events.publish(ctx.session().id(), stage().name(), "REVIEW_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ctx.excludeModel(modelId, "review failed: " + ex.getMessage());
            } catch (IllegalArgumentException ex) {
                events.publish(ctx.session().id(), stage().name(), "REVIEW_PARSE_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ctx.excludeModel(modelId, "review parse failed: " + ex.getMessage());
            }
        }
        artifactStore.writeJson(ctx.session().id(), "normalized/reviews.json", ctx.reviews());
        return ctx;
    }

    private boolean isSelfReview(CouncilContext ctx, String reviewerId, String draftId) {
        return ctx.drafts().stream()
                  .filter(d -> d.draftId().equals(draftId))
                  .findFirst()
                  .map(d -> reviewerId.equals(d.modelId()))
                  .orElse(false);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
