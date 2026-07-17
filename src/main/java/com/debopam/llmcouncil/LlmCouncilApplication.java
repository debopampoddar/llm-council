package com.debopam.llmcouncil;

import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiMultiModalEmbeddingAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.gemini.VertexAiGeminiChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the LLM Council Spring Boot application.
 *
 * <p>Starts an embedded Tomcat server on port 8080 by default.
 *
 * <h3>Provider auto-detection</h3>
 * <p>Cloud providers activate automatically when real credentials are set:
 * <ul>
 *   <li>{@code SPRING_AI_OPENAI_API_KEY=sk-...} → OpenAI active</li>
 *   <li>{@code SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...} → Anthropic active</li>
 *   <li>{@code GOOGLE_CLOUD_PROJECT=my-project} → Gemini/Vertex AI active</li>
 *   <li>Ollama is always available (local, no credentials)</li>
 * </ul>
 *
 * <p>The Vertex AI auto-configuration classes are excluded because they throw
 * at startup when no GCP project ID is set (unlike OpenAI/Anthropic which
 * accept placeholder keys). The Gemini ChatModel is conditionally created in
 * {@link com.debopam.llmcouncil.config.GeminiConditionalConfig} only when a
 * real {@code GOOGLE_CLOUD_PROJECT} is detected.
 */
@SpringBootApplication(exclude = {
        VertexAiGeminiChatAutoConfiguration.class,
        VertexAiTextEmbeddingAutoConfiguration.class,
        VertexAiMultiModalEmbeddingAutoConfiguration.class
})
@EnableConfigurationProperties
public class LlmCouncilApplication {
    public static void main(String[] args) {
        SpringApplication.run(LlmCouncilApplication.class, args);
    }
}
