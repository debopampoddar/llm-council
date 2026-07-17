package com.debopam.llmcouncil.config;

import com.google.cloud.vertexai.VertexAI;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * Conditional Gemini/Vertex AI configuration.
 *
 * <p>The Vertex AI auto-configuration is excluded from the main application
 * (see {@link com.debopam.llmcouncil.LlmCouncilApplication}) because it
 * throws at startup when no GCP project ID is set, unlike OpenAI and
 * Anthropic starters which accept placeholder keys gracefully.
 *
 * <p>This class manually creates the Vertex AI beans <b>only when</b>
 * {@code spring.ai.vertex.ai.gemini.project-id} is set to a non-blank value.
 * When the property is absent or blank, the Gemini ChatModel bean is never
 * created and models configured with {@code provider: gemini} fall through
 * to {@link com.debopam.llmcouncil.model.UnavailableModelClient}.
 *
 * <h3>Authentication options</h3>
 * <ol>
 *   <li><b>Application Default Credentials (ADC)</b>:
 *       {@code gcloud auth application-default login}</li>
 *   <li><b>Service account JSON</b>:
 *       {@code GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json}</li>
 * </ol>
 *
 * <p>Both options require {@code GOOGLE_CLOUD_PROJECT} to identify the GCP project.
 */
@Configuration
@ConditionalOnExpression("!'${spring.ai.vertex.ai.gemini.project-id:}'.isBlank()")
public class GeminiConditionalConfig {

    private static final Logger log = LoggerFactory.getLogger(GeminiConditionalConfig.class);

    @Bean
    public VertexAI vertexAi(
            @Value("${spring.ai.vertex.ai.gemini.project-id}") String projectId,
            @Value("${spring.ai.vertex.ai.gemini.location:us-central1}") String location) {
        log.info("Gemini/Vertex AI: creating VertexAI client for project='{}', location='{}'",
                 projectId, location);
        return new VertexAI.Builder()
                .setProjectId(projectId)
                .setLocation(location)
                .build();
    }

    @Bean
    public VertexAiGeminiChatModel vertexAiGeminiChatModel(
            VertexAI vertexAi,
            @Value("${spring.ai.vertex.ai.gemini.chat.options.model:gemini-2.5-flash}") String model,
            @Value("${spring.ai.vertex.ai.gemini.chat.options.temperature:0.3}") double temperature,
            ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        log.info("Gemini/Vertex AI: creating ChatModel with model='{}', temperature={}",
                 model, temperature);

        var options = VertexAiGeminiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        // Use builder to match Spring AI 1.0.0's constructor requirements
        var builder = VertexAiGeminiChatModel.builder()
                .vertexAI(vertexAi)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.defaultInstance());

        // Optionally inject ToolCallingManager and ObservationRegistry if available
        ToolCallingManager tcm = toolCallingManagerProvider.getIfAvailable();
        if (tcm != null) {
            builder.toolCallingManager(tcm);
        }

        ObservationRegistry or = observationRegistryProvider.getIfAvailable();
        if (or != null) {
            builder.observationRegistry(or);
        }

        return builder.build();
    }
}
