package com.debopam.llmcouncil;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the LLM Council Spring Boot application.
 *
 * <p>Starts an embedded Tomcat server on port 8080 by default.
 * Set API keys via environment variables:
 * <ul>
 *   <li>{@code OPENAI_API_KEY}</li>
 *   <li>{@code ANTHROPIC_API_KEY}</li>
 *   <li>{@code OLLAMA_BASE_URL} (default: http://localhost:11434)</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties
public class LlmCouncilApplication {
    public static void main(String[] args) {
        SpringApplication.run(LlmCouncilApplication.class, args);
    }
}
