package com.debopam.llmcouncil.observability;

import com.debopam.llmcouncil.model.ChatMessage;
import com.debopam.llmcouncil.model.MeteredModelClient;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.orchestration.StageType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CouncilMetricsTest {

    @Test
    void recordsSuccessfulPhysicalCallLatencyAndTokensWithoutRequestIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CouncilMetrics metrics = new CouncilMetrics(registry);
        MeteredModelClient client = new MeteredModelClient(
                ignored -> new ModelCallResult("ok", 17L, 5L, Duration.ofMillis(2)),
                metrics, "ollama");

        client.call(request("sensitive-session"));

        assertEquals(1.0, registry.get("llm.council.model.calls")
                .tag("model", "local-a").tag("provider", "ollama")
                .tag("stage", "GENERATE").tag("outcome", "success")
                .tag("failure", "none").counter().count());
        assertEquals(17.0, registry.get("llm.council.model.tokens")
                .tag("direction", "input").summary().totalAmount());
        assertEquals(5.0, registry.get("llm.council.model.tokens")
                .tag("direction", "output").summary().totalAmount());
        assertNotNull(registry.get("llm.council.model.duration").timer());
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                org.junit.jupiter.api.Assertions.assertNotEquals("sensitive-session", tag.getValue())));
    }

    @Test
    void categorizesFailedCallsAndTracksRetryStageAndAdmissionSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CouncilMetrics metrics = new CouncilMetrics(registry);
        MeteredModelClient client = new MeteredModelClient(ignored -> {
            throw new ModelCallException(ModelFailureCategory.MODEL_TIMEOUT,
                    "ollama", "qwen2.5:7b", "timed out");
        }, metrics, "ollama");

        assertThrows(ModelCallException.class, () -> client.call(request("session")));
        metrics.retry(request("session"), ModelFailureCategory.MODEL_TIMEOUT);
        metrics.stageCompleted(StageType.GENERATE, Duration.ofMillis(10), "partial");
        metrics.runAccepted();
        metrics.runRejected("capacity");

        assertEquals(1.0, registry.get("llm.council.model.calls")
                .tag("outcome", "failure").tag("failure", "MODEL_TIMEOUT")
                .counter().count());
        assertEquals(1.0, registry.get("llm.council.model.retries").counter().count());
        assertEquals(1L, registry.get("llm.council.stage.duration")
                .tag("stage", "GENERATE").tag("outcome", "partial").timer().count());
        assertEquals(1.0, registry.get("llm.council.runs.active").gauge().value());
        assertEquals(1.0, registry.get("llm.council.runs.rejected")
                .tag("reason", "capacity").counter().count());

        metrics.runFinished();
        assertEquals(0.0, registry.get("llm.council.runs.active").gauge().value());
    }

    private static ModelCallRequest request(String sessionId) {
        return new ModelCallRequest(sessionId, StageType.GENERATE, "local-a", "qwen2.5:7b",
                List.of(new ChatMessage("user", "private prompt")), 128, 0.2, false,
                Duration.ofSeconds(5));
    }
}
