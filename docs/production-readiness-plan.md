# LLM Council Production Readiness Plan

This document captures the recommended next steps after the local Docker,
Rancher Desktop, and Ollama integration fixes. The application is now much more
usable for local council runs, but production readiness still needs focused work
around provider contracts, failure handling, health checks, observability,
runtime safety, and API ergonomics.

## Current State

- Java 25 Spring Boot API with configurable profiles, policies, and protocols.
- Local Ollama, OCI/OpenAI-compatible, hybrid, and explicit test-only mock
  profiles.
- Direct Ollama `/api/chat` adapter for local models.
- Rancher Desktop app-only compose path using native Ollama through
  `host.rancher-desktop.internal`.
- Docker Desktop can still be used by overriding
  `SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434`.
- In-memory session and event handling.
- Local artifact writing for run outputs and debug material.
- Basic unit coverage for policy resolution, structured parsing, quorum, and
  Ollama client request/response behavior.

## 1. Provider Abstraction Cleanup

Move provider construction out of `CouncilConfig` into a dedicated
`ModelClientFactory`.

Why:

- `CouncilConfig` currently owns too much provider-specific wiring.
- Provider behavior will diverge as retries, health checks, metrics, auth, and
  streaming evolve.
- A factory makes it easier to add real OCI/OpenAI-compatible clients without
  weakening local Ollama behavior.

Recommended shape:

```java
public interface ModelClientFactory {
    ModelClient create(ModelProfile profile);
}
```

Provider-specific factories can then own adapter details:

```java
public final class OllamaModelClientFactory implements ModelClientFactory {
    @Override
    public ModelClient create(ModelProfile profile) {
        return new OllamaDirectModelClient(...);
    }
}
```

## 2. Resilience And Failure Semantics

Add explicit resilience policies per provider and per stage.

Recommended behavior:

- Bounded retry for transient failures such as connection reset, timeout, and
  temporary 5xx responses.
- No retry for deterministic failures such as unknown model, invalid API key, or
  malformed request.
- Separate connect timeout, read timeout, and model execution timeout.
- Return clearer failure categories in API responses:
  - `PROVIDER_UNAVAILABLE`
  - `MODEL_NOT_FOUND`
  - `MODEL_TIMEOUT`
  - `QUORUM_NOT_MET`
  - `INVALID_MODEL_OUTPUT`
  - `VALIDATION_FAILED`

Why:

- Today, infrastructure failures still bubble up into quorum failures at the
  protocol level.
- That is technically true but operationally misleading.
- Operators need to know whether to fix configuration, restart Ollama, lower the
  depth mode, or inspect model output quality.

## 3. Provider And Model Health Checks

Add provider health checks before a user runs a session.

For Ollama:

- Check `/api/tags`.
- Confirm configured `providerModelId` exists.
- Return the resolved base URL, reachable status, and known model list.

Potential endpoint:

```http
GET /api/council/profiles/local/health
```

Example response:

```json
{
  "profileId": "local",
  "runnable": true,
  "models": [
    {
      "modelId": "local-llama3",
      "providerModelId": "llama3.1:8b",
      "available": true
    }
  ]
}
```

Why:

- Most local setup failures should be found before a council run starts.
- This avoids confusing `Draft quorum not met` responses for simple
  configuration or networking issues.

## 4. Observability

Add structured operational signals around each council run.

Recommended metrics:

- Stage duration.
- Model call latency.
- Model failure count by provider and model.
- Quorum failures by policy.
- Validation failures by validator model.
- Debate trigger count.
- Estimated token usage where provider usage is unavailable.

Recommended logging:

- Include `sessionId`, `profileId`, `policyId`, `protocolId`, `stage`, and
  logical `modelId` in every orchestration log.
- Keep prompt and response body logging disabled by default.
- Add a safe debug mode that logs prompt sizes, response sizes, provider URL,
  resolved host, status code, and root cause.

Why:

- Debugging multi-agent systems without correlated logs is slow.
- Prompt body logging is useful locally but risky in shared environments.

## 5. Configuration Robustness

Harden startup validation.

Recommended checks:

- A non-test profile must not reference a test-only model.
- A policy must have at least one member model.
- `minimumSuccessfulDrafts` must be positive and cannot exceed member count
  unless partial quorum behavior is deliberately allowed.
- `chairModelId` must reference a model with role `CHAIR` or a compatible role.
- `validatorModelId`, when present, should reference a validator-capable model.
- Profile/depth policy mappings must cover the supported public depth modes.

Why:

- Configuration is now the primary control plane.
- Invalid config should fail at boot instead of during a user request.

## 6. Session Runtime Controls

The in-memory session model is useful for local development, but the runtime
needs lifecycle and concurrency controls.

Recommended controls:

- Maximum concurrent runs.
- Per-profile concurrency limits.
- Session expiration.
- Run cancellation.
- Request body size limits.
- Maximum prompt/context length.
- Async run mode with polling or server-sent events.

Why:

- A rigorous local run can occupy several model calls.
- Without concurrency control, one user can exhaust local model capacity.

## 7. Prompt-Injection And Data Boundaries

Strengthen prompt construction around untrusted user content.

Recommended changes:

- Wrap user content in explicit delimiters.
- Tell models that user content is data, not instructions to alter the council
  process.
- Keep system policy, rubric, and user content visibly separated.
- Avoid exposing hidden model identities or policy internals unless a stage
  requires them.
- Add a validation pass that detects instruction leakage or attempts to override
  the protocol.

Why:

- Council systems are especially vulnerable because one model output can become
  another model's input.
- The protocol should remain controlled by the application, not by user content
  or member-model responses.

## 8. Council Quality Improvements

Improve answer quality and confidence reporting.

Recommended enhancements:

- Calibrated rubric scoring instead of relying heavily on model-stated
  confidence.
- Explicit dissent extraction.
- Convergence summary after review/debate.
- "Insufficient evidence" or "needs clarification" outcome where appropriate.
- Judge prompt variants by domain, such as architecture review, code review,
  incident analysis, design decision, and risk assessment.

Why:

- A council should not always force a confident answer.
- The useful output is often the tradeoff analysis, dissent, and uncertainty,
  not only the final recommendation.

## 9. Testing Strategy

Add tests around the failure modes already encountered.

Recommended tests:

- Ollama unavailable.
- Ollama model missing.
- Provider URL unreachable.
- Provider URL resolves but refuses connection.
- Streaming Ollama response aggregation.
- Malformed streaming JSON chunk.
- Partial quorum success and failure.
- Balanced review returns invalid JSON.
- Validation model returns a rejection.
- Real profile references test-only model.
- Rancher Desktop and Docker Desktop compose configuration validation.

Why:

- Local LLM development fails in predictable ways.
- Tests should lock down the diagnostics so future changes do not regress into
  ambiguous `Draft quorum not met` responses.

## 10. API Ergonomics

Introduce chat without replacing council runs.

Recommended split:

- Chat API for iterative user interaction.
- Council API for deeper deliberation.
- A chat conversation can trigger a council run using recent turns as context.
- Council results can be appended back into the chat timeline.

Why:

- One-shot council execution is useful for analysis.
- Users often need clarification, follow-up questions, and incremental context.

## Suggested Next Implementation Order

1. Provider/model health endpoint.
2. Better failure categories in run responses.
3. Config validation hardening.
4. Provider retry and timeout policies.
5. Metrics and correlated structured logs.
6. Async run plus event streaming.
7. Chat API backed by the same council orchestration.
8. Prompt-injection hardening and rubric calibration.

The most practical next step is the provider/model health endpoint. It directly
addresses the recent local setup pain and gives users a fast preflight check
before running expensive or slow council protocols.
