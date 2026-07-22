package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsEverySectionWhenIncludeIsOmitted() throws Exception {
        mockMvc.perform(get("/api/council/catalog"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.generation").value(1))
               .andExpect(jsonPath("$.profiles").isArray())
               .andExpect(jsonPath("$.policies").isArray())
               .andExpect(jsonPath("$.models").isArray())
               .andExpect(jsonPath("$.protocols").isArray())
               .andExpect(jsonPath("$.providers").isArray())
               .andExpect(jsonPath("$.issues").isArray());
    }

    @Test
    void omitsSectionsThatWereNotRequested() throws Exception {
        // Absent rather than empty: a client must be able to tell "I did not ask
        // for this" apart from "I asked and there is nothing".
        mockMvc.perform(get("/api/council/catalog").param("include", "profiles"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.profiles").isArray())
               .andExpect(jsonPath("$.models").doesNotExist())
               .andExpect(jsonPath("$.policies").doesNotExist())
               .andExpect(jsonPath("$.providers").doesNotExist());
    }

    @Test
    void acceptsMultipleSections() throws Exception {
        mockMvc.perform(get("/api/council/catalog").param("include", "profiles,models"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.profiles").isArray())
               .andExpect(jsonPath("$.models").isArray())
               .andExpect(jsonPath("$.policies").doesNotExist());
    }

    @Test
    void rejectsUnknownSectionNames() throws Exception {
        mockMvc.perform(get("/api/council/catalog").param("include", "profiles,wat"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void hidesTestOnlyProfilesByDefault() throws Exception {
        mockMvc.perform(get("/api/council/catalog").param("include", "profiles"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.profiles[?(@.id == 'mock')]").isEmpty())
               .andExpect(jsonPath("$.profiles[?(@.id == 'local')]").isNotEmpty());

        mockMvc.perform(get("/api/council/catalog")
                                .param("include", "profiles")
                                .param("includeTestOnly", "true"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.profiles[?(@.id == 'mock')]").isNotEmpty());
    }

    @Test
    void exposesValidationIndependenceOnEveryPolicy() throws Exception {
        // Which tier each shipped policy has is pinned by
        // ShippedValidationIndependenceTest. This asserts only that the endpoint
        // carries the field at all, so the two do not have to change together.
        mockMvc.perform(get("/api/council/catalog").param("include", "policies"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.policies[*].validationIndependence").exists())
               .andExpect(jsonPath("$.policies[?(@.id == 'multi-cloud-balanced')].validationIndependence")
                                  .value("INDEPENDENT"));
    }

    @Test
    void neverExposesCredentialMaterial() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/council/catalog").param("includeTestOnly", "true"))
                                  .andExpect(status().isOk())
                                  .andReturn();

        String body = result.getResponse().getContentAsString();

        // The placeholder key is what application.yml ships. If it can reach the
        // response, so could a real key.
        assertFalse(body.contains("unused-development-placeholder"),
                    "catalog response leaked a configured credential value");
        assertFalse(body.toLowerCase().contains("api-key"),
                    "catalog response exposed an api-key field");
        assertFalse(body.toLowerCase().contains("apikey"),
                    "catalog response exposed an apiKey field");

        // Naming the environment variable is the intended affordance: it tells a
        // user what to set without the app ever handling the value.
        assertTrue(body.contains("SPRING_AI_ANTHROPIC_API_KEY")
                   || body.contains("\"active\":true"),
                   "provider section should either name the env var to set or report the provider active");
    }
}
