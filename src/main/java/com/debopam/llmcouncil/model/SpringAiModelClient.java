/**
 * Auto-generated documentation for SpringAiModelClient.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class SpringAiModelClient implements CouncilModelClient {
    private final String providerId;
    private final ChatClient chatClient;

    public SpringAiModelClient(String providerId, ChatClient chatClient) {
        this.providerId = providerId;
        this.chatClient = chatClient;
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) {
        Instant started = Instant.now();
        StringBuilder prompt = new StringBuilder();
        for (ChatMessage message : request.messages()) {
            prompt.append(message.role()).append(": ").append(message.content()).append("\n\n");
        }
        String content = chatClient.prompt()
                .user(prompt.toString())
                .call()
                .content();
        return new ModelCallResult(
                request.logicalModelId(),
                providerId,
                content,
                0,
                content == null ? 0 : content.length() / 4,
                Duration.between(started, Instant.now()),
                Map.of("springAi", true)
        );
    }

    @Override
    public ModelHealth health() {
        return ModelHealth.available(providerId);
    }
}
