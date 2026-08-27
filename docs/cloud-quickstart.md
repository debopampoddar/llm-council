# Cloud Quick Start: OpenAI and Claude

This tutorial starts LLM Council on your own computer with either OpenAI or
Anthropic Claude. It is deliberately a **local, low-cost first run**: use the
`QUICK` depth before trying the larger BALANCED or RIGOROUS protocols.

The application binds to loopback by default. It is not an internet-facing or
multi-tenant deployment.

## 1. Build the utility

Requirements: Java 25 and Maven 3.9 or later.

```bash
mvn clean verify
```

This runs the hermetic test suite and produces:

```text
target/llm-council-2.0.0.jar
```

## 2. Configure a provider

Create API keys in the provider consoles and add only the values you intend to
use. Never put a key in YAML, a command history that will be shared, a commit,
or a screenshot.

### OpenAI: economical first run

```bash
export SPRING_AI_OPENAI_API_KEY='your-openai-api-key'
export COUNCIL_OPENAI_MODEL='gpt-5.6-luna'
export COUNCIL_OPENAI_CHAIR_MODEL='gpt-5.6-luna'
```

`gpt-5.6-luna` is a cost-oriented model. The application uses the GPT-5
Chat-Completions-compatible output limit and provider-default sampling settings.

### Claude: economical first run

```bash
export SPRING_AI_ANTHROPIC_API_KEY='your-anthropic-api-key'
export COUNCIL_CLAUDE_MEMBER_MODEL='claude-sonnet-5'
export COUNCIL_CLAUDE_CHAIR_MODEL='claude-sonnet-5'
```

Claude Sonnet 5 does not accept non-default sampling settings. The application
configures Spring AI's provider default accordingly.

You may configure both providers in the same terminal. Keep the API keys out of
your shell profile if other users can read it; export them for the session and
close the terminal when finished.

## 3. Start the service

```bash
java -jar target/llm-council-2.0.0.jar
```

Open <http://127.0.0.1:8080>. Select `openai` or `claude`, select `QUICK`, and
ask a short, non-sensitive question.

`QUICK` is the right first step because it uses the smallest configured
protocol. The same-provider chair and member are intentionally correlated in
this onboarding configuration; it demonstrates the flow, not independent
validation.

## 4. Verify access before a council run

The configuration probe makes one bounded, billable provider call. Run it once
per provider after changing a key or model ID.

```bash
curl --silent --show-error --fail \
  --request POST http://127.0.0.1:8080/api/council/config/models/probe \
  --header 'Content-Type: application/json' \
  --data '{"provider":"openai","providerModelId":"gpt-5.6-luna","acknowledgeCloudCall":true}'
```

```bash
curl --silent --show-error --fail \
  --request POST http://127.0.0.1:8080/api/council/config/models/probe \
  --header 'Content-Type: application/json' \
  --data '{"provider":"anthropic","providerModelId":"claude-sonnet-5","acknowledgeCloudCall":true}'
```

A successful result has `"reachable":true` and `"status":"OK"`. A request
inside the short probe cooldown returns `429`; wait for the `Retry-After`
interval before retrying.

## 5. Run through the REST API (optional)

Create a session using the provider you verified:

```bash
curl --silent --show-error --fail \
  --request POST http://127.0.0.1:8080/api/council/sessions \
  --header 'Content-Type: application/json' \
  --data '{
    "question":"Give three risks and mitigations for introducing a new database migration.",
    "depthMode":"QUICK",
    "profileId":"openai"
  }'
```

Copy `sessionId` from that response, then run it:

```bash
curl --silent --show-error --fail \
  --request POST http://127.0.0.1:8080/api/council/sessions/SESSION_ID/run
```

The response contains the final answer, stage outcomes, warnings, usage, and
latency. Artifacts are stored locally under `~/.llm-council/runs/` unless you
override `LLM_COUNCIL_ARTIFACT_PATH`.

Every stage honours the model profile's timeout. If a provider does not return
before that deadline, the run records a typed timeout rather than remaining
stuck. Likewise, a blank provider response is rejected as invalid output rather
than being counted as a completed draft. For a custom reasoning-model profile,
increase its output-token budget and rerun if it consistently returns blank
output.

## 6. Cost and safety guardrails

- Start with `QUICK`; BALANCED and RIGOROUS make multiple provider calls.
- Probe only after configuration changes; it is intentionally billable.
- Do not provide secrets, customer data, or regulated data in prompts unless
  your own provider, legal, and privacy controls permit it.
- The service is local-only by design. Do not expose port 8080 publicly without
  adding authentication, authorization, ownership isolation, and deployment
  controls.
- A successful run proves connectivity and workflow execution. It does not
  prove that a multi-model council is more accurate than a direct model call.

## Next steps

Use the [showcase and blog guide](showcase-and-blog-guide.md) for a five-minute
demo and evidence-safe tutorial outline. Use the [configuration workbench]
(../README.md#configuration-api) to inspect or safely customize model profiles
without putting credentials in configuration files.
