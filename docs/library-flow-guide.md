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
  -> independent model drafts
  -> anonymous review
  -> scoring
  -> optional debate
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

Profiles can be local-only, OCI/OpenAI-compatible only, or hybrid.

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
GENERATE -> ANONYMIZE -> REVIEW -> SCORE -> DEBATE -> SCORE -> SYNTHESIZE -> VALIDATE -> EXPORT
```

Use this for architecture, risk, or design decisions where the extra cost is justified.

## Stage Details

### GENERATE

Class:

```text
GenerationStageExecutor
```

What it does:

1. Fans out the question to each member model in the policy.
2. Runs calls on virtual threads.
3. Stores successful drafts in `CouncilContext`.
4. Records failed models in `excludedModels`.
5. Enforces `minimumSuccessfulDrafts`.

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
3. Averages criteria and overall scores.
4. Creates `ScoreArtifact` per draft.
5. Creates `ScoreSummary` with variance and winning draft.

Debate uses score variance to decide if disagreement is large enough to discuss.

### DEBATE

Class:

```text
DebateStageExecutor
```

What it does:

1. Checks whether debate is forced or score variance exceeds threshold.
2. Runs bounded debate rounds.
3. Parses confidence from each contribution.
4. Stops early if confidence distributions converge.

This is intentionally bounded. More debate is not automatically better.

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

1. Add model under `council.models`.
2. Add it to a policy's `memberModelIds`, `chairModelId`, or `validatorModelId`.
3. Ensure the provider bean exists or implement a provider-specific `ModelClient`.

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
- Debate output is still text plus confidence parsing.
- Authentication and authorization are not implemented on the API.
- Chat API V1 and live SSE exist, but they are demo-grade: no durable chat
  store, no cancellation, no queued run recovery, no SSE reconnect cursor, and
  no user ownership.
- Durable history, structured-output repair, model-call metrics, cancellation,
  and production chat persistence remain next implementation sequences in
  [production-readiness-implementation-guide.md](production-readiness-implementation-guide.md).

These are deliberate next steps, not reasons to reintroduce user-selected protocol IDs or silent mock fallback.
