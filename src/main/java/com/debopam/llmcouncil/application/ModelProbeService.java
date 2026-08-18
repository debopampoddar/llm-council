package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.ModelProbeRequest;
import com.debopam.llmcouncil.api.dto.ModelProbeResponse;
import com.debopam.llmcouncil.config.user.ConfigLimits;
import com.debopam.llmcouncil.model.ChatMessage;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.model.ProbeModelClientFactory;
import com.debopam.llmcouncil.orchestration.StageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Executes a deliberately small, acknowledged, globally throttled live model check. */
@Service
public class ModelProbeService implements ModelProbeOperations {

    private static final Logger log = LoggerFactory.getLogger(ModelProbeService.class);
    private static final Pattern MODEL_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/+\\-]{0,159}$");
    private static final int OUTPUT_TOKENS = 8;
    private static final List<ChatMessage> PROMPT = List.of(
            ChatMessage.system("This is a connectivity check. Reply with exactly OK."),
            ChatMessage.user("Reply OK."));

    private final ProbeModelClientFactory clientFactory;
    private final Duration cooldown;
    private final Duration timeout;
    private final LongSupplier nanoTime;
    private boolean slotReserved;
    private long nextAllowedNanos;

    @Autowired
    public ModelProbeService(ProbeModelClientFactory clientFactory,
                             @Value("${council.model-probe.cooldown-seconds:10}") long cooldownSeconds,
                             @Value("${council.model-probe.timeout-seconds:20}") long timeoutSeconds) {
        this(clientFactory, Duration.ofSeconds(Math.max(1, cooldownSeconds)),
             Duration.ofSeconds(Math.max(1, timeoutSeconds)), System::nanoTime);
    }

    ModelProbeService(ProbeModelClientFactory clientFactory,
                      Duration cooldown,
                      Duration timeout,
                      LongSupplier nanoTime) {
        this.clientFactory = clientFactory;
        this.cooldown = cooldown;
        this.timeout = timeout;
        this.nanoTime = nanoTime;
    }

    /**
     * Run one probe. Provider failures are results, while malformed,
     * unacknowledged, and throttled requests are refusals.
     */
    @Override
    public ModelProbeResponse probe(ModelProbeRequest request) {
        ValidatedProbe validated = validate(request);
        reserveSlot();

        long started = nanoTime.getAsLong();
        try {
            ModelCallResult result = clientFactory.create(validated.provider(), validated.modelId())
                    .call(new ModelCallRequest(
                            "configuration-probe", StageType.GENERATE, "configuration-probe",
                            validated.modelId(), PROMPT, OUTPUT_TOKENS, 0.0, false, timeout));
            long latencyMs = result.latency() == null
                    ? elapsedMillis(started)
                    : Math.max(0L, result.latency().toMillis());
            return new ModelProbeResponse(validated.provider(), validated.modelId(), true,
                                          "OK", "Provider completed the bounded probe call.",
                                          latencyMs, result.promptTokens(), result.completionTokens());
        } catch (ModelCallException ex) {
            log.warn("Configuration model probe failed provider={} model={} category={}",
                     validated.provider(), validated.modelId(), ex.category());
            log.debug("Probe provider failure details", ex);
            return new ModelProbeResponse(validated.provider(), validated.modelId(), false,
                                          ex.category().name(), safeDetail(ex.category(), validated.provider()),
                                          elapsedMillis(started), null, null);
        } catch (RuntimeException ex) {
            log.warn("Configuration model probe failed unexpectedly provider={} model={}",
                     validated.provider(), validated.modelId(), ex);
            return new ModelProbeResponse(validated.provider(), validated.modelId(), false,
                                          ModelFailureCategory.UNKNOWN.name(),
                                          "The provider call failed unexpectedly. Check server logs for the cause.",
                                          elapsedMillis(started), null, null);
        }
    }

    private ValidatedProbe validate(ModelProbeRequest request) {
        if (request == null) {
            throw new ModelProbeRequestException("A JSON request body is required.",
                                                 "Choose a provider and enter its exact model id.");
        }
        String provider = request.provider() == null ? "" : request.provider().trim().toLowerCase(Locale.ROOT);
        String modelId = request.providerModelId() == null ? "" : request.providerModelId().trim();
        if (!ConfigLimits.ALLOWED_PROVIDERS.contains(provider)) {
            throw new ModelProbeRequestException("Provider is not supported for user-defined models.",
                    "Choose one of: " + String.join(", ", ConfigLimits.sortedProviders()) + ".");
        }
        if (!MODEL_ID.matcher(modelId).matches()) {
            throw new ModelProbeRequestException("Provider model id is missing or contains unsupported characters.",
                    "Use 1-160 letters, numbers, dots, underscores, colons, slashes, plus signs, or hyphens.");
        }
        if (!"ollama".equals(provider) && !request.cloudCallAcknowledged()) {
            throw new ModelProbeRequestException("A cloud probe requires explicit acknowledgement.",
                    "Confirm that this makes one potentially billable request to " + provider + ".");
        }
        return new ValidatedProbe(provider, modelId);
    }

    private synchronized void reserveSlot() {
        long now = nanoTime.getAsLong();
        if (slotReserved && now < nextAllowedNanos) {
            long remainingNanos = nextAllowedNanos - now;
            long seconds = Math.max(1L, (remainingNanos + 999_999_999L) / 1_000_000_000L);
            throw new ModelProbeThrottledException(seconds);
        }
        slotReserved = true;
        nextAllowedNanos = saturatingAdd(now, cooldown.toNanos());
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private long elapsedMillis(long started) {
        return Math.max(0L, (nanoTime.getAsLong() - started) / 1_000_000L);
    }

    private String safeDetail(ModelFailureCategory category, String provider) {
        return switch (category) {
            case CONFIGURATION_ERROR -> credentialGuidance(provider);
            case MODEL_NOT_FOUND -> "The provider is reachable but does not expose that model id.";
            case MODEL_TIMEOUT -> "The provider did not complete the probe before the fixed timeout.";
            case PROVIDER_UNAVAILABLE -> "The provider runtime could not be reached.";
            case MODEL_CALL_FAILED -> "The provider rejected or failed the bounded model call.";
            default -> "The model call did not complete successfully. Check server logs for details.";
        };
    }

    private String credentialGuidance(String provider) {
        return switch (provider) {
            case "openai" -> "OpenAI is not configured. Set SPRING_AI_OPENAI_API_KEY and restart.";
            case "anthropic" -> "Anthropic is not configured. Set SPRING_AI_ANTHROPIC_API_KEY and restart.";
            case "gemini" -> "Gemini is not configured. Set GOOGLE_CLOUD_PROJECT, configure ADC, and restart.";
            default -> "The local provider is not configured correctly. Check the server logs.";
        };
    }

    private record ValidatedProbe(String provider, String modelId) {}
}
