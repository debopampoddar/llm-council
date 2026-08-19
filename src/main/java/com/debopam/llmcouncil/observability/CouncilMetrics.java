package com.debopam.llmcouncil.observability;

import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.orchestration.StageType;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cardinality-safe operational metrics for council runs and model calls.
 *
 * <p>Only bounded configuration values and enums are used as tags. Session
 * identifiers, questions, responses, and exception messages are deliberately
 * excluded so the metrics backend cannot grow one time series per request.
 */
@Component
public class CouncilMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeRuns = new AtomicInteger();

    public CouncilMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("llm.council.runs.active", activeRuns, AtomicInteger::get)
                .description("Council runs currently executing in this process")
                .register(registry);
    }

    private CouncilMetrics() {
        this.registry = null;
    }

    /** A no-op instance for focused unit tests that construct services directly. */
    public static CouncilMetrics noop() {
        return new CouncilMetrics();
    }

    public void modelSucceeded(ModelCallRequest request, String provider,
                               Duration elapsed, ModelCallResult result) {
        Tags tags = modelTags(request, provider, "success", "none");
        recordModelAttempt(tags, elapsed);
        recordTokens(request, provider, result);
    }

    public void modelFailed(ModelCallRequest request, String provider,
                            Duration elapsed, ModelFailureCategory category) {
        String failure = category == null ? ModelFailureCategory.UNKNOWN.name() : category.name();
        recordModelAttempt(modelTags(request, provider, "failure", failure), elapsed);
    }

    public void retry(ModelCallRequest request, ModelFailureCategory category) {
        if (registry == null) {
            return;
        }
        registry.counter("llm.council.model.retries",
                "model", safe(request.modelId()),
                "stage", stage(request.stage()),
                "failure", category == null ? ModelFailureCategory.UNKNOWN.name() : category.name())
                .increment();
    }

    public void stageCompleted(StageType stage, Duration elapsed, String outcome) {
        if (registry == null) {
            return;
        }
        Timer.builder("llm.council.stage.duration")
                .description("Council protocol stage execution time")
                .tags("stage", stage(stage), "outcome", safe(outcome))
                .register(registry)
                .record(elapsed);
    }

    public void runAccepted() {
        activeRuns.incrementAndGet();
    }

    public void runFinished() {
        activeRuns.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void runRejected(String reason) {
        if (registry != null) {
            registry.counter("llm.council.runs.rejected", "reason", safe(reason)).increment();
        }
    }

    private void recordModelAttempt(Tags tags, Duration elapsed) {
        if (registry == null) {
            return;
        }
        registry.counter("llm.council.model.calls", tags).increment();
        Timer.builder("llm.council.model.duration")
                .description("Individual provider attempt latency")
                .tags(tags)
                .register(registry)
                .record(elapsed);
    }

    private void recordTokens(ModelCallRequest request, String provider, ModelCallResult result) {
        if (registry == null || result == null) {
            return;
        }
        recordTokenValue(request, provider, "input", result.promptTokens());
        recordTokenValue(request, provider, "output", result.completionTokens());
    }

    private void recordTokenValue(ModelCallRequest request, String provider,
                                  String direction, Long value) {
        if (value == null || value < 0) {
            return;
        }
        DistributionSummary.builder("llm.council.model.tokens")
                .description("Reported model tokens per provider attempt")
                .baseUnit("tokens")
                .tags("model", safe(request.modelId()),
                      "provider", safe(provider),
                      "stage", stage(request.stage()),
                      "direction", direction)
                .register(registry)
                .record(value);
    }

    private static Tags modelTags(ModelCallRequest request, String provider,
                                  String outcome, String failure) {
        return Tags.of("model", safe(request.modelId()),
                       "provider", safe(provider),
                       "stage", stage(request.stage()),
                       "outcome", outcome,
                       "failure", failure);
    }

    private static String stage(StageType value) {
        return value == null ? "UNKNOWN" : value.name();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
