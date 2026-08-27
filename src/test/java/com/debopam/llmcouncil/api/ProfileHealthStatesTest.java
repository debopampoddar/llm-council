package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the preflight states the UI's health badge is built on.
 *
 * <p>A missing cloud credential is a configuration error and blocks a run before
 * the application can issue a paid request. A configured cloud provider may still
 * be {@code NOT_CHECKED}: endpoint probing is deferred until an explicit probe or
 * a run, and the UI must not present that state as verified access.
 *
 * <p>These tests ensure that a verified profile, a blocked profile, and the
 * fields required by the badge remain distinguishable.
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=unused-development-placeholder")
@AutoConfigureMockMvc
class ProfileHealthStatesTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void verifiedProfileIsRunnableWithNoWarnings() throws Exception {
        mockMvc.perform(get("/api/council/profiles/mock/health?depthMode=RIGOROUS"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.runnable").value(true))
               .andExpect(jsonPath("$.warnings").isEmpty())
               .andExpect(jsonPath("$.models[0].status").value("AVAILABLE"));
    }

    @Test
    void unconfiguredCloudProfileIsBlockedBeforeRun() throws Exception {
        mockMvc.perform(get("/api/council/profiles/openai/health?depthMode=BALANCED"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.runnable").value(false))
               .andExpect(jsonPath("$.models[0].status").value("CONFIGURATION_ERROR"))
               .andExpect(jsonPath("$.models[0].detail").value(containsString("SPRING_AI_OPENAI_API_KEY")));
    }

    @Test
    void everyModelReportsTheFieldsTheBadgeNeeds() throws Exception {
        // The badge renders modelId, providerModelId and a reason per model.
        mockMvc.perform(get("/api/council/profiles/mock/health?depthMode=QUICK"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.policyId").isNotEmpty())
               .andExpect(jsonPath("$.models[0].modelId").isNotEmpty())
               .andExpect(jsonPath("$.models[0].providerModelId").isNotEmpty())
               .andExpect(jsonPath("$.models[0].knownProviderModels").isArray());
    }
}
