package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the three preflight states the UI's health badge is built on.
 *
 * <p>The obvious reading of {@code ProfileHealthResponse} is a binary — runnable
 * or not. It is not: a profile can report {@code runnable: true} while every one
 * of its models is {@code NOT_CHECKED}, because provider health is deferred to
 * runtime credentials. The UI paints that amber rather than green, since green
 * would promise a preflight that never happened on the profiles that cost real
 * money when they fail.
 *
 * <p>These tests exist so that distinction cannot be erased by accident: if
 * warnings stopped being populated, the amber tier would silently become green
 * and nothing else would fail.
 */
@SpringBootTest
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
    void deferredProfileIsRunnableButCarriesWarnings() throws Exception {
        // The third state. Nothing was checked, so "runnable" here means
        // "nothing is known to be wrong", not "verified".
        mockMvc.perform(get("/api/council/profiles/oci/health?depthMode=BALANCED"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.runnable").value(true))
               .andExpect(jsonPath("$.warnings.length()").value(greaterThan(0)))
               .andExpect(jsonPath("$.models[0].status").value("NOT_CHECKED"));
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
