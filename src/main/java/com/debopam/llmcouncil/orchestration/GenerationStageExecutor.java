package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Generation stage: fan-out the user question to all council member models
 * and collect their initial drafts.
 */
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
        return StageType.GENERATE;
    }

    @Override
    public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
        // Use virtual threads so multiple model calls can proceed in parallel
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = context.profile().memberModelIds().stream()
                                 .map(modelId -> executor.submit(() -> callModel(context, modelId)))
                                 .toList();

            // Join all tasks; failures are recorded as events but do not abort the whole stage.
            for (var future : futures) {
                try {
                    Draft draft = future.get();
                    if (draft != null) {
                        context.addDraft(draft);
                    }
                } catch (Exception ex) {
                    // Future already logged the problem; nothing more to do here.
                }
            }
        }

        return context;
    }

    private Draft callModel(CouncilContext context, String modelId) {
        ModelProfile model = registry.model(modelId);
        events.publish(context.session().id(), stage().name(), "MODEL_CALL_STARTED", modelId, Map.of());

        try {
            ModelCallResult result = registry.clientForModel(modelId).call(
                    new ModelCallRequest(
                            context.session().id(),
                            stage(),
                            model.id(),
                            model.providerModelId(),
                            promptBuilder.generationMessages(
                                    context.session().question(),
                                    context.session().context()
                            ),
                            model.defaultOutputTokens(),
                            model.temperature(),          // consider adding this to ModelProfile
                            false,
                            model.defaultTimeout()        // consider adding this too
                    )
            );

            String draftId = model.id();
            Draft draft = new Draft(draftId, model.id(), result.text());

            events.publish(
                    context.session().id(),
                    stage().name(),
                    "MODEL_CALL_COMPLETED",
                    modelId,
                    Map.of("characters", result.text().length())
            );

            return draft;
        } catch (ModelCallException ex) {
            // Record the failure in events; synthesis/validation stages can incorporate this as dissent/uncertainty.
            events.publish(
                    context.session().id(),
                    stage().name(),
                    "MODEL_CALL_FAILED",
                    modelId,
                    Map.of(
                            "errorType", ex.getClass().getSimpleName(),
                            "message", ex.getMessage()
                    )
            );
            return null;
        }
    }
}
