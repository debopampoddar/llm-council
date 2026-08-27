package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the probe's Spring wiring without making a real provider call. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "council.allowMockFallback=true",
        // This is specifically the unconfigured-provider contract.  A real
        // developer credential must not turn the test into a paid live probe.
        "spring.ai.openai.api-key=unused-development-placeholder",
        "council.userConfigPath=target/model-probe-api/council-user.yml"
})
class ModelProbeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unacknowledgedCloudProbeIsRefusedBeforeProviderConstruction() throws Exception {
        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"provider":"openai","providerModelId":"gpt-4.1-mini"}
                                        """))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message", containsString("acknowledgement")));
    }

    @Test
    void unconfiguredCloudProbeFailsHonestlyEvenWhenGlobalMockFallbackIsEnabled() throws Exception {
        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"provider":"openai","providerModelId":"gpt-4.1-mini",
                                         "acknowledgeCloudCall":true}
                                        """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.reachable").value(false))
               .andExpect(jsonPath("$.status").value("CONFIGURATION_ERROR"))
               .andExpect(content().string(containsString("SPRING_AI_OPENAI_API_KEY")))
               .andExpect(content().string(not(containsString("unused-development-placeholder"))));
    }
}
