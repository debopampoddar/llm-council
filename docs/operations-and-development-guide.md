# Operations And Development Guide

This is the practical reference for running, configuring, testing, observing,
and extending LLM Council. New users should begin with the
[root README](../README.md); use this guide after the first local `QUICK` run.

## Runtime Requirements

- Java 25.
- Maven 3.9 or newer.
- Optional local runtime: Ollama, started by the macOS application, another
  service manager, or `ollama serve`.
- Optional cloud providers, activated by environment credentials.

The project deliberately compiles against Java 25. If the shell defaults to a
different JDK, point Maven at Java 25 explicitly:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin:$PATH \
mvn test
```

## Provider Credentials

Credentials belong in the environment, never in the repository, a command
history, or `council-user.yml`.

| Provider | Activation |
|---|---|
| Ollama | A reachable local runtime; no credential is required. |
| OpenAI | `SPRING_AI_OPENAI_API_KEY` |
| Anthropic | `SPRING_AI_ANTHROPIC_API_KEY` |
| Gemini / Vertex AI | `GOOGLE_CLOUD_PROJECT` plus Application Default Credentials or `GOOGLE_APPLICATION_CREDENTIALS` |

The configuration overlay structurally rejects credential field names and
credential-shaped values. If a real key has been written to a file, rotate it;
do not try to keep using it there.

For a cloud walkthrough, including preflight and cost guardrails, use the
[cloud quick start](cloud-quickstart.md). Calls to an unconfigured provider fail
explicitly; the application does not silently fall back to mock output.

## Profiles And Depth

Public callers choose a configured `profileId` and a `depthMode`; they cannot
send a raw protocol ID.

| Profile | Intended use |
|---|---|
| `local` | Ollama-only local council. |
| `openai` | OpenAI-only council; requires an OpenAI credential. |
| `claude` | Anthropic-only council; requires an Anthropic credential. |
| `hybrid-openai` | Local Ollama drafting and validation with an OpenAI chair; requires an OpenAI credential. |
| `hybrid-claude` | Local Ollama drafting and validation with a Claude chair; requires an Anthropic credential. |
| `gemini` | Gemini / Vertex AI council; requires Google Cloud configuration. |
| `multi-cloud` | Advanced profile spanning Ollama, Gemini, Anthropic, and OpenAI; it requires Gemini and the providers named by the selected depth. |
| `mock` | Fabricated test-only output for smoke tests; never a real answer. |

| Depth | Behaviour |
|---|---|
| `QUICK` | Generate and synthesise. No review or validation. |
| `BALANCED` | Generate, anonymise, review, score, synthesise, validate. |
| `RIGOROUS` | `BALANCED` plus evidence-triggered debate, revision, post-debate review, second scoring, validation, and export metadata. |

The full stage semantics, roles, scoring, and failure behavior are described in
the [library flow guide](library-flow-guide.md).

Hybrid profiles send the synthesis prompt and the local drafts it contains to
their named cloud chair. Their preflight blocks a missing OpenAI or Anthropic
credential before a run starts; it does not make a billable endpoint call.

## Safe Configuration

Use the Guided setup page at `http://127.0.0.1:8080/setup.html` for a
reviewable proposal based on the models installed on the machine and providers
that are actually configured. The model can express closed-choice intent; Java
builds the configuration. Nothing is written until confirmation.

For a YAML overlay, create the user-owned file outside the checkout:

```bash
cp council-user.example.yml ~/.llm-council/council-user.yml
```

The overlay merges over the shipped configuration. It can tune model bindings,
policies, and profiles within validated bounds, but it cannot add a provider,
reorder/remove protocol stages, or seat test-only mock models in a real council.

The Advanced configuration workbench at `http://127.0.0.1:8080/config.html`
uses the same strict parser and semantic validator. Validate and preview does
not write. A save is locked to the exact validated text, requires confirmation,
is atomic, and takes effect after restart.

## Context Window And Local Memory

The local `RIGOROUS` council carries drafts, reviews, and debate evidence into
the chair prompt. The default `SPRING_AI_OLLAMA_NUM_CTX=16384` is sized for that
case. Reducing it is permitted, but omitted evidence is marked in the prompt and
reported as a warning.

Larger context windows consume more KV cache. On a constrained machine, reduce
the context window or output-token limits and use fewer members so the chair can
still see the evidence that matters. The detailed local-machine trade-offs are
in the [M1 32 GB runbook](testing-m1-32gb.md) and
[Intel 32 GB runbook](testing-intel-2019-32gb.md).

## Observability

Actuator exposes a bounded metrics catalog at `GET /actuator/metrics`. Useful
meters include:

- `llm.council.model.calls` and `llm.council.model.duration`, tagged by the
  configured model, provider, stage, outcome, and stable failure category;
- `llm.council.model.tokens`, split by input/output where a provider reports it;
- `llm.council.model.retries`;
- `llm.council.stage.duration`;
- `llm.council.runs.active` and `llm.council.runs.rejected`.

For example:

```bash
curl http://127.0.0.1:8080/actuator/metrics/llm.council.model.calls
```

Metrics intentionally do not tag session IDs, prompts, responses, or exception
messages. A green build and a healthy metric endpoint prove implementation
behavior and availability—not answer quality.

## Build, Test, And Repository Checks

Run unit tests:

```bash
mvn test
```

Run the same repository-level checks used by pull-request CI:

```bash
mvn --batch-mode --no-transfer-progress clean verify
./scripts/verify-repository.sh
```

The verification script parses repository YAML, checks local Markdown and image
targets, and guards against removed provider configuration. It does not make
live provider calls or qualify model quality.

## Docker And Hardware Testing

The repository has dedicated Compose files and runbooks for local Mac testing.
Use the runbook that matches the machine instead of copying a random Compose
command:

| Environment | Guide |
|---|---|
| Apple Silicon, 32 GB | [M1 32 GB runbook](testing-m1-32gb.md) |
| 2019 Intel MacBook Pro, 32 GB | [Intel 32 GB runbook](testing-intel-2019-32gb.md) |

The full-stack Compose files run both Ollama and the Java service. The app-only
variant expects a native or separately managed Ollama runtime. The runbooks
document the matching base URLs, model tags, validation steps, and teardown.

## Package Layout And Extension Points

```text
com.debopam.llmcouncil.advisor          requirement extraction and configuration proposals
com.debopam.llmcouncil.api              REST controllers and DTOs
com.debopam.llmcouncil.application      service, policy resolution, event publishing
com.debopam.llmcouncil.chat             chats, turns, asynchronous execution, SSE
com.debopam.llmcouncil.config           binding, validation, registry setup
com.debopam.llmcouncil.domain           sessions, status, depth, event records
com.debopam.llmcouncil.model            profiles, policies, provider clients, retries
com.debopam.llmcouncil.orchestration    protocol stages, prompts, parsing, scoring, artifacts
com.debopam.llmcouncil.persistence      in-memory/JDBC stores, retention, migrations, artifacts
src/main/resources/static               browser UI with no Node build step
```

The [library flow guide](library-flow-guide.md#extension-points) explains how
the extension boundaries fit together. Preserve the central safety rule: models
produce untrusted text and closed-choice intent; application code owns policy,
authorization boundaries, validation, and side effects.

## Distribution And License

The project currently publishes an executable Spring Boot jar. It is not a thin
library jar intended for use as a normal Maven dependency. GitHub Packages
publishing uses the release workflow and the automatic `GITHUB_TOKEN`; a manual
publish requires a `write:packages` token in the developer's external Maven
settings, never in this repository.

The current license is GNU GPL version 3. The repository's
[LICENSE](../LICENSE) is authoritative. The
[licensing and distribution record](licensing-and-distribution.md) discusses
future options; it does not change the current license.
