/**
 * Auto-generated documentation for ModelClientConfig.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.model.CouncilModelClient;
import com.debopam.llmcouncil.model.MockModelClient;
import com.debopam.llmcouncil.model.OllamaModelClient;
import com.debopam.llmcouncil.model.OpenAiCompatibleModelClient;
import com.debopam.llmcouncil.model.ProviderKind;
import com.debopam.llmcouncil.model.ProviderProfile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class ModelClientConfig {
    @Bean
    public Map<String, CouncilModelClient> councilModelClients(CouncilProperties properties, WebClient.Builder builder) {
        Map<String, CouncilModelClient> clients = new HashMap<>();
        properties.providers().forEach((id, cfg) -> {
            ProviderProfile provider = new ProviderProfile(
                    id,
                    ProviderKind.valueOf(cfg.kind().replace("-", "_").toUpperCase()),
                    cfg.baseUrl(),
                    cfg.apiKeyEnv(),
                    cfg.maxConcurrentRequests() == null ? 1 : cfg.maxConcurrentRequests(),
                    cfg.timeout() == null ? Duration.ofSeconds(120) : cfg.timeout()
            );
            CouncilModelClient client = switch (provider.kind()) {
                case MOCK -> new MockModelClient(provider);
                case OPENAI_COMPATIBLE -> new OpenAiCompatibleModelClient(provider, builder);
                case OLLAMA -> new OllamaModelClient(provider, builder);
                case SPRING_AI ->
                        throw new IllegalArgumentException("Register SpringAiModelClient with a Spring AI ChatClient bean for provider " + id);
            };
            clients.put(id, client);
        });
        return clients;
    }
}
