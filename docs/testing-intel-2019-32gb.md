# Testing On 2019 Intel MacBook Pro With 32 GB Memory

This guide tests the application on a 2019 Intel MacBook Pro with 32 GB memory
using `docker-compose.intel-2019-32gb.yml`.

This machine class is CPU-bound for local LLM inference. Start with mock and
`QUICK` local tests. Run `BALANCED` only after the quick path is reliable.

> **Security:** the compose app binds `0.0.0.0` so Docker port publishing works,
> and the API has no authentication. Run this only on a trusted local network,
> restrict the published port with host/firewall controls, and do not expose
> port 8080 to the internet. Raw prompts and model output are stored in the
> artifact volume.

## Files Used

```text
Dockerfile
docker-compose.intel-2019-32gb.yml
src/main/resources/application.yml
docs/library-flow-guide.md
```

## Hardware Assumptions

- Intel MacBook Pro, 2019 generation.
- 32 GB system memory.
- Docker Desktop installed.
- Docker Desktop resource allocation:
  - Memory: 20 GB to 24 GB.
  - CPU: 6 cores if available.
  - Swap: 4 GB.
  - Disk image: 60 GB or more free space.

Docker-based Ollama on Intel macOS is CPU-only unless you are using a separate
accelerated runtime outside this compose topology. Expect much slower runs than
on hosted models or native accelerated runtimes.

## Model Defaults

The Intel compose file defaults to smaller models:

```text
LLM_COUNCIL_LOCAL_MODEL=llama3.2:3b
LLM_COUNCIL_LOCAL_ALT_MODEL=qwen2.5:3b
LLM_COUNCIL_LOCAL_THIRD_MODEL=qwen2.5:7b
LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.2:3b
LLM_COUNCIL_LOCAL_VALIDATOR_MODEL=gemma4:12b-it-qat
```

The alternate is explicitly declared as family `qwen` in this compose file so
the catalog's diversity diagnostics match the overridden model. The full stack
also pulls the third member required by the rigorous local policy. Expect that
7B model to be slow on CPU; use mock rigorous mode when testing protocol shape.

These defaults are chosen for practical testing on CPU. For higher quality, you
can override them, but expect longer runs:

```bash
export LLM_COUNCIL_LOCAL_MODEL=llama3.1:8b
export LLM_COUNCIL_LOCAL_ALT_MODEL=mistral:7b
export LLM_COUNCIL_LOCAL_ALT_MODEL_FAMILY=mistral
export LLM_COUNCIL_LOCAL_THIRD_MODEL=qwen2.5:7b
export LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.1:8b
export LLM_COUNCIL_LOCAL_VALIDATOR_MODEL=gemma4:12b-it-qat
export LLM_COUNCIL_LOCAL_VALIDATOR_MODEL_FAMILY=gemma
```

## Step 1: Confirm Machine And Docker

From the project root:

```bash
cd /path/to/llm-council
uname -m
docker version
docker compose version
```

Expected architecture:

```text
x86_64
```

Validate compose syntax:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml config >/tmp/llm-council-intel-compose.yml
```

## Step 2: Run Unit Tests Locally With Java 25

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin:$PATH \
mvn test
```

Expected result:

```text
BUILD SUCCESS
Tests run: 952, Failures: 0, Errors: 0, Skipped: 0
```

If Java 25 is not available on the Intel machine, rely on the Docker build path:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml build app
docker image inspect llm-council:java25-intel --format '{{.Architecture}}/{{.Os}}'
```

Expected image architecture:

```text
amd64/linux
```

You do not need to set `BUILDPLATFORM` or `TARGETPLATFORM`. Docker BuildKit
provides those automatically. The Intel compose file declares both the build
output platform and runtime platform as `linux/amd64`.

## Step 3: Start The Docker Stack

```bash
docker compose -f docker-compose.intel-2019-32gb.yml up --build
```

First startup downloads images and model weights. On Intel, this can take a long
time. Wait for:

```text
Started LlmCouncilApplication
```

Confirm service health:

```bash
curl -s http://localhost:8080/actuator/health
```

Expected:

```json
{"status":"UP"}
```

Confirm model downloads:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml exec ollama ollama list
```

## Step 4: Run Mock BALANCED First

The mock profile is the fastest integration test and avoids local model
performance questions.

```bash
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Smoke test the council flow on Intel.",
    "context": "Use deterministic mock models.",
    "depthMode": "BALANCED",
    "profileId": "mock"
  }' | jq -r .sessionId)

curl -s -X POST "http://localhost:8080/api/council/sessions/$SESSION_ID/run" | jq
curl -s "http://localhost:8080/api/council/sessions/$SESSION_ID" | jq
```

Expected:

```text
status=COMPLETED
profileId=mock
policyId=mock-balanced
protocolId=balanced
```

## Step 5: Inspect Mock Events And Artifacts

```bash
curl -s "http://localhost:8080/api/council/sessions/$SESSION_ID/events" | jq
curl -s "http://localhost:8080/api/council/sessions/$SESSION_ID/artifacts" | jq
```

Inspect files:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml exec app \
  find /data/llm-council/runs -maxdepth 4 -type f | sort
```

## Step 6: Run Local QUICK

`QUICK` is the practical local-model test for Intel. It exercises Ollama without
review, scoring, or validation.

```bash
LOCAL_SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Give a concise recommendation on sagas versus two-phase commit.",
    "context": "CPU-bound local test on Intel MacBook Pro.",
    "depthMode": "QUICK",
    "profileId": "local"
  }' | jq -r .sessionId)

time curl -s -X POST "http://localhost:8080/api/council/sessions/$LOCAL_SESSION_ID/run" | jq
```

Expected:

- `policyId` is `local-quick`.
- `protocolId` is `quick`.
- `answer` is non-empty.

If this takes too long, keep using the mock profile for orchestration testing and
use an OpenAI, Claude, or Gemini profile for real model quality.

## Step 7: Run Local BALANCED Only After QUICK Passes

`BALANCED` can take a long time on Intel CPU because it performs multiple model
calls:

- Draft generation.
- Anonymous peer review.
- Scoring.
- Chair synthesis.
- Fresh Eyes validation.

Run:

```bash
BALANCED_SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Compare outbox, CDC, and direct synchronous calls for service integration.",
    "context": "The application is Java Spring Boot with PostgreSQL.",
    "depthMode": "BALANCED",
    "profileId": "local"
  }' | jq -r .sessionId)

time curl -s -X POST "http://localhost:8080/api/council/sessions/$BALANCED_SESSION_ID/run" | jq
```

Watch logs:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml logs -f app
docker compose -f docker-compose.intel-2019-32gb.yml logs -f ollama
```

Expected if successful:

- `policyId` is `local-balanced`.
- `scoreSummary` is present.
- `validation` is present.

The review parser recovers the local-model shapes observed in practice:
multiple JSON envelopes, compact criterion objects, fractional scores, and
valid siblings of a malformed review. The timeline reports exact per-reviewer
coverage. An otherwise valid response that omits a required draft receives one
targeted recovery call. Evidence still missing afterward degrades the run to
`PARTIAL`; it is never counted toward quorum or hidden behind a clean completion.

In `RIGOROUS`, debate is conditional even after complete review coverage. A
measurable disagreement value below the configured trigger causes debate and
its dependent stages to be explicitly skipped. This is expected. Use a derived
protocol with `DEBATE.force-run: true` only when the goal is to demonstrate
every stage; it is substantially slower on this CPU-only setup.

## Step 8: Prefer A Cloud Profile For Real Quality On Intel

For a 2019 Intel laptop, local CPU models are useful for smoke tests and privacy
checks. For real answer quality and acceptable latency, use `openai`, `claude`,
or `gemini`.

Stop stack:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml down
```

Provide runtime credentials:

```bash
export SPRING_AI_OPENAI_API_KEY="your-runtime-api-key"
# Or use Claude instead:
# export SPRING_AI_ANTHROPIC_API_KEY="your-anthropic-api-key"
```

Restart:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml up --build
```

Run OpenAI:

```bash
OPENAI_SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Review this migration plan for risks and missing controls.",
    "context": "Prefer concrete engineering tradeoffs.",
    "depthMode": "BALANCED",
    "profileId": "openai"
  }' | jq -r .sessionId)

curl -s -X POST "http://localhost:8080/api/council/sessions/$OPENAI_SESSION_ID/run" | jq
```

## Step 9: Common Intel Failures

### Compose Pulls The Wrong Architecture

Check image architecture:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml images
```

The compose file sets:

```yaml
platform: linux/amd64
```

If you edit the file, keep that setting on Intel.

### Local Model Calls Timeout Or Appear Hung

Intel CPU local inference can look idle for a long time.

Check:

```bash
docker stats
docker compose -f docker-compose.intel-2019-32gb.yml logs -f ollama
```

Mitigations:

- Use `QUICK` instead of `BALANCED`.
- Keep `LLM_COUNCIL_LOCAL_OUTPUT_TOKENS` low.
- Keep the shipped `SPRING_AI_OLLAMA_NUM_CTX=8192` for rigorous prompt fit. If
  memory pressure forces a smaller value, expect prompt truncation warnings and
  reduce output-token budgets or use `QUICK` rather than assuming 3072 is enough.
- Use smaller models.
- Use `openai`, `claude`, or `gemini` for real cloud runs.

### App Is Healthy But Local Run Fails

Check model names:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml exec ollama ollama list
docker compose -f docker-compose.intel-2019-32gb.yml logs ollama-pull
```

Check app environment:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml exec app env | sort | grep -E 'OLLAMA|LLM_COUNCIL'
```

Expected:

```text
SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
LLM_COUNCIL_LOCAL_MODEL=llama3.2:3b
LLM_COUNCIL_LOCAL_ALT_MODEL=qwen2.5:3b
LLM_COUNCIL_LOCAL_THIRD_MODEL=qwen2.5:7b
LLM_COUNCIL_LOCAL_ALT_MODEL_FAMILY=qwen
LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.2:3b
LLM_COUNCIL_LOCAL_VALIDATOR_MODEL=gemma4:12b-it-qat
```

### Port Conflict

```bash
lsof -i :8080
lsof -i :11434
```

Stop the conflicting process or change the host-side port mapping.

### Memory Pressure

If Docker Desktop swaps heavily or containers exit, lower model size and context:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml down
export LLM_COUNCIL_LOCAL_MODEL=llama3.2:3b
export LLM_COUNCIL_LOCAL_ALT_MODEL=qwen2.5:3b
export LLM_COUNCIL_LOCAL_ALT_MODEL_FAMILY=qwen
export LLM_COUNCIL_LOCAL_THIRD_MODEL=qwen2.5:7b
export LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.2:3b
export LLM_COUNCIL_LOCAL_VALIDATOR_MODEL=gemma4:12b-it-qat
export LLM_COUNCIL_LOCAL_VALIDATOR_MODEL_FAMILY=gemma
docker compose -f docker-compose.intel-2019-32gb.yml up --build
```

## Step 10: Teardown

Stop containers but keep volumes:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml down
```

Delete containers and volumes:

```bash
docker compose -f docker-compose.intel-2019-32gb.yml down -v
```

List volumes:

```bash
docker volume ls | grep llm-council
```

## Pass Criteria

A successful Intel validation means:

- Compose config validates.
- App container builds.
- `GET /actuator/health` returns `UP`.
- Mock `BALANCED` run completes.
- Local `QUICK` run completes or fails with a clear model/runtime error.
- Events and artifacts endpoints are usable.
- Local `BALANCED` is attempted only after `QUICK` is stable.
