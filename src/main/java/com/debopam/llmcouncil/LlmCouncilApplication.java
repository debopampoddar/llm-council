/**
 * Auto-generated documentation for LlmCouncilApplication.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil;

import com.debopam.llmcouncil.config.CouncilProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CouncilProperties.class)
public class LlmCouncilApplication {
    public static void main(String[] args) {
        SpringApplication.run(LlmCouncilApplication.class, args);
    }
}