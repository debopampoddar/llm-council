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
- Debate trigger based on reviewer disagreement about the same draft.
- Chair synthesis with score and dissent context.
- Fresh Eyes validation with structured JSON output.
- Bounded in-memory persistence by default, with optional JDBC persistence for
  sessions, chats, events, and chat sequence state on H2 or SQLite.
- Local artifact storage for raw, normalized, final, export metadata, and the
  durable terminal run response at `final/result.json`.

### Anti-Sycophancy & Quality (Phase 3)
- **Adversarial debate roles**: `PROPOSER`, `CRITIC`, and `SYNTHESIZER` council personas with role-specific system prompts. CRITIC models receive explicit instructions to challenge emerging consensus.
- **Sycophancy detection**: a two-condition gate, each condition in its own unit. A member is flagged when its confidence moved at least `sycophancy-confidence-delta` points toward the majority **and** its reasoning stood still — either its own text barely changed, or its new text has migrated onto the other members' prior language (`alignmentToOthers`). Both components are reported whether or not the member was flagged.
- **Post-debate draft revision** (`REVISE` stage): each model revises its draft incorporating debate arguments before re-scoring.
- **Post-debate re-review** (`REVIEW_POST_DEBATE` stage): reviewers re-evaluate drafts considering debate transcript, so the second SCORE pass uses genuinely updated evidence.
- **Model heterogeneity enforcement**: startup warning when all council members share the same `modelFamily`.

### Scoring & Resilience (Phases 1–2)
- **Confidence-weighted scoring** (default): reviewer scores weighted by self-reported confidence.
- **Pluggable scoring strategies**: `average`, `confidence-weighted`, `median`, `trimmed-mean` — selectable per protocol stage.
- **Disagreement escalation**: `SYNTHESIZE_WITH_DISSENT` or `HALT_AND_ESCALATE` when reviewers still disagree about the same draft after debate. Escalation is only claimed when that disagreement was measurable — it needs two reviewers on one draft, which a two-member council never has once self-review is excluded.
- **Retry with exponential backoff**: `RetryableModelClient` decorator retries transient failures (`PROVIDER_UNAVAILABLE`, `MODEL_TIMEOUT`) with jitter.
- **Confidence parsing**: free-text confidence is captured once and normalised onto 0–100, so `Confidence: 85`, `confidence: 0.85`, `.7`, and `92%` all resolve correctly. A bare `0` or `1` is refused as ambiguous rather than guessed, and anything unreadable is reported as unreadable rather than defaulted — an unreadable round is excluded from convergence and sycophancy analysis and says so in the run warnings.
- **JSON parsing resilience**: markdown fence stripping, trailing comma tolerance, lenient Jackson configuration.
- **Token usage tracking**: Ollama (`prompt_eval_count`/`eval_count`) and Spring AI (`getUsage()`) token extraction.
- **Minimum debate rounds**: prevents premature convergence from sycophantic first-round agreement.
- **Convergence sized to the council**: debate stops early when every member's confidence moved less than `convergence-confidence-delta` points. The two-sample KS test is used only from 8 members up, where it is not quantised into uselessness — on a three-member council the statistic can only take the values 0, ⅓, ⅔ and 1.
- **Council-composition warnings at boot**: two member ids resolving to one provider model, a chair seated as a member, or a `median`/`trimmed-mean` strategy on a council too small to aggregate.
- **Immutable ModelRegistry**: constructor-injected via `@Bean` — no mutable post-construction registration.

### Multi-Provider Support
- **Credential auto-detection**: providers activate automatically when real API keys are set. Placeholder keys (e.g. `unused-development-placeholder`) are detected and ignored — no explicit "enabled" flags needed.
- **Google Gemini / Vertex AI**: `spring-ai-starter-model-vertex-ai-gemini` with conditional activation. Supports both Application Default Credentials (ADC) and service account JSON.
- **Pre-built profiles**: `gemini` (Gemini-only), `multi-cloud` (Ollama + Gemini + Anthropic/OpenAI) with full QUICK/BALANCED/RIGOROUS policy sets.
- **Startup provider banner**: logs which providers were auto-detected at boot.
- **Graceful degradation**: models on disabled providers fall through to `UnavailableModelClient` with actionable error messages.

### Web UI (Phase 2)
- Chat view at `/`, served straight from the static classpath root — no Node, no bundler, no build step.
- Preflight health gate before the send button, with three states: verified, unverified, blocked.
- Live council stage timeline over SSE, with per-stage evidence expanded from the run's own artifacts.
- A trust strip above every answer carrying confidence with its independence tier, member roster, sycophancy findings, and preserved dissent.
- Cancel a running council from the browser.

### Requirement Advisor (Phase 5)
- Setup wizard at `/setup.html`: describe the council you want in plain language, review what was understood, and get a configuration built from the models this machine can actually run.
- **The model produces intent; deterministic Java produces configuration.** An LLM's only output is a small record of closed choices — it cannot emit a model id, a provider, or a stage type, because the record has nowhere to put one.
- Nothing uncallable is ever proposed: an Ollama tag you have not pulled, a provider with no credential, and a mock model are all excluded before selection rather than configured and discovered at run time.
- **Additive.** The advisor owns the `advisor-*` namespace and replaces its own previous output; everything else in your configuration is carried through untouched.
- Extraction is optional. No model available, a provider that fails, or a reply it cannot read all fall back to the same form — with your typing still on screen.
- Nothing is sent to a cloud provider until it is named and you confirm it, enforced on the server rather than in the page.
- "Save for later" writes a proposal file that startup never reads, re-checked every time you come back to it.

### Testing
- 887 JUnit tests: policy resolution, confidence parsing, quorum, KS convergence math, sycophancy detection at the shipped thresholds, council-composition warnings, debate and post-debate evidence handling, all scoring strategies, retry and timeout logic, concurrent-run lifecycle, full protocol integration, path-containment and deletion-cascade security, durable stores against H2 and SQLite, Docker rigorous-model provisioning, the catalog, config-write and advisor endpoints, static resource serving, cancellation, and configuration synthesis across every requirement combination.

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

Public callers choose:

- `profileId`: a configured profile such as `default`, `local`, `oci`,
  `hybrid`, `gemini`, `multi-cloud`, or the test-only `mock`.
- `depthMode`: `QUICK`, `BALANCED`, or `RIGOROUS`.

Configuration maps that pair to a `CouncilPolicy`.

| Profile | Purpose |
|---|---|
| `default` | Alias of the shipped local policy mapping, defaulting to BALANCED. |
| `local` | Ollama-only local council. Useful for private or offline-capable runs. |
| `oci` | OCI/OpenAI-compatible council. Useful for Oracle Code Assist LiteLLM, OCI, or another OpenAI-compatible provider. |
| `hybrid` | Local models for draft diversity plus OCI/OpenAI-compatible chair and validator. |
| `gemini` | Gemini/Vertex-only council. Requires a configured Google Cloud project and credentials. |
| `multi-cloud` | Ollama plus Gemini, Anthropic, and OpenAI models for maximum provider diversity. Required providers depend on depth. |
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

If `DEBATE` does not trigger, `REVISE`, `REVIEW_POST_DEBATE`, and the second
`SCORE` pass are explicitly skipped. The initial score remains authoritative;
the run does not manufacture a post-debate comparison without debate evidence.

## Architecture

### Council Roles

Each council member model is assigned a `CouncilRole` (separate from structural `ModelRole`):

| Role | Behavior |
|---|---|
| `PROPOSER` | Default. Produces an independent answer with a concise, inspectable rationale. |
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

## Publishing

Releases go to GitHub Packages. The
[publish workflow](.github/workflows/publish.yml) runs on a published release
and needs no secret beyond the automatic `GITHUB_TOKEN` — cutting a release is
the whole procedure.

**A published version cannot be replaced.** GitHub Packages refuses a re-deploy
of a version that already exists rather than overwriting it, so every release
bumps the version; there is no republishing a fix under the same number.

To publish by hand you need a personal access token with `write:packages`, in
`~/.m2/settings.xml`. The `id` must be `github`, matching
`<distributionManagement>` in the POM:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>ghp_YOUR_TOKEN</password>
    </server>
  </servers>
</settings>
```

Then:

```bash
mvn deploy
```

Never put that token in the POM or anywhere in this repository. It belongs in
`~/.m2/settings.xml`, which is outside the working tree — the same rule the
application itself follows for provider credentials.

### Consuming the published artifact

**GitHub Packages requires authentication to download as well as to publish**,
even for a public package. There is no anonymous read, unlike Maven Central, so
anyone depending on this needs a token with `read:packages` in their own
`settings.xml` plus:

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/debopampoddar/llm-council</url>
</repository>
```

The published artifact is the **executable** jar produced by Spring Boot's
`repackage` — the one you run with `java -jar`. Its dependencies are nested
under `BOOT-INF/lib` rather than on a normal classpath, so it is a distribution
format, not something to depend on as a library. Publishing a plain library jar
alongside it would mean giving the fat jar a classifier and letting the thin one
be the main artifact.

## Run

```bash
java -jar target/llm-council-2.0.0.jar
```

Then open **<http://localhost:8080/>** for the web UI.

To try it with no model runtime at all, pick the `mock` profile in the profile
dropdown: it runs the full rigorous protocol offline in well under a second.
`mock` is flagged test-only and is labelled as such wherever it appears, because
its output is fabricated and must never be mistaken for a council answer.

The default profile is `local`, so make sure Ollama is running and pull the configured models first.

If Ollama is not already running through the macOS app or another service manager, start it in a separate terminal:

```bash
ollama serve
```

Then pull the local models:

```bash
ollama pull llama3.1:8b
ollama pull mistral:7b
ollama pull qwen2.5:7b   # third distinct member for local-rigorous
```

### Building a council without writing YAML

Open **<http://localhost:8080/setup.html>** and describe what you want in plain
language. A local model reads that into a small set of choices, you correct
anything it got wrong, and deterministic Java turns the result into
configuration — choosing only from models that are installed here and providers
that are actually configured.

If no model is available to read your description, the wizard opens on the same
choices as a form and says why. Extraction is a convenience, never a dependency.

The wizard **only adds**. Anything already in your configuration is carried
through unchanged, and the confirmation step shows you what would be removed
before it writes anything — which should be nothing. You can also save a council
for later; the proposal is kept in a separate file that is never read at startup,
and is re-checked when you come back in case the models it names are gone.

```bash
curl localhost:8080/api/council/advisor/environment   # what this machine can run
```

### Defining your own models, policies, and profiles

The wizard writes the same file you can write yourself. To add your own without
editing the shipped `application.yml`, drop an overlay at
`~/.llm-council/council-user.yml`:

```bash
cp council-user.example.yml ~/.llm-council/council-user.yml
```

The example file documents every field and its bounds. The overlay is merged
over the built-in configuration at startup, so you only state what you want to
change; unmentioned fields, depths, and entities keep their shipped values.

**Mistakes are survivable.** An invalid entry is dropped and reported while
everything else still applies and the application still starts. See what was
accepted or rejected:

```bash
curl 'localhost:8080/api/council/catalog?include=issues,profiles'
```

**What you can change:** bind models on any supported provider, compose policies
and profiles, and tune protocols within validated bounds.

**What you cannot:** add a provider (that needs a `ModelClient` implementation),
reorder or remove protocol stages, or use the test-only mock models in a real
council. Anonymised review and adversarial debate are what make the council
resistant to sycophancy, so they are not removable — you tune protocols rather
than compose them.

**Never put an API key in the overlay.** Credentials are read from the
environment only. A key found in the file is refused, and you should rotate it:
it has been written to disk in plain text. To see which providers are active and
which environment variable activates the rest:

```bash
curl 'localhost:8080/api/council/catalog?include=providers'
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
| `docker-compose.m1-32gb.yml` | Apple Silicon M1 class Mac with 32 GB memory | `llama3.1:8b`, `mistral:7b`, `qwen2.5:7b` |
| `docker-compose.m1-32gb-app-only.yml` | Apple Silicon M1 app container plus native/separate Ollama | `llama3.1:8b`, `mistral:7b`, `qwen2.5:7b` |
| `docker-compose.intel-2019-32gb.yml` | 2019 Intel MacBook Pro with 32 GB memory | `llama3.2:3b`, `qwen2.5:3b`, `qwen2.5:7b` |

Validate and start on M1:

```bash
docker compose -f docker-compose.m1-32gb.yml config >/tmp/llm-council-m1-compose.yml
docker compose -f docker-compose.m1-32gb.yml up --build
```

Recommended M1 path when Ollama runs natively or separately:

```bash
ollama pull llama3.1:8b
ollama pull mistral:7b
ollama pull qwen2.5:7b   # third distinct member for local-rigorous
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

Read the trust signals for a finished run — sycophancy warnings, excluded
models, scores, the validation verdict and its independence tier. The
synchronous endpoint above returns this shape directly; this is how the chat
path, which returns as soon as a run is submitted, gets at the same thing.
404 means the run has not finished, not that anything is wrong:

```bash
curl http://localhost:8080/api/council/sessions/{sessionId}/result
```

Stop a running council. Returns 202 with the status at the time of the request.
Cancellation is honoured at **stage boundaries only** — a model call already in
flight runs to completion and its result is discarded, so cancelling a long
Ollama generation still waits for that call. Cancelling a run that already
finished is a no-op, not an error:

```bash
curl -X DELETE http://localhost:8080/api/council/sessions/{sessionId}/run
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

### Requirement Advisor API

The wizard is a client of these; nothing about them needs a browser.

```bash
# What can actually be seated here: installed models, provider states, remediation.
curl localhost:8080/api/council/advisor/environment

# Free text to a requirement. modelId must be one the environment offered, and a
# non-local one is refused unless acknowledgeCloudProvider is true.
curl -X POST localhost:8080/api/council/advisor/extract \
  -H 'Content-Type: application/json' \
  -d '{"text":"a careful local council for reviewing code","modelId":"local-chair"}'

# A requirement to configuration, with its rationale, validation, and diff.
curl -X POST localhost:8080/api/council/advisor/synthesize \
  -H 'Content-Type: application/json' \
  -d '{"requirement":{"privacy":"LOCAL_ONLY","cost":"FREE_ONLY","rigor":"RIGOROUS",
                      "councilSize":3,"adversarialEmphasis":true}}'

# Save it without applying. Takes a requirement, never a document.
curl -X PUT localhost:8080/api/council/advisor/proposal \
  -H 'Content-Type: application/json' \
  -d '{"requirement":{"privacy":"LOCAL_ONLY","rigor":"BALANCED"}}'

curl localhost:8080/api/council/advisor/proposal            # re-checked on read
curl -X DELETE localhost:8080/api/council/advisor/proposal  # discard it
```

Applying goes through the configuration write path that already exists —
`PUT /api/council/config/draft` — so there is one place that touches the overlay
file, one atomic rename, and one backup. Saved configuration takes effect at the
next restart.

Synthesis answers with `200` and a null `profileId` when this machine has nothing
to seat, carrying the reason and the command that fixes it. That is an answer to a
well-formed question, not a malformed request.

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

Each message creates one linked council session. Persistence defaults to bounded
memory; set `council.persistence.type=jdbc` and configure H2 or SQLite for
durable sessions, chats, event history, restart interruption recovery, and SSE
cursor replay. Cancellation and chat deletion are implemented:

```bash
curl -X DELETE http://localhost:8080/api/council/sessions/{sessionId}/run
curl -X DELETE http://localhost:8080/api/council/chats/{chatId}
```

Chat deletion cascades through its linked council sessions, persisted events,
cached results, and artifact directories. The remaining production gaps are
authentication/ownership, a durable queued scheduler, and per-provider limits.

Artifacts are written under:

```text
${LLM_COUNCIL_ARTIFACT_PATH:-$HOME/.llm-council/runs}/{sessionId}/
```

## Configuring OCI Or Oracle Code Assist Runtime

The `oci` profile uses logical models with provider `openai-compatible`. Configure Spring AI's OpenAI-compatible client externally. The exact environment variables depend on your Spring AI setup and endpoint, but the runtime concept is:

```bash
export SPRING_AI_OPENAI_API_KEY="$OCA_LLM_API_TOKEN"
export SPRING_AI_OPENAI_BASE_URL="$OCA_LLM_BASE_URL"
export OCA_LLM_MODEL="gpt-4o"
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
com.debopam.llmcouncil.advisor          requirement extraction, config synthesis, proposals
com.debopam.llmcouncil.api              REST controller and DTOs
com.debopam.llmcouncil.application      service, policy resolver, event publisher
com.debopam.llmcouncil.chat             chat sessions, turns, async chat service, event broker
com.debopam.llmcouncil.config           configuration binding, validation, and registry setup
com.debopam.llmcouncil.domain           session, status, depth, event records
com.debopam.llmcouncil.model            model profiles, policies, clients, retry decorator
com.debopam.llmcouncil.orchestration    protocol, stages, prompts, parser, scoring strategies,
                                        sycophancy detection, convergence detector, artifacts
com.debopam.llmcouncil.persistence      bounded memory/JDBC stores, retention, migrations,
                                        local artifacts, deletion cascade

src/main/resources/static                web UI — vanilla HTML/CSS/JS, no build step
├── index.html                           chat view, served at /
├── setup.html                           requirement advisor wizard
├── css/app.css                          single stylesheet, light and dark
└── js/
    ├── api.js                           fetch wrapper over /api/council/**
    ├── advisor-api.js                   fetch wrapper over /api/council/advisor/**
    ├── sse.js                           EventSource lifecycle, dedupe, backoff
    ├── chat.js                          chat list, composer, turn states
    ├── health.js                        preflight gate and independence tiers
    ├── timeline.js                      council stage timeline
    ├── trust.js                          trust strip, sycophancy, dissent
    ├── artifacts.js                     per-stage evidence panels
    ├── providers.js                     read-only provider status panel
    ├── proposal.js                      unapplied-proposal and first-run notices
    ├── setup.js                         the five-step wizard
    ├── requirement-form.js              the requirement as editable choices
    ├── dom.js                           node builders
    └── main.js                          app state and orchestration
```

## More Detail

See [docs/library-flow-guide.md](docs/library-flow-guide.md) for a simple but detailed explanation of the business logic, execution sequence, configuration model, and extension points.

See [docs/production-readiness-plan.md](docs/production-readiness-plan.md) for the prioritized robustness and production-readiness plan.

See [docs/production-readiness-implementation-guide.md](docs/production-readiness-implementation-guide.md) for the historical implementation sequence, current status matrix, and remaining implementation guidance. Code sketches in that document are design history, not drop-in current code.

See [docs/testing-m1-32gb.md](docs/testing-m1-32gb.md) for the Apple Silicon/Rancher or Docker Desktop runbook, and [docs/testing-intel-2019-32gb.md](docs/testing-intel-2019-32gb.md) for the Intel Docker runbook.
