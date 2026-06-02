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

import static com.debopam.llmcouncil.orchestration.StageType.VALIDATE;

@Component
public class ValidationStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final StructuredReviewParser parser;
    private final ArtifactStore artifactStore;
    private final EventPublisher events;

    public ValidationStageExecutor(ModelRegistry registry,
                                   StructuredReviewParser parser,
                                   ArtifactStore artifactStore,
                                   EventPublisher events) {
        this.registry = registry;
        this.parser = parser;
        this.artifactStore = artifactStore;
        this.events = events;
    }

    @Override
    public StageType stage() {
        return VALIDATE;
    }

    @Override
    public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
        ModelProfile validator = registry.model(context.profile().freshEyesModelId());
        ModelCallResult result = registry.clientForModel(validator.id()).call(new ModelCallRequest(
                context.session().id(),
                stage(),
                validator.id(),
                validator.providerModelId(),
                promptBuilder.validationMessages(context.session().question(), context.finalAnswer()),
                validator.defaultOutputTokens(),
                0.0,
                true,
                Duration.ofSeconds(120)
        ));
        ValidationOutput output = parser.parseValidation(result.text());
        context.setValidationOutput(output);
        artifactStore.writeJson(context.session().id(), "final/validation.json", output);
        events.publish(context.session().id(), stage().name(), "VALIDATION_COMPLETED", validator.id(), Map.of("approved", output.approved()));
        return context;
    }
}
