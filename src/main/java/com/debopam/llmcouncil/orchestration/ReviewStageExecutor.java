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
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ReviewStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final StructuredReviewParser parser;
    private final ArtifactStore artifactStore;
    private final EventPublisher events;

    public ReviewStageExecutor(ModelRegistry registry, StructuredReviewParser parser,
                               ArtifactStore artifactStore,
                               EventPublisher events) {
        this.registry = registry;
        this.parser = parser;
        this.artifactStore = artifactStore;
        this.events = events;
    }

    @Override
    public String stage() {
        return "REVIEW";
    }

    @Override
    public CouncilContext execute(CouncilContext context) {
        Set<String> validDraftIds = context.anonymizedDraftSet().drafts().stream()
                .map(AnonymizedDraft::draftId)
                .collect(Collectors.toSet());

        for (String reviewerId : context.profile().memberModelIds()) {
            ModelProfile reviewer = registry.model(reviewerId);
            ModelCallResult result = registry.clientForModel(reviewerId).call(new ModelCallRequest(
                    context.session().id(),
                    stage(),
                    reviewer.id(),
                    reviewer.providerModelId(),
                    promptBuilder.reviewMessages(context.session().question(), context.anonymizedDraftSet().drafts()),
                    reviewer.defaultOutputTokens(),
                    0.0,
                    true,
                    Duration.ofSeconds(120)
            ));
            artifactStore.writeText(context.session().id(), "raw/review-" + reviewer.id() + ".json", result.text());
            PeerReviewOutput review = parser.parseReview(reviewer.id(), result.text(), validDraftIds);
            context.addReview(review);
            events.publish(context.session().id(), stage(), "REVIEW_COMPLETED", reviewer.id(), Map.of("evaluations", review.evaluations().size()));
        }
        artifactStore.writeJson(context.session().id(), "normalized/reviews.json", context.reviews());
        return context;
    }
}
