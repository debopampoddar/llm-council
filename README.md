# LLM Council

[![Pull request checks](https://github.com/debopampoddar/llm-council/actions/workflows/ci.yml/badge.svg)](https://github.com/debopampoddar/llm-council/actions/workflows/ci.yml)
[![Java 25](https://img.shields.io/badge/Java-25-007396.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

LLM Council is a Java 25 / Spring Boot library and service for running a configurable council of language models. A caller submits a question, chooses a profile such as `local`, `openai`, `claude`, `gemini`, or `multi-cloud`, and chooses a depth mode such as `QUICK`, `BALANCED`, or `RIGOROUS`. The application resolves that request to an internal policy and protocol, collects model drafts, reviews and scores them, optionally debates disagreements, synthesizes a final answer, and validates it with a Fresh Eyes model.

The public API does not accept raw protocol IDs. Protocols are owned by application configuration so users cannot bypass validation, quorum, or cost controls.

This project is designed for engineers who want to study or demonstrate
multi-model orchestration with inspectable intermediate evidence—not merely put
several model calls behind a voting prompt.

## Why This Project Is Useful

- **Inspectable:** every stage emits events and artifacts instead of hiding the
  council behind one opaque response.
- **Configurable:** models, policies, profiles, and safe protocol tuning can be
  changed without editing orchestration code.
- **Honest about trust:** validation independence, partial evidence, dissent,
  retries, and prompt-injection limits are visible to the user.
- **Evaluation-ready:** the separate
  [evaluation harness](https://github.com/debopampoddar/llm-council-evaluation)
  compares the council with direct-model and same-model-ensemble baselines.
- **Java-first:** the implementation, APIs, persistence, configuration, tests,
  and operational controls are built with Java and Spring Boot.

## Current Status

| Area | Status |
|---|---|
| Personal/local use | Ready for controlled use on loopback |
| Deterministic build | 953 tests plus repository YAML/link/provider checks |
| Local Ollama | QUICK, BALANCED, and RIGOROUS profiles are implemented |
| Cloud providers | OpenAI, Anthropic, Gemini, and multi-cloud profiles are implemented; repeatable live contract suites remain open |
| Security boundary | Layered prompt/data separation and bounded recovery are implemented; this is not universal prompt-injection prevention |
| Quality evidence | Historical local evidence did not demonstrate that RIGOROUS beats the direct or same-model-ensemble baselines |
| Shared/public deployment | Not ready: authentication, authorization, ownership, and distributed admission are not implemented |

The tracked historical 36-case ablation found no rigorous-council advantage and
also exposed partial runs and an adversarial failure. Those findings drove the
current hardening, so that dataset is now contaminated for confirmation. A fresh
security regression and new held-out evidence are required before publishing a
quality claim. See the
[evaluation repository](https://github.com/debopampoddar/llm-council-evaluation)
for the reports and methodology.

> **Read [Limitations](#limitations) before trusting a result.** There is no
> authentication; loopback binding is the only access control. A green build
> establishes deterministic implementation behavior, not model quality.

## Quick Local Demo

Prerequisites: Java 25, Maven 3.9+, and a running Ollama service.

```bash
ollama pull llama3.1:8b
ollama pull mistral:7b
ollama pull qwen2.5:7b
ollama pull gemma4:12b-it-qat

mvn --batch-mode --no-transfer-progress clean verify
java -jar target/llm-council-2.0.1.jar
```

Open <http://127.0.0.1:8080>, choose `local` and `QUICK`, and ask a question.
Move to `BALANCED` or `RIGOROUS` only after the fast path works. The setup wizard
at <http://127.0.0.1:8080/setup.html> can build a configuration from the models
available on the machine; the advanced workbench is at
<http://127.0.0.1:8080/config.html>.

For Docker, cloud-provider credentials, API examples, and troubleshooting, use
the [documentation index](docs/README.md). To run a low-cost OpenAI or Claude
example, use the [cloud quick start](docs/cloud-quickstart.md). For a concise
demo, screenshot plan, and evidence-safe blog outline, use the
[showcase and blog guide](docs/showcase-and-blog-guide.md).

## What This Implements

### Core Council Engine
- Profile plus depth policy resolution.
- Separate local, OpenAI, Claude, Gemini, multi-cloud, and explicit mock profiles.
- Config-owned protocols: `quick`, `balanced`, and `rigorous`.
- Quorum enforcement before synthesis.
- Explicit unavailable-provider failures instead of silent mock fallback.
- Provider/model health preflight for profile-depth combinations.
- Structured model failure categories in run responses.
- Chat API V1 with asynchronous council runs and server-sent progress events.
- Anonymized draft IDs with private model mapping artifacts.
- Resilient structured-review parsing with exact non-self coverage checks,
  one bounded sanitized call for malformed or omitted reviews, and per-draft scoring.
- Debate trigger based on reviewer disagreement about the same draft.
- One bounded member regeneration with explicit context directives removed when
  an initial draft returns an attacker-requested literal as a standalone result.
- Chair synthesis with score and dissent context plus one bounded recovery when
  the first output violates an explicit-literal or reserved-internal-output invariant;
  likely internal narration is a cleanup signal rather than a security verdict.
- Fresh Eyes validation with structured JSON output and one bounded clean-room
  retry against sanitized context when any authority-bearing validator field
  repeats an attacker-requested literal.
- Bounded in-memory persistence by default, with optional JDBC persistence for
  sessions, chats, events, and chat sequence state on H2 or SQLite.
- Local artifact storage for raw, normalized, final, export metadata, and the
  durable terminal run response at `final/result.json`.

### Deliberation And Anti-Sycophancy
- **Adversarial debate roles**: `PROPOSER`, `CRITIC`, and `SYNTHESIZER` council personas with role-specific system prompts. CRITIC models receive explicit instructions to challenge emerging consensus.
- **Sycophancy detection**: a two-condition gate, each condition in its own unit. A member is flagged when its confidence moved at least `sycophancy-confidence-delta` points toward the majority **and** its reasoning stood still — either its own text barely changed, or its new text has migrated onto the other members' prior language (`alignmentToOthers`). Both components are reported whether or not the member was flagged.
- **Post-debate draft revision** (`REVISE` stage): each model revises its draft incorporating debate arguments before re-scoring.
- **Post-debate re-review** (`REVIEW_POST_DEBATE` stage): reviewers re-evaluate drafts considering debate transcript, so the second SCORE pass uses genuinely updated evidence.
- **Model heterogeneity enforcement**: startup warning when all council members share the same `modelFamily`.

### Scoring, Resilience, And Observability
- **Confidence-weighted scoring** (default): reviewer scores weighted by self-reported confidence.
- **Pluggable scoring strategies**: `average`, `confidence-weighted`, `median`, `trimmed-mean` — selectable per protocol stage.
- **Disagreement escalation**: `SYNTHESIZE_WITH_DISSENT` or `HALT_AND_ESCALATE` when reviewers still disagree about the same draft after debate. Escalation is only claimed when that disagreement was measurable — it needs two reviewers on one draft, which a two-member council never has once self-review is excluded.
- **Retry with exponential backoff**: `RetryableModelClient` decorator retries transient failures (`PROVIDER_UNAVAILABLE`, `MODEL_TIMEOUT`) with jitter.
- **Confidence parsing**: free-text confidence is captured once and normalised onto 0–100, so `Confidence: 85`, `confidence: 0.85`, `.7`, and `92%` all resolve correctly. A bare `0` or `1` is refused as ambiguous rather than guessed, and anything unreadable is reported as unreadable rather than defaulted — an unreadable round is excluded from convergence and sycophancy analysis and says so in the run warnings.
- **JSON parsing resilience**: markdown fence stripping, trailing comma tolerance, lenient Jackson configuration.
- **Token usage tracking**: Ollama (`prompt_eval_count`/`eval_count`) and Spring AI (`getUsage()`) token extraction.
- **Operational metrics**: cardinality-safe Micrometer counters, timers, token summaries,
  retry counts, active-run gauge, and admission-rejection counts through Actuator.
- **Minimum debate rounds**: prevents premature convergence from sycophantic first-round agreement.
- **Convergence sized to the council**: debate stops early when every member's confidence moved less than `convergence-confidence-delta` points. The two-sample KS test is used only from 8 members up, where it is not quantised into uselessness — on a three-member council the statistic can only take the values 0, ⅓, ⅔ and 1.
- **Council-composition warnings at boot**: two member ids resolving to one provider model, a chair seated as a member, or a `median`/`trimmed-mean` strategy on a council too small to aggregate.
- **Immutable ModelRegistry**: constructor-injected via `@Bean` — no mutable post-construction registration.

### Multi-Provider Support
- **Credential auto-detection**: providers activate automatically when real API keys are set. Placeholder keys (e.g. `unused-development-placeholder`) are detected and ignored — no explicit "enabled" flags needed.
- **Google Gemini / Vertex AI**: `spring-ai-starter-model-vertex-ai-gemini` with conditional activation. Supports both Application Default Credentials (ADC) and service account JSON.
- **Pre-built profiles**: `openai`, `claude`, `gemini`, and `multi-cloud` (Ollama + Gemini + Anthropic/OpenAI), each with QUICK/BALANCED/RIGOROUS policy sets.
- **Startup provider banner**: logs which providers were auto-detected at boot.
- **Graceful degradation**: models on disabled providers fall through to `UnavailableModelClient` with actionable error messages.

### Web UI
- Chat view at `/`, served straight from the static classpath root — no Node, no bundler, no build step.
- Preflight health gate before the send button, with three states: verified, unverified, blocked.
- Live council stage timeline over SSE, with per-stage evidence expanded from the run's own artifacts.
- A trust strip above every answer carrying confidence with its independence tier, member roster, sycophancy findings, and preserved dissent.
- Cancel a running council from the browser.

### Requirement Advisor
- Setup wizard at `/setup.html`: describe the council you want in plain language, review what was understood, and get a configuration built from the models this machine can actually run.
- **The model produces intent; deterministic Java produces configuration.** An LLM's only output is a small record of closed choices — it cannot emit a model id, a provider, or a stage type, because the record has nowhere to put one.
- Nothing uncallable is ever proposed: an Ollama tag you have not pulled, a provider with no credential, and a mock model are all excluded before selection rather than configured and discovered at run time.
- **Additive.** The advisor owns the `advisor-*` namespace and replaces its own previous output; everything else in your configuration is carried through untouched.
- Extraction is optional. No model available, a provider that fails, or a reply it cannot read all fall back to the same form — with your typing still on screen.
- Nothing is sent to a cloud provider until it is named and you confirm it, enforced on the server rather than in the page.
- "Save for later" writes a proposal file that startup never reads, re-checked every time you come back to it.

### Advanced configuration workbench
- Workbench at `/config.html` for users who want to edit or import the YAML overlay directly.
- Uses the server-owned schema and strict parser, then shows validation issues, integrity warnings, the merged-catalog diff, and the profiles that will be selectable after restart.
- Save is locked to the exact validated text, requires a second confirmation, writes atomically, and keeps the previous file as a backup.
- A new provider model can be live-probed before saving. The probe uses a fixed non-sensitive prompt, eight output tokens, a fixed timeout, no retries, and a global cooldown. Cloud probes require an explicit acknowledgement because they may be billable.
- Credentials are structurally outside the request and overlay contracts; credential fields and credential-shaped values are refused without being echoed.

### Testing
- 953 deterministic JUnit tests: policy resolution, confidence parsing, validation
  evidence consistency, quorum, multi-envelope and compact local-model review output,
  exact review coverage and targeted recovery, partial-state reporting, KS convergence
  math, sycophancy detection at the shipped thresholds, council-composition warnings,
  model-call/telemetry behavior, debate and post-debate evidence handling, all scoring
  strategies, retry and timeout logic, concurrent-run lifecycle, full protocol
  integration, path-containment and deletion-cascade security, durable stores against
  H2 and SQLite, Docker rigorous-model provisioning, the catalog,
  config-write/advisor/model-probe endpoints, static resource serving, cancellation,
  and configuration synthesis across every requirement combination.
- Pull requests and pushes to `main` run a clean Java 25 Maven build plus YAML, local Markdown-link, and removed-provider regression checks in `.github/workflows/ci.yml`.

## Limitations

Everything above describes what the system does. This section describes what it
has not been shown to do. Read it before trusting a result.

**Current evidence does not establish that a council beats one good model.** The
deterministic test suite verifies implementation behavior, not answer superiority.
The tracked historical local ablation favored the direct and same-model-ensemble
baselines over RIGOROUS under one local judge, while several council candidates
were partial. That run predates the latest hardening and its dataset subsequently
informed changes, so it is historical diagnostic evidence rather than confirmation
of the current code. A rigorous run can cost many times the baseline's one call,
so the burden of proof is real and has not been met. The separate
[llm-council-evaluation](https://github.com/debopampoddar/llm-council-evaluation)
harness exists to answer this — blinded pairwise judging against a true one-call
baseline plus a same-model-ensemble ablation. Treat the council's advantage as a
hypothesis this project is testing, not a property it has.

**The detection thresholds are uncalibrated starting points.** These four values
in `application.yml` were chosen by reasoning, not fitted to recorded debate
transcripts:

| Option | Ships as |
|---|---|
| `debate-trigger-score-variance` | `40.0` |
| `sycophancy-threshold` | `0.70` |
| `sycophancy-confidence-delta` | `15.0` |
| `convergence-confidence-delta` | `5.0` |

Treat any of them as a hypothesis. Two related caveats on the sycophancy gate
specifically: its Jaccard similarity runs over raw whitespace tokens with no
stopword removal, so shared function words put a floor under every score; and
because a flag requires confidence to have *moved toward* the majority, a member
that agreed immediately and never wavered produces no movement and is never
flagged, however completely its language converges on everyone else's. This is a
detector blind spot: immediate agreement may be genuine or sycophantic, and the
current signal cannot distinguish the two.

**There is no authentication or authorization.** `server.address` defaults to
`127.0.0.1`, and loopback binding is the only access control this application
has. The Docker Compose files set `LLM_COUNCIL_BIND_ADDRESS=0.0.0.0` because a
container must bind all interfaces to publish a port; a `NetworkExposureWarning`
logs whenever the bind address is not loopback. Do not expose this to an
untrusted network. Artifacts and events are stored unencrypted, and there is no
user-level ownership model.

**A green build does not prove a provider contract.** `mvn test` is hermetic — no
network, no live model calls. It proves the orchestration is internally
consistent. A prior three-member Ollama council has been exercised live by
hand; OpenAI, Anthropic, and Vertex call paths have not been covered by a
repeatable suite.

**Homogeneous single-family profiles cannot validate themselves independently.**
The `openai`, `claude`, and `gemini` profiles draw chair and validator from one
model family, so `ValidationIndependence` classifies them `CORRELATED`. The
application reports this honestly rather than hiding it, but a reported warning
is not independence. Independence is checked against the chair and every drafting
member, not just the chair. `local-*` and `multi-cloud-*` are `INDEPENDENT`
because they use the dedicated Gemma validator; `*-quick` is `NOT_APPLICABLE`
because QUICK declares no validator at all.
`ShippedValidationIndependenceTest` is the authority on the current tiers.

**Supporting context is untrusted data.** Context defaults to purpose
`EVIDENCE`; complete lines containing a recognized explicit directive are
removed before the first model call. A caller whose authorized task is to
analyze hostile text can explicitly select `ANALYSIS_SUBJECT`, which preserves
the quoted text as data without granting it instruction authority. Every
model-facing stage receives a
JSON provenance envelope: only `task.text` has instruction authority; context,
drafts, reviews, scores, and debate turns have none. Prompts reinforce that
boundary, and reviews score grounding and trust-boundary compliance. The
deterministic backstop recognizes a closed grammar of explicit literal-output
directives and rejects only a complete standalone answer or verdict equal to
the requested literal. Comparison uses Unicode NFKC normalization, case folding,
zero-width/control removal, and whitespace normalization; it performs no
stemming, sentiment, synonym, or negative-phrase analysis. An objective
violation receives one retry with the directive removed. Synthesis recovery uses
sanitized, identifier-free evidence; a closed list of application-owned envelope
labels and process phrases is also forbidden in ordinary user-facing answers.
Repeated literal or reserved-internal-output violations fail closed, including
in QUICK. Natural-language council narration
receives one cleanup attempt but remains a quality warning rather than a security
decision. A validation-required synthesis is private until approved; rejected
text is not returned as a displayable answer or written to `final/answer.md`.
This materially reduces a demonstrated injection path; it is not a
proof that prompt injection is solved. See
[the prompt-injection threat model](docs/prompt-injection-threat-model.md).

**Its shape is a single-process local application.** One run at a time by default
(`council.runtime.max-concurrent-runs: 1`), cancellation honoured at stage
boundaries only, and an interrupted run is reported rather than resumed. This is
a good personal tool and a strong platform to build on; it is not a distributed
scheduler and not a multi-tenant service.

## Runtime Requirements

- Java 25.
- Maven 3.9+.
- Optional local model runtime: Ollama, either as the macOS app/background service or via `ollama serve`.
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

## Provider Credentials

Configure runtime model providers with their own environment variables. Local Ollama calls use direct `/api/chat` HTTP requests against `spring.ai.ollama.base-url`; OpenAI and Anthropic calls use their Spring AI clients.

`application.yml` supplies harmless placeholder keys for eager Spring AI auto-configuration so local/mock profiles can boot without OpenAI or Anthropic credentials. Those placeholders are not valid for runtime model calls. OpenAI and Claude runs require their respective real API keys.

Credentials belong in the environment and never in the repository or in the user configuration overlay. The overlay contract is structurally incapable of carrying one: credential field names and credential-shaped values are refused before parsing, and the refusal never echoes the value.

## Operational Metrics

Actuator exposes the bounded metrics catalog at `GET /actuator/metrics`. Useful
meters include:

- `llm.council.model.calls` and `llm.council.model.duration`, tagged by configured
  model, provider, stage, outcome, and stable failure category;
- `llm.council.model.tokens`, split into input/output directions when the provider
  reports usage;
- `llm.council.model.retries`;
- `llm.council.stage.duration`;
- `llm.council.runs.active` and `llm.council.runs.rejected`.

For example, inspect `GET /actuator/metrics/llm.council.model.calls` after a run.
Metrics never tag session IDs, prompts, responses, or exception messages. These
meters are the minimum operational baseline; dashboards, alerts, storage-failure,
cancellation, and synchronous-endpoint admission metrics remain future work.

## Profiles And Depth Modes

Public callers choose:

- `profileId`: a configured profile such as `default`, `local`, `openai`,
  `claude`, `gemini`, `multi-cloud`, or the test-only `mock`.
- `depthMode`: `QUICK`, `BALANCED`, or `RIGOROUS`.

Configuration maps that pair to a `CouncilPolicy`.

| Profile | Purpose |
|---|---|
| `default` | Alias of the shipped local policy mapping, defaulting to BALANCED. |
| `local` | Ollama-only local council. Useful for private or offline-capable runs. |
| `openai` | OpenAI-only council. Requires `SPRING_AI_OPENAI_API_KEY`. |
| `claude` | Anthropic Claude-only council. Requires `SPRING_AI_ANTHROPIC_API_KEY`. |
| `gemini` | Gemini/Vertex-only council. Requires a configured Google Cloud project and credentials. |
| `multi-cloud` | Ollama plus Gemini, Anthropic, and OpenAI models for maximum provider diversity. Required providers depend on depth. |
| `mock` | Test-only deterministic profile. Use for smoke tests, not real answers. |

| Depth | Protocol | Typical behavior |
|---|---|---|
| `QUICK` | `quick` | Generate and synthesize only. No review or validation. |
| `BALANCED` | `balanced` | Generate, anonymize, review, score, synthesize, validate. |
| `RIGOROUS` | `rigorous` | Balanced flow plus conditional debate, draft revision, post-debate re-review, second score, validation, and export manifest. |

### Rigorous Protocol Pipeline

```text
GENERATE → ANONYMIZE → REVIEW → SCORE → DEBATE → REVISE → REVIEW_POST_DEBATE → SCORE → SYNTHESIZE → VALIDATE → EXPORT
```

The `REVISE` stage lets each model incorporate debate arguments into a revised draft. The `REVIEW_POST_DEBATE` stage asks reviewers to re-evaluate with debate context, so the second `SCORE` pass operates on genuinely updated evidence. Debate is evidence-triggered by default: if reviewer disagreement is measurable but below the configured threshold, `DEBATE`, `REVISE`, post-debate review, and the second score pass are explicitly skipped. Derive a user protocol from `rigorous` with `DEBATE.force-run: true` when a demonstration must execute every stage.

Fresh Eyes validation is a model assessment, not external fact-checking. The
validator is instructed to recompute material numerical claims and flag claims it
cannot establish. An `approved: true` response is overridden when a required
criterion is missing/invalid, any criterion fails, or `requiresHumanReview` is
true. The UI labels validator confidence and whether the validator is independent
of, correlated with, or identical to the chair.

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
java -jar target/llm-council-2.0.1.jar
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
ollama pull gemma4:12b-it-qat # validator, separate from all answer producers
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

For an in-browser power-user workflow, open
**<http://localhost:8080/config.html>**. The workbench loads the current overlay,
accepts a local YAML file, and offers a valid model example. "Validate and
preview" does not write: it runs the strict parser and semantic validator, then
shows every added, overridden, or removed entity. "Review save" is enabled only
for the exact text that passed validation, and the final confirmation states
that restart is required.

The optional model probe checks whether the configured runtime can call an exact
provider model id. It is deliberately not a quality evaluation and it does not
save anything. For cloud providers it makes one potentially billable call only
after acknowledgement; it never retries. A global cooldown defaults to ten
seconds. Change the operational bounds only when necessary:

```bash
export LLM_COUNCIL_MODEL_PROBE_COOLDOWN_SECONDS=10
export LLM_COUNCIL_MODEL_PROBE_TIMEOUT_SECONDS=20
```

### OCI Generative AI with local Ollama models

Use [`council-user.oci-local.example.yml`](council-user.oci-local.example.yml)
as the starting point for a mixed council. OCI's Chat Completions endpoint uses
the OpenAI request format, so the application deliberately reuses its existing
`openai` adapter; `provider: oci` is not a valid user-configuration value.

The example keeps credentials and the endpoint out of YAML. Set them in the
environment before startup, substituting the region and exact model id shown in
your OCI Generative AI console:

```bash
export SPRING_AI_OPENAI_API_KEY='<oci-generative-ai-api-key>'
export SPRING_AI_OPENAI_BASE_URL='https://inference.generativeai.<region>.oci.oraclecloud.com/20231130/actions/v1'
export SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH='/chat/completions'
export COUNCIL_OPENAI_CHAIR_MODEL='<oci-chat-completions-model-id>'
```

This uses the API-key Chat Completions endpoint supported by the application's
current Spring AI adapter. A browser login or ChatGPT subscription cannot be
reused as an API credential. After copying or merging the overlay, restart the
application, inspect configuration issues, and probe the cloud chair once before
running a council:

```bash
curl 'localhost:8080/api/council/catalog?include=issues,profiles,providers'
```

The example is a candidate topology, not a quality claim. Qualify its local
models first, then run the evaluation harness against the `oci-local` profile.

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
java -jar target/llm-council-2.0.1.jar
```

Then create a session with `profileId: "mock"`.

## Docker Compose Local Testing

The repository includes Docker Compose files for local Mac testing:

| File | Target machine | Default local models |
|---|---|---|
| `docker-compose.m1-32gb.yml` | Apple Silicon M1 class Mac with 32 GB memory | `llama3.1:8b`, `mistral:7b`, `qwen2.5:7b`, `gemma4:12b-it-qat` validator |
| `docker-compose.m1-32gb-app-only.yml` | Apple Silicon M1 app container plus native/separate Ollama | `llama3.1:8b`, `mistral:7b`, `qwen2.5:7b`, `gemma4:12b-it-qat` validator |
| `docker-compose.intel-2019-32gb.yml` | 2019 Intel MacBook Pro with 32 GB memory | `llama3.2:3b`, `qwen2.5:3b`, `qwen2.5:7b`, `gemma4:12b-it-qat` validator |

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
ollama pull gemma4:12b-it-qat # independent local validator
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
    "context": "Optional factual background",
    "contextPurpose": "EVIDENCE",
    "depthMode": "BALANCED",
    "profileId": "mock"
  }'
```

`contextPurpose` is optional and defaults to `EVIDENCE`. Use
`ANALYSIS_SUBJECT` only when the question explicitly asks the council to inspect
the context text itself, such as explaining a prompt-injection sample. A result
also exposes `answerDisplayable`; when required validation rejects, it is
`false` and the top-level `answer` is empty.

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

### Configuration API

The advanced workbench is a client of the same public localhost API:

```bash
curl localhost:8080/api/council/config/schema
curl localhost:8080/api/council/config/export

# One local connectivity call. Cloud providers additionally require
# "acknowledgeCloudCall": true.
curl -X POST localhost:8080/api/council/config/models/probe \
  -H 'Content-Type: application/json' \
  -d '{"provider":"ollama","providerModelId":"llama3.1:8b"}'
```

Provider-call failures return a stable status such as `MODEL_NOT_FOUND`,
`MODEL_TIMEOUT`, or `PROVIDER_UNAVAILABLE` without returning the raw provider
exception. Malformed requests are `400`; calls inside the cooldown are `429`
with `Retry-After`.

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

## Configuring OpenAI Or Claude

The `openai` and `claude` profiles use the official Spring AI provider clients. Configure one or both credentials before starting the service:

```bash
export SPRING_AI_OPENAI_API_KEY="sk-..."
export SPRING_AI_ANTHROPIC_API_KEY="sk-ant-..."
```

Without valid values, the service can still start for local and mock use. Calls to an unconfigured cloud profile fail explicitly rather than silently falling back to mock output. Model IDs can be overridden with the `COUNCIL_OPENAI_*_MODEL` and `COUNCIL_CLAUDE_*_MODEL` variables documented in `application.yml`.

Then call with:

```json
{
  "question": "Review this architecture decision...",
  "depthMode": "BALANCED",
  "profileId": "openai"
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
├── config.html                          advanced YAML validation workbench
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
    ├── config-api.js                    strict configuration/probe API wrapper
    ├── config.js                        validate, preview, save, schema, probe UI
    ├── requirement-form.js              the requirement as editable choices
    ├── dom.js                           node builders
    └── main.js                          app state and orchestration
```

## Repository verification

Run the same repository-level checks used by pull-request CI:

```bash
mvn --batch-mode --no-transfer-progress clean verify
./scripts/verify-repository.sh
```

The script parses every repository YAML document, checks local Markdown and
image targets, and rejects reintroduction of the removed provider configuration.

## License

The repository currently contains the GNU General Public License version 3. The
[LICENSE](LICENSE) file is the legal authority. The future licensing options in
[the licensing decision record](docs/licensing-and-distribution.md) have not been
applied and must not be presented as the current license.

## More Detail

Use the [documentation index](docs/README.md) to choose between current references,
hardware runbooks, threat and readiness documents, and historical design records.
