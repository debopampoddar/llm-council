# LLM Council Library Flow Guide

This guide explains the LLM Council implementation in simple terms, then maps that explanation to the Java code.

## Simple Mental Model

An LLM Council is a structured way to ask multiple models the same question and use their disagreement as evidence.

Instead of:

```text
question -> one model -> answer
```

LLM Council does:

```text
question
  -> independent model drafts (role-aware: PROPOSER, CRITIC, SYNTHESIZER)
  -> anonymous review
  -> scoring (pluggable: confidence-weighted, median, trimmed-mean, average)
  -> optional debate (with sycophancy detection)
  -> optional draft revision (post-debate)
  -> optional post-debate re-review
  -> chair synthesis
  -> Fresh Eyes validation
  -> answer plus audit trail
```

The goal is not to make models vote blindly. The goal is to make the evidence visible: what each model said, what reviewers found, which draft scored best, what dissent remains, and whether a separate validator approves the final answer.

The service now exposes two user-facing ways to start that same council engine:

```text
one-shot session API
  -> create session
  -> run session
  -> inspect result/events/artifacts

chat API V1
  -> create chat
  -> send message
  -> background council run
  -> stream progress events
  -> answer attached back to chat turn
```

The chat API is a usability layer. It does not replace the council engine. Each
chat turn creates and links to a normal `CouncilSession`.

## Main Business Rules

1. Public users cannot choose arbitrary protocols.
2. Users choose `profileId` and `depthMode`.
3. `CouncilPolicyResolver` maps profile plus depth to a configured `CouncilPolicy`.
4. A run needs enough successful drafts to meet quorum before synthesis.
5. Mock models are explicit test-only models, not silent fallback for missing real providers.
6. Review output is JSON and is treated as untrusted until parsed and validated.
7. Drafts are anonymized before review so reviewers do not see model identity.
8. Fresh Eyes validation sees only the original request and final answer.
9. Events and artifacts are inspectable after the run.
10. Chat messages run asynchronously and keep a `councilSessionId` for traceability.
11. Demo runtime concurrency is bounded by `council.runtime.max-concurrent-runs`.

## Request Flow

The one-shot session flow is still the lowest-level public API.

### 1. Create Session

Endpoint:

```text
POST /api/council/sessions
```

Request:

```json
{
  "question": "What should we do about distributed transactions?",
  "context": "Optional background",
  "depthMode": "BALANCED",
  "profileId": "hybrid"
}
```

Code path:

```text
CouncilController.createSession()
  -> CouncilSession.create()
  -> CouncilService.createSession()
  -> InMemorySessionStore.save()
```

Important class:

```java
public record CreateSessionRequest(
    String question,
    String context,
    DepthMode depthMode,
    String profileId
) {}
```

There is no `protocolId` in the request. Protocol selection is internal.

### 2. Run Session

Endpoint:

```text
POST /api/council/sessions/{sessionId}/run
```

Code path:

```text
CouncilController.runCouncil()
  -> CouncilService.runCouncil()
  -> CouncilPolicyResolver.resolve(profileId, depthMode)
  -> ProtocolOrchestrator.run(session, profile, policy)
  -> StageExecutor.execute(...) for each configured stage
```

The service resolves:

```text
profileId=hybrid + depthMode=BALANCED
  -> policyId=hybrid-balanced
  -> protocolId=balanced
```

The resolved policy is written back to the session so `GET /sessions/{id}` can explain what actually ran.

## Chat API V1 Flow

The chat API wraps the same council engine with a conversation layer.

### 1. Create Chat

Endpoint:

```text
POST /api/council/chats
```

Request:

```json
{
  "profileId": "local",
  "depthMode": "QUICK",
  "initialContext": "Demo: architecture tradeoff discussion"
}
```

Code path:

```text
ChatController.create()
  -> ChatCouncilService.createChat()
  -> ChatSession.create in memory
  -> InMemoryChatSessionStore.save()
  -> ChatEventBroker publishes CHAT_CREATED
```

Important classes:

```text
ChatSession
ChatTurn
ChatTurnStatus
ChatCouncilService
InMemoryChatSessionStore
```

### 2. Send Chat Message

Endpoint:

```text
POST /api/council/chats/{chatId}/messages
```

Request:

```json
{
  "message": "Compare sagas, two-phase commit, and the outbox pattern."
}
```

Code path:

```text
ChatController.ask()
  -> ChatCouncilService.ask()
  -> build bounded context from chat summary and recent completed turns
  -> CouncilSession.create()
  -> CouncilService.createSession()
  -> add RUNNING ChatTurn with councilSessionId
  -> CouncilRunExecutor.submit()
  -> return ChatResponse immediately
```

The council run continues on a virtual thread:

```text
CouncilRunExecutor
  -> CouncilService.runCouncil(sessionId)
  -> ProtocolOrchestrator.run(...)
  -> completion callback updates ChatTurn
```

Turn outcomes:

```text
RUNNING   -> council run is active
COMPLETED -> final answer attached to turn
PARTIAL   -> answer exists but council recorded a failure or warning state
FAILED    -> council run failed without an answer
REJECTED  -> runtime concurrency guard rejected the run
```

### 3. Stream Chat Events

Endpoint:

```text
GET /api/council/chats/{chatId}/events
```

Code path:

```text
ChatController.events()
  -> sends current ChatResponse snapshot
  -> replays ChatEventBroker history
  -> subscribes to future chat events
  -> subscribes to linked council session events
  -> streams everything as server-sent events
```

Event names in the SSE stream:

```text
snapshot -> current chat state
chat     -> chat lifecycle event such as TURN_STARTED or TURN_COMPLETED
council  -> underlying council event such as MODEL_CALL_STARTED
```

This is why the demo can show progress while the message request has already
returned.

## Configuration Model

Configuration has four layers.

### Models

Models are logical names that point to provider details:

```yaml
- id: local-llama3
  provider: ollama
  providerModelId: llama3.1:8b
  role: MEMBER
```

Runtime provider clients are created in `CouncilConfig`.

If a real provider bean is missing, the model gets `UnavailableModelClient`. That client fails explicitly if called. It does not return mock output.

The default `application.yml` also includes placeholder OpenAI/Anthropic keys because Spring AI creates those provider beans eagerly. These placeholders are boot-only placeholders, not usable credentials. Real OCI/OpenAI-compatible profiles require real runtime environment variables.

For local Ollama runs, `application.yml` now exposes the Ollama connection and
runtime options as environment variables:

```yaml
spring:
  ai:
    ollama:
      base-url: ${SPRING_AI_OLLAMA_BASE_URL:http://localhost:11434}
      chat:
        options:
          model: ${LLM_COUNCIL_LOCAL_MODEL:llama3.1:8b}
          num-ctx: ${SPRING_AI_OLLAMA_NUM_CTX:4096}
          num-thread: ${SPRING_AI_OLLAMA_NUM_THREAD:0}
          keep_alive: ${SPRING_AI_OLLAMA_KEEP_ALIVE:10m}
```

Use `http://localhost:11434` when the Java service runs on the host beside a
native Ollama process. Use `http://ollama:11434` when the Java service runs
inside the Docker Compose network.

## Provider Configuration

LLM Council supports multiple LLM providers. Each cloud provider **auto-activates** when its API key or GCP project ID is set to a real value. No explicit "enabled" flags are needed — placeholder credentials are detected and ignored automatically.

### Supported Providers

| Provider | Config Value | Credential | How It Activates |
|---|---|---|---|
| Ollama | `ollama` | None (local) | Always available |
| OpenAI | `openai` | `SPRING_AI_OPENAI_API_KEY` | Auto-detects real key (not a placeholder) |
| Anthropic | `anthropic` | `SPRING_AI_ANTHROPIC_API_KEY` | Auto-detects real key (not a placeholder) |
| Gemini / Vertex AI | `gemini` | `GOOGLE_CLOUD_PROJECT` + ADC or SA | Auto-detects real project ID |
| OCI/OpenAI-compatible | `openai-compatible` | `SPRING_AI_OPENAI_API_KEY` + `SPRING_AI_OPENAI_BASE_URL` | Uses OpenAI detection |
| Mock | `mock` | None | Always available (test-only) |

### How Auto-Detection Works

At startup, each provider's configured API key is inspected. If the key matches a known placeholder value (like `unused-development-placeholder`) or is blank, the provider is marked as unavailable. If the key looks real, the provider activates automatically.

The startup banner shows what was detected:

```text
╔══════════════════════════════════════════════════╗
║       LLM Council — Provider Status              ║
╠══════════════════════════════════════════════════╣
║  OpenAI             ⬚  NOT CONFIGURED            ║
║  Anthropic          ⬚  NOT CONFIGURED            ║
║  Gemini             ✅ DETECTED (auto)           ║
║  Ollama .............. ✅ ALWAYS AVAILABLE       ║
║  Mock ................ ✅ ALWAYS AVAILABLE       ║
╚══════════════════════════════════════════════════╝
```

### Gemini / Vertex AI Setup

Gemini uses Google Cloud Vertex AI. Two authentication options:

**Option 1: Application Default Credentials (ADC)** — simplest for development:

```bash
# Authenticate with GCP
gcloud auth application-default login

# Set project (this is what triggers auto-detection)
export GOOGLE_CLOUD_PROJECT=my-project-id
export GOOGLE_CLOUD_LOCATION=us-central1  # optional, defaults to us-central1

# Start the application — Gemini auto-activates
java -jar target/llm-council-2.0.0.jar
```

**Option 2: Service account JSON** — for CI/CD and production:

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
export GOOGLE_CLOUD_PROJECT=my-project-id
```

Then use the Gemini profile:

```json
{
  "question": "Evaluate this microservices architecture.",
  "depthMode": "BALANCED",
  "profileId": "gemini"
}
```

### Anthropic Setup

Just set the API key — the provider auto-activates:

```bash
export SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...
```

### OpenAI Direct Setup

```bash
export SPRING_AI_OPENAI_API_KEY=sk-...
```

### Multi-Cloud Council

For maximum model diversity, set multiple credentials:

```bash
export GOOGLE_CLOUD_PROJECT=my-project
export SPRING_AI_ANTHROPIC_API_KEY=sk-ant-...

java -jar target/llm-council-2.0.0.jar
```

```json
{
  "question": "Should we adopt event sourcing?",
  "depthMode": "RIGOROUS",
  "profileId": "multi-cloud"
}
```

This runs drafts across Ollama (local), Gemini, and Anthropic models simultaneously, maximizing architectural diversity in the council.

### Profiles

Profiles are user-facing:

```yaml
profiles:
  hybrid:
    defaultDepth: BALANCED
    depthPolicies:
      QUICK: hybrid-quick
      BALANCED: hybrid-balanced
      RIGOROUS: hybrid-rigorous
```

Profiles can be local-only, OCI/OpenAI-compatible only, hybrid, Gemini-only, or multi-cloud.

| Profile | Purpose |
|---|---|
| `local` | Ollama-only local council. Private or offline-capable runs. |
| `oci` | OCI/OpenAI-compatible council. Oracle Code Assist, OCI, or another endpoint. |
| `hybrid` | Local models for draft diversity plus OCI/OpenAI-compatible chair and validator. |
| `gemini` | Google Gemini (Vertex AI) only. Requires `COUNCIL_GEMINI_ENABLED=true`. |
| `multi-cloud` | Maximum diversity: Ollama + Gemini + Anthropic/OpenAI. Requires enabled providers. |
| `mock` | Test-only deterministic profile. Use for smoke tests, not real answers. |

### Policies

Policies are the business contract for one profile/depth pair:

```yaml
hybrid-balanced:
  protocolId: balanced
  memberModelIds: [local-llama3, local-mistral, oci-reviewer]
  chairModelId: oci-gpt-5-4
  validatorModelId: oci-reviewer
  minimumSuccessfulDrafts: 2
  minimumReviewsPerDraft: 1
  validationRequired: true
```

Policies answer:

- Which member models generate drafts?
- Which model synthesizes?
- Which model validates?
- How many drafts are required?
- Is validation required?

### Protocols

Protocols define stage order:

```yaml
balanced:
  orderedStages: [GENERATE, ANONYMIZE, REVIEW, SCORE, SYNTHESIZE, VALIDATE]

rigorous:
  orderedStages: [GENERATE, ANONYMIZE, REVIEW, SCORE, DEBATE, REVISE, REVIEW_POST_DEBATE, SCORE, SYNTHESIZE, VALIDATE, EXPORT]
```

The app ships with:

- `quick`
- `balanced`
- `rigorous`

## Execution Sequence

### QUICK

```text
GENERATE -> SYNTHESIZE
```

Use this for smoke tests and low-stakes local checks.

### BALANCED

```text
GENERATE -> ANONYMIZE -> REVIEW -> SCORE -> SYNTHESIZE -> VALIDATE
```

Use this for normal engineering decisions.

### RIGOROUS

```text
GENERATE -> ANONYMIZE -> REVIEW -> SCORE -> DEBATE -> REVISE -> REVIEW_POST_DEBATE -> SCORE -> SYNTHESIZE -> VALIDATE -> EXPORT
```

Use this for architecture, risk, or design decisions where the extra cost is justified.

The `REVISE` stage lets each model incorporate debate arguments into a revised draft. The `REVIEW_POST_DEBATE` stage asks reviewers to re-evaluate with debate context, so the second `SCORE` pass operates on genuinely updated evidence.

## Stage Details

### GENERATE

Class:

```text
GenerationStageExecutor
```

What it does:

1. Fans out the question to each member model in the policy.
2. Runs calls on virtual threads.
3. Uses role-aware prompts — `PROPOSER` gets standard generation, `CRITIC` gets adversarial prompts, `SYNTHESIZER` gets bridge-building prompts.
4. Stores successful drafts in `CouncilContext`.
5. Records failed models in `excludedModels`.
6. Enforces `minimumSuccessfulDrafts`.

Business rule:

```text
If successful drafts < minimumSuccessfulDrafts, stop the protocol.
```

### ANONYMIZE

Class:

```text
AnonymizeStageExecutor
```

What it does:

1. Replaces model-derived draft IDs with opaque IDs such as `draft-7F2A`.
2. Keeps original model ID inside server-side context.
3. Writes private mapping to:

```text
private/anonymization-map.json
```

Review prompts receive anonymous IDs only.

### REVIEW

Class:

```text
ReviewStageExecutor
```

What it does:

1. Sends anonymized drafts to reviewers.
2. Asks for JSON only.
3. Parses the JSON with `StructuredOutputParser`.
4. Rejects malformed scores or confidence values.
5. Filters self-review.
6. Writes raw and normalized review artifacts.

Expected review shape:

```json
{
  "reviews": [
    {
      "draftId": "draft-A",
      "strengths": ["clear"],
      "issues": ["misses tradeoffs"],
      "criteria": [
        {"name": "accuracy", "score": 82, "rationale": "reasonable"}
      ],
      "overallScore": 80,
      "confidence": 0.7
    }
  ]
}
```

### SCORE

Class:

```text
ScoreStageExecutor
```

What it does:

1. Groups reviews by draft ID.
2. Checks review quorum per draft.
3. Aggregates scores using the configured scoring strategy (default: confidence-weighted).
4. Creates `ScoreArtifact` per draft.
5. Creates `ScoreSummary` with variance and winning draft.
6. If post-debate variance exceeds threshold, triggers escalation policy.

Available scoring strategies (selectable per protocol stage via `scoring-strategy` option):

| Strategy | Description |
|---|---|
| `confidence-weighted` | Default. Weights reviews by reviewer confidence. |
| `average` | Simple arithmetic mean. |
| `median` | Robust to outliers. |
| `trimmed-mean` | Drops highest and lowest, then averages. |

### DEBATE

Class:

```text
DebateStageExecutor
```

What it does:

1. Checks whether debate is forced or score variance exceeds threshold.
2. Runs bounded debate rounds (minimum 2 by default to prevent premature convergence).
3. Uses role-aware debate prompts — `CRITIC` models are explicitly instructed to challenge consensus.
4. Parses confidence from each contribution using multi-pattern extraction.
5. Detects sycophancy via Jaccard word similarity + confidence delta toward majority.
6. Stops early if confidence distributions converge (KS statistic below threshold).

Sycophancy detection formula:

```text
sycophancyIndex = textSimilarity × (confidenceDelta / 100)
```

This is intentionally bounded. More debate is not automatically better.

### REVISE

Class:

```text
RevisionStageExecutor
```

What it does:

1. After debate, each member model receives its original draft plus debate transcript.
2. The model produces a revised draft incorporating the strongest debate arguments.
3. Revised drafts replace originals in context (same draft ID for lineage tracking).
4. If a model fails to revise, its original draft is retained.
5. System prompt explicitly prevents blind capitulation to majority.

### REVIEW_POST_DEBATE

Class:

```text
ReviewPostDebateStageExecutor
```

What it does:

1. Reviewers re-evaluate drafts considering the debate transcript.
2. Uses `postDebateReviewMessages()` — the prompt includes debate history alongside drafts.
3. Post-debate reviews are added to context, so the second SCORE pass uses genuinely updated evidence.
4. System prompt: "Do not simply copy your pre-debate review."

### SYNTHESIZE

Class:

```text
SynthesisStageExecutor
```

What it does:

1. Checks draft quorum again.
2. Sends drafts, reviews, score summary, and debate history to the chair.
3. Requires the chair to include recommendation, rationale, dissent, unresolved risks, and confidence.
4. Writes final answer to:

```text
final/answer.md
```

### VALIDATE

Class:

```text
ValidateStageExecutor
```

What it does:

1. Uses `validatorModelId` from policy.
2. Sends only original question/context and final answer.
3. Does not send the full council transcript.
4. Parses structured JSON validation.
5. Fails the session if validation is required and rejected.

Expected validation shape:

```json
{
  "approved": true,
  "confidence": 0.9,
  "issues": [],
  "recommendedFixes": [],
  "criteria": {
    "correctness": "pass",
    "completeness": "pass"
  },
  "requiresHumanReview": false
}
```

### EXPORT

Class:

```text
ExportStageExecutor
```

What it does:

1. Lists local artifacts.
2. Writes a redacted manifest.
3. Excludes `raw/` and `private/` artifacts unless configured otherwise.

Manifest:

```text
exports/manifest.json
```

## Event Flow

Council events are emitted throughout the run:

```text
PROTOCOL_STARTED
STAGE_STARTED
MODEL_CALL_STARTED
MODEL_CALL_COMPLETED
MODEL_CALL_FAILED
STAGE_COMPLETED
PROTOCOL_COMPLETED
PROTOCOL_FAILED
```

Read events:

```bash
curl http://localhost:8080/api/council/sessions/{sessionId}/events
```

For chat, the SSE endpoint combines chat lifecycle events and linked council
events:

```bash
curl -N http://localhost:8080/api/council/chats/{chatId}/events
```

The current implementation stores session events, chat events, and chat state in
memory. Restarting the app clears them.

## Artifact Flow

Artifacts are written under:

```text
$HOME/.llm-council/runs/{sessionId}/
```

Typical balanced artifacts:

```text
raw/generate-local-llama3.txt
raw/review-local-mistral.json
normalized/drafts-generation.json
normalized/anonymized-drafts.json
normalized/reviews.json
normalized/scores-initial.json
private/anonymization-map.json
final/answer.md
final/validation.json
```

List artifacts:

```bash
curl http://localhost:8080/api/council/sessions/{sessionId}/artifacts
```

## How To Use

### 1. Build

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin:$PATH \
mvn test
```

### 2. Start Local Models

```bash
ollama pull llama3.1:8b
ollama pull mistral:7b
```

### 3. Run Service

```bash
java -jar target/llm-council-2.0.0.jar
```

### 3a. Or Run With Docker Compose

For Apple Silicon M1 with 32 GB memory:

```bash
docker compose -f docker-compose.m1-32gb.yml up --build
```

For a 2019 Intel MacBook Pro with 32 GB memory:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml up --build
```

Detailed runbooks:

- [Testing on M1 Mac with 32 GB memory](testing-m1-32gb.md)
- [Testing on 2019 Intel MacBook Pro with 32 GB memory](testing-intel-2019-32gb.md)

### 4. Create Session

```bash
curl -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Should we use sagas or two-phase commit?",
    "context": "We run Java services across multiple databases.",
    "depthMode": "BALANCED",
    "profileId": "local"
  }'
```

### 5. Run Session

```bash
curl -X POST http://localhost:8080/api/council/sessions/{sessionId}/run
```

### 6. Inspect Result

```bash
curl http://localhost:8080/api/council/sessions/{sessionId}
curl http://localhost:8080/api/council/sessions/{sessionId}/events
curl http://localhost:8080/api/council/sessions/{sessionId}/artifacts
```

## How To Use The Chat API

### 1. Create A QUICK Chat

```bash
CHAT_ID=$(curl -s -X POST http://localhost:8080/api/council/chats \
  -H "Content-Type: application/json" \
  -d '{
    "profileId": "local",
    "depthMode": "QUICK",
    "initialContext": "Architecture tradeoff discussion"
  }' | jq -r .chatId)

echo "$CHAT_ID"
```

### 2. Open The Event Stream

```bash
curl -N "http://localhost:8080/api/council/chats/$CHAT_ID/events"
```

### 3. Send A Message

```bash
curl -s -X POST "http://localhost:8080/api/council/chats/$CHAT_ID/messages" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "For a banking microservice migration, compare sagas, two-phase commit, and the outbox pattern. Give a practical recommendation."
  }' | jq
```

The response returns while the turn is still `RUNNING`. The event stream shows
the underlying council run.

### 4. Inspect The Completed Chat

```bash
curl -s "http://localhost:8080/api/council/chats/$CHAT_ID" | jq
```

Each turn contains:

```text
turnId
userMessage
assistantAnswer
councilSessionId
status
failureReason
```

Use `councilSessionId` to inspect the underlying session:

```bash
SESSION_ID=$(curl -s "http://localhost:8080/api/council/chats/$CHAT_ID" \
  | jq -r '.turns[-1].councilSessionId')

curl -s "http://localhost:8080/api/council/sessions/$SESSION_ID" | jq
```

### 5. Use Different Depth Modes

Create separate chats for separate depths:

```json
{
  "profileId": "local",
  "depthMode": "BALANCED",
  "initialContext": "Enterprise AI architecture review"
}
```

```json
{
  "profileId": "local",
  "depthMode": "RIGOROUS",
  "initialContext": "High-rigor risk analysis"
}
```

For live demos, start with `QUICK`, then show `BALANCED` if local model
preflight passes. Use `RIGOROUS` only after practicing the latency or use
`profileId: "mock"` to show the protocol shape quickly.

## Using The Mock Profile

Mock is explicit and test-only:

```json
{
  "question": "Smoke test",
  "depthMode": "BALANCED",
  "profileId": "mock"
}
```

Mock output is deterministic and parser-friendly. Do not use it to judge answer quality.

## Using OCI Or Oracle Code Assist

The Java service should not read `~/.codex/auth.json`.

Codex uses ChatGPT auth for the development tool. This application uses runtime provider credentials.

Configure your OpenAI-compatible runtime externally:

```bash
export SPRING_AI_OPENAI_API_KEY="$OCA_LLM_API_TOKEN"
export SPRING_AI_OPENAI_BASE_URL="$OCA_LLM_BASE_URL"
export OCA_LLM_MODEL="gpt-5.4"
```

If these values are missing, the application still boots for local and mock use, but OCI model calls fail explicitly.

Then choose:

```json
{
  "question": "Assess this architecture risk.",
  "depthMode": "RIGOROUS",
  "profileId": "oci"
}
```

## Extension Points

Add a model:

1. Add model under `council.models` with the appropriate provider name.
2. Add it to a policy's `memberModelIds`, `chairModelId`, or `validatorModelId`.
3. Ensure the provider is activated (`council.providers.<name>.enabled=true`).
4. Set the model's `councilRole` for debate persona (PROPOSER, CRITIC, SYNTHESIZER).
5. Set `modelFamily` for heterogeneity validation.

Add a provider:

1. Add the Spring AI starter dependency to `pom.xml`.
2. Inject the `ChatModel` bean in `CouncilConfig` with `@Autowired(required = false)`.
3. Add a case to `buildClient()` gated by `isProviderActive()`.
4. Add a provider activation flag under `council.providers` in `application.yml`.
5. Add model entries with the new provider name.

Add a protocol:

1. Add a protocol under `council.protocols`.
2. Map it from a policy.
3. Keep public callers using profile plus depth.

Add a stage:

1. Add enum value to `StageType`.
2. Implement `StageExecutor`.
3. Add it to a protocol.

## Current Limitations

- Events, sessions, and chats are in memory.
- Artifact storage is local file only.
- Spring AI provider-specific option support is intentionally conservative.
- Review repair prompts are not implemented yet; malformed review JSON excludes that reviewer.
- Authentication and authorization are not implemented on the API.
- Chat API V1 and live SSE exist, but they are demo-grade: no durable chat
  store, no cancellation, no queued run recovery, no SSE reconnect cursor, and
  no user ownership.
- Durable history, structured-output repair, model-call metrics, cancellation,
  and production chat persistence remain next implementation sequences in
  [production-readiness-implementation-guide.md](production-readiness-implementation-guide.md).

These are deliberate next steps, not reasons to reintroduce user-selected protocol IDs or silent mock fallback.
