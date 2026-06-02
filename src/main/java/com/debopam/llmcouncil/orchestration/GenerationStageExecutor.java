package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.debopam.llmcouncil.orchestration.StageType.GENERATE;

@Component
public class GenerationStageExecutor implements StageExecutor {
    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder = new PromptBuilder();
    private final EventPublisher events;

    public GenerationStageExecutor(ModelRegistry registry, EventPublisher events) {
        this.registry = registry;
        this.events = events;
    }

    @Override
    public StageType stage() {
        return GENERATE;
    }

    @Override
    public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
        for (String modelId : context.profile().memberModelIds()) {
            ModelProfile model = registry.model(modelId);
            events.publish(context.session().id(), stage().name(), "MODEL_CALL_STARTED", modelId, Map.of());
            ModelCallResult result = registry.clientForModel(modelId).call(new ModelCallRequest(
                    context.session().id(),
                    stage(),
                    model.id(),
                    model.providerModelId(),
                    promptBuilder.generationMessages(context.session().question(), context.session().context()),
                    model.defaultOutputTokens(),
                    0.2,
                    false,
                    java.time.Duration.ofSeconds(120)
            ));
            String draftId = model.id();
            context.addDraft(new Draft(draftId, model.id(), result.text()));
            events.publish(context.session().id(),
                    stage().name(),
                    "MODEL_CALL_COMPLETED",
                    modelId,
                    Map.of("characters", result.text().length()));
        }
        return context;
    }
}
