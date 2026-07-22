# LLM Council

LLM Council is a Java 25 / Spring Boot library and service for running a configurable council of language models. A caller submits a question, chooses a profile such as `local`, `oci`, `hybrid`, `gemini`, or `multi-cloud`, and chooses a depth mode such as `QUICK`, `BALANCED`, or `RIGOROUS`. The application resolves that request to an internal policy and protocol, collects independent model drafts, reviews and scores them, optionally debates disagreements, synthesizes a final answer, and validates it with a Fresh Eyes model.

The public API does not accept raw protocol IDs. Protocols are owned by application configuration so users cannot bypass validation, quorum, or cost controls.

## What This Implements

### Core Council Engine
- Profile plus depth policy resolution.
- Separate local, OCI/OpenAI-compatible, hybrid, Gemini, multi-cloud, and explicit mock profiles.
- Config-owned protocols: `quick`, `balanced`, and `rigorous`.
- Quorum enforcement before synthesis.
- Explicit unavailable-provider failures instead of silent mock fallback.
- Provider/model health preflight for profile-depth combinations.
- Structured model failure categories in run responses.
- Chat API V1 with asynchronous council runs and server-sent progress events.
- Anonymized draft IDs with private model mapping artifacts.
- Structured JSON review parsing and per-draft scoring.
- Debate trigger based on score variance.
- Chair synthesis with score and dissent context.
- Fresh Eyes validation with structured JSON output.
- In-memory session and event history.
- Local artifact storage for raw, normalized, final, and export metadata.

### Anti-Sycophancy & Quality (Phase 3)
- **Adversarial debate roles**: `PROPOSER`, `CRITIC`, and `SYNTHESIZER` council personas with role-specific system prompts. CRITIC models receive explicit instructions to challenge emerging consensus.
- **Sycophancy detection**: per-round Jaccard word similarity plus confidence delta toward majority median. Models that shift opinion without changing their argument are flagged.
- **Post-debate draft revision** (`REVISE` stage): each model revises its draft incorporating debate arguments before re-scoring.
- **Post-debate re-review** (`REVIEW_POST_DEBATE` stage): reviewers re-evaluate drafts considering debate transcript, so the second SCORE pass uses genuinely updated evidence.
- **Model heterogeneity enforcement**: startup warning when all council members share the same `modelFamily`.

### Scoring & Resilience (Phases 1–2)
- **Confidence-weighted scoring** (default): reviewer scores weighted by self-reported confidence.
- **Pluggable scoring strategies**: `average`, `confidence-weighted`, `median`, `trimmed-mean` — selectable per protocol stage.
- **Disagreement escalation**: `SYNTHESIZE_WITH_DISSENT` or `HALT_AND_ESCALATE` when post-debate score variance remains high.
- **Retry with exponential backoff**: `RetryableModelClient` decorator retries transient failures (`PROVIDER_UNAVAILABLE`, `MODEL_TIMEOUT`) with jitter.
- **Robust confidence parsing**: multi-pattern extraction handles `Confidence: 85`, `confidence: 0.85`, `85%`, and more.
- **JSON parsing resilience**: markdown fence stripping, trailing comma tolerance, lenient Jackson configuration.
- **Token usage tracking**: Ollama (`prompt_eval_count`/`eval_count`) and Spring AI (`getUsage()`) token extraction.
- **Minimum debate rounds**: prevents premature convergence from sycophantic first-round agreement.
- **Immutable ModelRegistry**: constructor-injected via `@Bean` — no mutable post-construction registration.

### Multi-Provider Support
- **Credential auto-detection**: providers activate automatically when real API keys are set. Placeholder keys (e.g. `unused-development-placeholder`) are detected and ignored — no explicit "enabled" flags needed.
- **Google Gemini / Vertex AI**: `spring-ai-starter-model-vertex-ai-gemini` with conditional activation. Supports both Application Default Credentials (ADC) and service account JSON.
- **Pre-built profiles**: `gemini` (Gemini-only), `multi-cloud` (Ollama + Gemini + Anthropic/OpenAI) with full QUICK/BALANCED/RIGOROUS policy sets.
- **Startup provider banner**: logs which providers were auto-detected at boot.
- **Graceful degradation**: models on disabled providers fall through to `UnavailableModelClient` with actionable error messages.

### Testing
- 70 JUnit tests: policy resolution, parsing, quorum, KS convergence math, sycophancy detection, all scoring strategies, retry logic, and full protocol integration.

## Runtime Requirements

- Java 25.
- Maven 3.9+.
- Optional local model runtime: Ollama, either as the macOS app/background service or via `ollama serve`.
- Optional OCI/OpenAI-compatible runtime: Oracle Code Assist LiteLLM, OCI OpenAI-compatible endpoint, or another Spring AI OpenAI-compatible endpoint.
- Optional cloud providers (auto-detected via API keys):
  - **OpenAI**: set `SPRING_AI_OPENAI_API_KEY=sk-...`
  - **Anthropic**: set `SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...`
  - **Gemini / Vertex AI**: set `GOOGLE_CLOUD_PROJECT=my-project` and authenticate via `gcloud auth application-default login` or `GOOGLE_APPLICATION_CREDENTIALS`

The project intentionally keeps Java 25 in `pom.xml`:

```xml
<java.version>25</java.version>
<maven.compiler.release>25</maven.compiler.release>
```

If your shell default Java is not 25, run Maven with an explicit JDK:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin:$PATH \
mvn test
```

## Codex Authentication Note

Codex development authentication is separate from LLM Council runtime authentication.

The current local Codex configuration uses ChatGPT auth in `~/.codex/auth.json`:

```json
{
  "auth_mode": "chatgpt",
  "OPENAI_API_KEY": null,
  "tokens": {
    "id_token": "<redacted>",
    "access_token": "<redacted>",
    "refresh_token": "<redacted>",
    "account_id": "<redacted>"
  }
}
```

Do not read or reuse these Codex tokens from the Java service. They authenticate the Codex development tool, not this application backend.

Configure runtime model providers with their own environment variables. Local Ollama calls use direct `/api/chat` HTTP requests against `spring.ai.ollama.base-url`. Oracle Code Assist or OCI/OpenAI-compatible endpoints use Spring AI's OpenAI-compatible client settings.

`application.yml` supplies harmless placeholder keys for eager Spring AI auto-configuration so local/mock profiles can boot without OpenAI or Anthropic credentials. Those placeholders are not valid for runtime model calls. Real `oci` or `hybrid` runs must override them with valid endpoint credentials.

## Profiles And Depth Modes

Public callers choose only:

- `profileId`: `local`, `oci`, `hybrid`, or `mock`.
- `depthMode`: `QUICK`, `BALANCED`, or `RIGOROUS`.

Configuration maps that pair to a `CouncilPolicy`.

| Profile | Purpose |
|---|---|
| `local` | Ollama-only local council. Useful for private or offline-capable runs. |
| `oci` | OCI/OpenAI-compatible council. Useful for Oracle Code Assist LiteLLM, OCI, or another OpenAI-compatible provider. |
| `hybrid` | Local models for draft diversity plus OCI/OpenAI-compatible chair and validator. |
| `mock` | Test-only deterministic profile. Use for smoke tests, not real answers. |

| Depth | Protocol | Typical behavior |
|---|---|---|
| `QUICK` | `quick` | Generate and synthesize only. No review or validation. |
| `BALANCED` | `balanced` | Generate, anonymize, review, score, synthesize, validate. |
| `RIGOROUS` | `rigorous` | Balanced flow plus debate, draft revision, post-debate re-review, second score, validation, and export manifest. |

### Rigorous Protocol Pipeline

```text
GENERATE → ANONYMIZE → REVIEW → SCORE → DEBATE → REVISE → REVIEW_POST_DEBATE → SCORE → SYNTHESIZE → VALIDATE → EXPORT
```

The `REVISE` stage lets each model incorporate debate arguments into a revised draft. The `REVIEW_POST_DEBATE` stage asks reviewers to re-evaluate with debate context, so the second `SCORE` pass operates on genuinely updated evidence.

## Architecture

### Council Roles

Each council member model is assigned a `CouncilRole` (separate from structural `ModelRole`):

| Role | Behavior |
|---|---|
| `PROPOSER` | Default. Produces an independent answer with chain-of-thought reasoning. |
| `CRITIC` | Devil's advocate. System prompt explicitly requires challenging the consensus. |
| `SYNTHESIZER` | Seeks common ground across perspectives. |

Configure via `application.yml`:

```yaml
council:
  models:
    local-llama3:
      councilRole: PROPOSER
      modelFamily: llama
    local-mistral:
      councilRole: CRITIC
      modelFamily: mistral
```

### Scoring Strategies

The `SCORE` stage supports pluggable aggregation via the `scoring-strategy` stage option:

| Strategy | Description |
|---|---|
| `confidence-weighted` | Default. Weights reviews by reviewer confidence. |
| `average` | Simple arithmetic mean. Vulnerable to outlier manipulation. |
| `median` | Robust to outliers but loses score nuance. |
| `trimmed-mean` | Drops highest and lowest review, then averages. |

### Sycophancy Detection

After each debate round (from round 1 onward), the `SycophancyDetector` computes:

```text
sycophancyIndex = textSimilarity × (confidenceDelta / 100)
```

- **textSimilarity**: Jaccard word overlap between a model's consecutive debate contributions.
- **confidenceDelta**: How much confidence shifted toward the group majority median.
- A high index means the model changed its stated confidence toward the majority without meaningfully changing its argument — a sycophancy signal.

Flagged models are recorded in `CouncilContext.sycophancyWarnings()` and emitted as `DEBATE_SYCOPHANCY_WARNING` events.

### Retry Logic

`RetryableModelClient` wraps each provider client with exponential backoff:

```text
delay = baseDelay × 2^attempt + random(0–500ms)
```

Only transient failures retry: `PROVIDER_UNAVAILABLE`, `MODEL_TIMEOUT`. Non-retryable categories (`MODEL_NOT_FOUND`, `CONFIGURATION_ERROR`) are propagated immediately.

## Build And Test

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin:$PATH \
mvn test
```

Package:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin:$PATH \
mvn clean package
```

## Run

```bash
java -jar target/llm-council-2.0.0.jar
```

The default profile is `local`, so make sure Ollama is running and pull the configured models first.

If Ollama is not already running through the macOS app or another service manager, start it in a separate terminal:

```bash
ollama serve
```

Then pull the local models:

```bash
ollama pull llama3.1:8b
ollama pull mistral:7b
```

### Context window and memory

The council's chair must hold every draft, review, and debate turn its members
produce. A rigorous local council generates roughly 11,000 tokens of evidence,
so `SPRING_AI_OLLAMA_NUM_CTX` defaults to **16384**. Anything smaller is not an
error — prompts are fitted to the window, truncation is marked in the prompt,
and both the boot log and the run's `warnings` say what was dropped — but the
chair then synthesises from part of the council's work.

A larger window costs KV cache, roughly 2 GiB per resident 8B-class model at
16384 (about 0.5 GiB at 4096). With `keep_alive` holding two models resident
that is ~4 GiB on top of the weights, which is comfortable on 32 GB. On a
smaller machine, lower `SPRING_AI_OLLAMA_NUM_CTX` and either use fewer council
members or reduce `LLM_COUNCIL_LOCAL_OUTPUT_TOKENS` so the evidence still fits.
The startup log states the numbers for every policy that does not.

For mock smoke testing:

```bash
java -jar target/llm-council-2.0.0.jar
```

Then create a session with `profileId: "mock"`.

## Docker Compose Local Testing

The repository includes Docker Compose files for local Mac testing:

| File | Target machine | Default local models |
|---|---|---|
| `docker-compose.m1-32gb.yml` | Apple Silicon M1 class Mac with 32 GB memory | `llama3.1:8b`, `mistral:7b` |
| `docker-compose.m1-32gb-app-only.yml` | Apple Silicon M1 app container plus native/separate Ollama | `llama3.1:8b`, `mistral:7b` |
| `docker-compose.intel-2019-32gb.yml` | 2019 Intel MacBook Pro with 32 GB memory | `llama3.2:3b`, `qwen2.5:3b` |

Validate and start on M1:

```bash
docker compose -f docker-compose.m1-32gb.yml config >/tmp/llm-council-m1-compose.yml
docker compose -f docker-compose.m1-32gb.yml up --build
```

Recommended M1 path when Ollama runs natively or separately:

```bash
ollama pull llama3.1:8b
ollama pull mistral:7b
unset SPRING_AI_OLLAMA_BASE_URL
docker compose -f docker-compose.m1-32gb-app-only.yml up --build
```

Validate and start on Intel:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml config >/tmp/llm-council-intel-compose.yml
docker compose -f docker-compose.intel-2019-32gb.yml up --build
```

The full-stack compose files run Ollama and the Java service. Inside full-stack
compose, the app uses:

```text
SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
LLM_COUNCIL_ARTIFACT_PATH=/data/llm-council/runs
```

The M1 app-only compose file uses:

```text
SPRING_AI_OLLAMA_BASE_URL=http://host.rancher-desktop.internal:11434
```

This default targets Rancher Desktop/Lima. Docker Desktop users can override
with `SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434`.

Example Docker Desktop override:

```bash
SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434 \
docker compose -f docker-compose.m1-32gb-app-only.yml up --build
```

Detailed testing guides:

- [Testing on M1 Mac with 32 GB memory](docs/testing-m1-32gb.md)
- [Testing on 2019 Intel MacBook Pro with 32 GB memory](docs/testing-intel-2019-32gb.md)

## API Usage

Create a balanced mock session:

```bash
curl -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is the best approach to distributed transactions?",
    "depthMode": "BALANCED",
    "profileId": "mock"
  }'
```

Run the session:

```bash
curl -X POST http://localhost:8080/api/council/sessions/{sessionId}/run
```

Preflight a profile before running it:

```bash
curl "http://localhost:8080/api/council/profiles/local/health?depthMode=QUICK"
```

For Ollama-backed profiles, this checks `/api/tags` and verifies the configured
`providerModelId` is actually available before a council run starts. Use this
first when a run would otherwise fail with quorum errors.

Example health response:

```json
{
  "profileId": "local",
  "depthMode": "QUICK",
  "policyId": "local-quick",
  "protocolId": "quick",
  "runnable": true,
  "models": [
    {
      "modelId": "local-llama3",
      "provider": "ollama",
      "providerModelId": "llama3.1:8b",
      "available": true,
      "status": "AVAILABLE",
      "detail": null,
      "knownProviderModels": ["llama3.1:8b", "mistral:7b"]
    }
  ],
  "warnings": []
}
```

Read session state:

```bash
curl http://localhost:8080/api/council/sessions/{sessionId}
```

Read replayable events:

```bash
curl http://localhost:8080/api/council/sessions/{sessionId}/events
```

List artifacts:

```bash
curl http://localhost:8080/api/council/sessions/{sessionId}/artifacts
```

Run responses include both the legacy `excludedModels` strings and structured
fields for automation:

```json
{
  "failureCategory": "PROVIDER_UNAVAILABLE",
  "modelFailures": [
    {
      "modelId": "local-llama3",
      "provider": "ollama",
      "providerModelId": "llama3.1:8b",
      "category": "PROVIDER_UNAVAILABLE",
      "message": "Ollama provider is unreachable"
    }
  ]
}
```

Common `failureCategory` values are:

| Category | Meaning |
|---|---|
| `PROVIDER_UNAVAILABLE` | The provider endpoint could not be reached or returned a provider-level failure. |
| `MODEL_NOT_FOUND` | The configured `providerModelId` is not available from the provider. |
| `MODEL_TIMEOUT` | The model/provider call timed out. |
| `MODEL_CALL_FAILED` | The provider call failed, but not in a more specific classified way. |
| `CONFIGURATION_ERROR` | The profile or model is configured in a way that cannot run. |
| `INVALID_MODEL_OUTPUT` | A model response could not be parsed or normalized as expected. |
| `VALIDATION_FAILED` | The final validation stage rejected the answer. |
| `QUORUM_NOT_MET` | Too few model calls succeeded for the selected policy. |

## Chat API V1

Chat API V1 is a usability layer over the existing council engine. Each chat
message creates one linked council session, runs it asynchronously, and attaches
the final answer back to the chat turn.

Create a chat:

```bash
curl -s -X POST http://localhost:8080/api/council/chats \
  -H "Content-Type: application/json" \
  -d '{"profileId":"local","depthMode":"QUICK","initialContext":"Demo discussion"}'
```

Send a message. This returns immediately with the turn in `RUNNING` state while
the council run continues in the background:

```bash
curl -s -X POST "http://localhost:8080/api/council/chats/{chatId}/messages" \
  -H "Content-Type: application/json" \
  -d '{"message":"Compare sagas and two-phase commit for microservices."}'
```

Stream chat and council progress:

```bash
curl -N http://localhost:8080/api/council/chats/{chatId}/events
```

Read the chat after completion:

```bash
curl -s http://localhost:8080/api/council/chats/{chatId}
```

Use separate chats to demonstrate different depth modes:

```bash
curl -s -X POST http://localhost:8080/api/council/chats \
  -H "Content-Type: application/json" \
  -d '{"profileId":"local","depthMode":"BALANCED","initialContext":"Enterprise AI architecture review"}'
```

```bash
curl -s -X POST http://localhost:8080/api/council/chats \
  -H "Content-Type: application/json" \
  -d '{"profileId":"mock","depthMode":"RIGOROUS","initialContext":"Fast rigorous protocol demonstration"}'
```

For live local demos, start with `QUICK`, show `BALANCED` if local model
preflight passes, and use `RIGOROUS` only after practicing latency or with the
`mock` profile to show protocol shape quickly.

Chat V1 is intentionally in-memory. App restart clears chat history, and each
message creates one linked council session. It is demo-grade: durable chat
storage, cancellation, queued run recovery, SSE reconnect cursors, and user
ownership are still production-readiness work.

Artifacts are written under:

```text
${LLM_COUNCIL_ARTIFACT_PATH:-$HOME/.llm-council/runs}/{sessionId}/
```

## Configuring OCI Or Oracle Code Assist Runtime

The `oci` profile uses logical models with provider `openai-compatible`. Configure Spring AI's OpenAI-compatible client externally. The exact environment variables depend on your Spring AI setup and endpoint, but the runtime concept is:

```bash
export SPRING_AI_OPENAI_API_KEY="$OCA_LLM_API_TOKEN"
export SPRING_AI_OPENAI_BASE_URL="$OCA_LLM_BASE_URL"
export OCA_LLM_MODEL="gpt-5.4"
```

Without valid values, the service can still start, but `oci` and `hybrid` model calls will fail explicitly rather than silently falling back to mock output.

Then call with:

```json
{
  "question": "Review this architecture decision...",
  "depthMode": "BALANCED",
  "profileId": "oci"
}
```

## Key Package Layout

```text
com.debopam.llmcouncil.api              REST controller and DTOs
com.debopam.llmcouncil.application      service, policy resolver, event publisher
com.debopam.llmcouncil.chat             chat sessions, turns, async chat service, event broker
com.debopam.llmcouncil.config           configuration binding, validation, and registry setup
com.debopam.llmcouncil.domain           session, status, depth, event records
com.debopam.llmcouncil.model            model profiles, policies, clients, retry decorator
com.debopam.llmcouncil.orchestration    protocol, stages, prompts, parser, scoring strategies,
                                        sycophancy detection, convergence detector, artifacts
com.debopam.llmcouncil.persistence      in-memory sessions and local artifacts
```

## More Detail

See [docs/library-flow-guide.md](docs/library-flow-guide.md) for a simple but detailed explanation of the business logic, execution sequence, configuration model, and extension points.

See [docs/enhancement-implementation-sequences.md](docs/enhancement-implementation-sequences.md) for concrete code examples for planned enhancements. Some early examples, such as chat and event streaming, now exist as demo-grade V1 features.

See [docs/production-readiness-plan.md](docs/production-readiness-plan.md) for the prioritized robustness and production-readiness plan.

See [docs/production-readiness-implementation-guide.md](docs/production-readiness-implementation-guide.md) for detailed implementation notes, full code examples, and testing guidance for those recommendations.

See [docs/demo-chat-api-v1-guide.md](docs/demo-chat-api-v1-guide.md) for a step-by-step Rancher Desktop demo runbook.
