package com.debopam.llmcouncil.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Startup banner showing which LLM providers are auto-detected as available.
 *
 * <p>Providers are auto-detected by inspecting their configured API keys or
 * GCP project IDs against known placeholder values. No explicit "enabled"
 * flags are needed — just set a real credential and the provider activates.
 *
 * <h3>How to activate a provider</h3>
 * <ul>
 *   <li><b>OpenAI</b>: set {@code SPRING_AI_OPENAI_API_KEY=sk-...}</li>
 *   <li><b>Anthropic</b>: set {@code SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...}</li>
 *   <li><b>Gemini (Vertex AI)</b>: set {@code GOOGLE_CLOUD_PROJECT=my-project}
 *       and authenticate via {@code gcloud auth application-default login}
 *       or {@code GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json}</li>
 *   <li><b>Ollama</b>: always available (local, no credentials needed)</li>
 * </ul>
 */
@Configuration
public class ProviderAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProviderAutoConfiguration.class);

    /**
     * Logs a provider status banner at startup so operators can see at a
     * glance which providers were auto-detected as available.
     *
     * @return the banner string (also logged at INFO level)
     */
    @Bean
    public String providerStatusBanner(
            @Value("${spring.ai.openai.api-key:}") String openAiApiKey,
            @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
            @Value("${spring.ai.vertex.ai.gemini.project-id:}") String geminiProjectId) {

        boolean openAi = CouncilConfig.hasRealCredential(openAiApiKey);
        boolean anthropic = CouncilConfig.hasRealCredential(anthropicApiKey);
        boolean gemini = CouncilConfig.hasRealCredential(geminiProjectId);

        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════╗\n");
        sb.append("║       LLM Council — Provider Status              ║\n");
        sb.append("╠══════════════════════════════════════════════════╣\n");
        appendStatus(sb, "OpenAI",    openAi,    "SPRING_AI_OPENAI_API_KEY");
        appendStatus(sb, "Anthropic", anthropic,  "SPRING_AI_ANTHROPIC_API_KEY");
        appendStatus(sb, "Gemini",    gemini,     "GOOGLE_CLOUD_PROJECT");
        sb.append("║  Ollama .............. ✅ ALWAYS AVAILABLE       ║\n");
        sb.append("║  Mock ................ ✅ ALWAYS AVAILABLE       ║\n");
        sb.append("╚══════════════════════════════════════════════════╝");

        String banner = sb.toString();
        log.info(banner);
        return banner;
    }

    /**
     * Appends one provider status line to the banner.
     */
    private void appendStatus(StringBuilder sb, String name, boolean detected, String credentialHint) {
        // Pad name for alignment
        String padded = String.format("%-18s", name + " ");
        if (detected) {
            sb.append("║  ").append(padded).append("✅ DETECTED (auto)       ║\n");
        } else {
            sb.append("║  ").append(padded).append("⬚  NOT CONFIGURED        ║\n");
        }
    }
}
