package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.ModelProbeRequest;
import com.debopam.llmcouncil.api.dto.ModelProbeResponse;
import com.debopam.llmcouncil.application.ModelProbeOperations;
import com.debopam.llmcouncil.application.ModelProbeThrottledException;
import com.debopam.llmcouncil.config.user.SecretScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelProbeControllerTest {

    private StubProbeOperations service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new StubProbeOperations();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ModelProbeController(service, new SecretScanner(), new ObjectMapper())).build();
    }

    @Test
    void acceptedStrictRequestReturnsTheProbeResult() throws Exception {
        service.response = new ModelProbeResponse(
                "ollama", "llama3.1:8b", true, "OK", "completed", 4L, 2L, 1L);

        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"provider":"ollama","providerModelId":"llama3.1:8b",
                                         "acknowledgeCloudCall":false}
                                        """))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.reachable").value(true))
               .andExpect(jsonPath("$.status").value("OK"));

        assertEquals(new ModelProbeRequest("ollama", "llama3.1:8b", false), service.captured);
    }

    @Test
    void credentialFieldIsRefusedBeforeDelegationAndNeverEchoed() throws Exception {
        String secret = "sk-ant-do-not-echo-01234567890123456789";

        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"provider":"anthropic","providerModelId":"claude-sonnet-4",
                                         "apiKey":"%s","acknowledgeCloudCall":true}
                                        """.formatted(secret)))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(containsString("Credentials are not accepted")))
               .andExpect(content().string(not(containsString(secret))));
    }

    @Test
    void credentialShapeHiddenInModelIdIsRefusedAndNeverEchoed() throws Exception {
        String secret = "sk-ant-hidden-01234567890123456789";

        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"provider":"anthropic","providerModelId":"%s",
                                         "acknowledgeCloudCall":true}
                                        """.formatted(secret)))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(not(containsString(secret))));
    }

    @Test
    void unknownAndMalformedFieldsAreRejectedByStrictParsing() throws Exception {
        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"provider":"ollama","providerModelId":"llama3.1:8b","surprise":true}
                                        """))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(containsString("strict JSON")));

        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[]"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void throttlingUses429AndRetryAfter() throws Exception {
        service.failure = new ModelProbeThrottledException(7);

        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"provider":"ollama","providerModelId":"llama3.1:8b"}
                                        """))
               .andExpect(status().isTooManyRequests())
               .andExpect(header().string("Retry-After", "7"))
               .andExpect(jsonPath("$.remediation", containsString("7 seconds")));
    }

    @Test
    void oversizedBodyIsRejectedBeforeParsingOrDelegation() throws Exception {
        String oversized = "{\"provider\":\"ollama\",\"providerModelId\":\""
                + "x".repeat(2_100) + "\"}";

        mockMvc.perform(post("/api/council/config/models/probe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(oversized))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(containsString("too large")));
        assertEquals(null, service.captured);
    }

    private static final class StubProbeOperations implements ModelProbeOperations {
        private ModelProbeRequest captured;
        private ModelProbeResponse response;
        private RuntimeException failure;

        @Override
        public ModelProbeResponse probe(ModelProbeRequest request) {
            captured = request;
            if (failure != null) throw failure;
            return response;
        }
    }
}
