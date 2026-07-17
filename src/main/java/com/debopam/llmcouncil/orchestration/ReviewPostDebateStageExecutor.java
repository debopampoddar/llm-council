// ── ReviewPostDebateStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallException;
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
 * REVIEW_POST_DEBATE stage: post-debate peer review incorporating debate arguments.
 *
 * <p><b>(Post-Debate Re-Review):</b> the second SCORE stage in the
 * rigorous protocol previously re-scored the same pre-debate reviews, making
 * it effectively redundant. This stage asks reviewers to re-evaluate drafts
 * after considering debate arguments, so the second scoring pass operates on
 * genuinely updated evidence.
 *
 * <p>Post-debate reviews are ADDED to the context (not replacing pre-debate
 * reviews). The SCORE stage filters reviews by draftId, so both pre- and
 * post-debate reviews contribute to the final score — more data is better.
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
        Set<String> validDraftIds = ctx.drafts().stream()
                .map(Draft::draftId).collect(Collectors.toSet());

        for (String modelId : ctx.policy().memberModelIds()) {
            ModelProfile model = registry.model(modelId);
            events.publish(ctx.session().id(), stage().name(),
                           "POST_DEBATE_REVIEW_STARTED", modelId, Map.of());
            try {
                // Use post-debate review prompt that includes debate transcript
                ModelCallResult result = registry.clientForModel(modelId).call(
                        new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                             model.providerModelId(),
                                             promptBuilder.postDebateReviewMessages(
                                                     ctx.session().question(),
                                                     ctx.drafts(),
                                                     ctx.debateRounds()),
                                             model.defaultOutputTokens(), model.temperature(),
                                             false, model.defaultTimeout()));

                artifactStore.writeText(ctx.session().id(),
                        "raw/review-post-debate-" + modelId + ".json", result.text());

                // Parse and filter reviews using the same logic as ReviewStageExecutor
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

                // ADD new reviews to context (don't replace pre-debate reviews)
                parsed.forEach(ctx::addReview);

                events.publish(ctx.session().id(), stage().name(),
                               "POST_DEBATE_REVIEW_COMPLETED", modelId,
                               Map.of("reviewCount", parsed.size()));
            } catch (ModelCallException ex) {
                events.publish(ctx.session().id(), stage().name(),
                               "POST_DEBATE_REVIEW_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ctx.excludeModel(modelId, "post-debate review failed: " + ex.getMessage());
            } catch (IllegalArgumentException ex) {
                events.publish(ctx.session().id(), stage().name(),
                               "POST_DEBATE_REVIEW_PARSE_FAILED", modelId,
                               Map.of("error", ex.getMessage()));
                ctx.excludeModel(modelId, "post-debate review parse failed: " + ex.getMessage());
            }
        }

        artifactStore.writeJson(ctx.session().id(), "normalized/reviews-post-debate.json", ctx.reviews());
        return ctx;
    }

    /**
     * Check if a review is a self-review (reviewer reviewing their own draft).
     */
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
