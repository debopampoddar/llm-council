package com.debopam.llmcouncil.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web UI is served from the static classpath root with no Java behind it.
 *
 * <p>These tests exist because that arrangement is easy to break silently: a
 * stray {@code WebMvcConfigurer}, a controller mapped at {@code /}, or a
 * renamed module would leave the API working perfectly and the UI blank.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaticResourceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theRootForwardsToTheChatView() throws Exception {
        // Boot's welcome-page handler forwards rather than rendering, and
        // MockMvc does not execute forwards — so the contract to assert here is
        // the forward target, with the body checked separately below.
        mockMvc.perform(get("/"))
               .andExpect(status().isOk())
               .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void servesTheChatViewAsHtml() throws Exception {
        mockMvc.perform(get("/index.html"))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith("text/html"))
               .andExpect(content().string(containsString("<title>LLM Council</title>")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/js/main.js",
            "/js/api.js",
            "/js/sse.js",
            "/js/dom.js",
            "/js/markdown.js",
            "/js/chat.js",
            "/js/health.js",
            "/js/timeline.js",
            "/js/trust.js",
            "/js/artifacts.js",
            "/css/app.css"
    })
    void servesEveryModuleTheChatViewLoads(String path) throws Exception {
        // index.html imports these directly; a 404 on any one of them is a blank
        // page with an error only in the browser console.
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }
}
