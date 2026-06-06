/**
 * Auto-generated documentation for CouncilConfig.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class CouncilConfig {
    @Bean
    public ExecutorService applicationTaskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
