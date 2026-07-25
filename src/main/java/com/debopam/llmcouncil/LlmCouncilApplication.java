package com.debopam.llmcouncil;

import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiMultiModalEmbeddingAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration;
import org.springframework.ai.model.vertexai.autoconfigure.gemini.VertexAiGeminiChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

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
 *
 * <h3>Persistence</h3>
 * <p>{@code DataSourceAutoConfiguration} is excluded for a related reason. The
 * SQLite and H2 drivers are on the classpath so that
 * {@code council.persistence.type=jdbc} needs no extra install, but with an
 * embedded driver present and no explicit URL, Boot creates an in-memory H2
 * database of its own accord. Every {@code memory} user would then be running a
 * database, migrating a schema, and writing nothing to it. The datasource is
 * built instead by
 * {@link com.debopam.llmcouncil.persistence.jdbc.JdbcPersistenceConfig}, which
 * exists only under {@code type=jdbc}.
 *
 * <p>Scheduling is enabled for the retention sweep, which is the one thing in
 * this application that runs on a timer. Its first fire is an hour after
 * startup, so it costs a test context a thread pool and nothing else — the
 * in-memory stores still evict on write, and every retention test drives the
 * sweep directly rather than waiting for it.
 */
@SpringBootApplication(exclude = {
        VertexAiGeminiChatAutoConfiguration.class,
        VertexAiTextEmbeddingAutoConfiguration.class,
        VertexAiMultiModalEmbeddingAutoConfiguration.class,
        DataSourceAutoConfiguration.class
})
@EnableConfigurationProperties
@EnableScheduling
public class LlmCouncilApplication {
    public static void main(String[] args) {
        SpringApplication.run(LlmCouncilApplication.class, args);
    }
}
