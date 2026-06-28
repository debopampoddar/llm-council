# Testing On M1 Mac With 32 GB Memory

This guide tests the application on an Apple Silicon Mac with 32 GB memory.

There are two supported local paths:

- Full Docker stack: `docker-compose.m1-32gb.yml` runs Ollama, pulls models, and
  runs the LLM Council app.
- App-only stack: `docker-compose.m1-32gb-app-only.yml` runs only the LLM Council
  app and connects to native or separately managed Ollama on the Mac host.

The compose stack starts:

- `ollama`: local Ollama runtime in Docker.
- `ollama-pull`: one-shot model download job.
- `app`: Java 25 Spring Boot LLM Council service.

Important performance note: Ollama inside Docker Desktop on macOS is a
reproducible CPU-oriented setup. Native macOS Ollama can be faster on Apple
Silicon because it can use Apple platform acceleration. Use the compose setup to
validate the full container topology. Use native Ollama for day-to-day local
performance if Docker inference is too slow.

## Files Used

```text
Dockerfile
docker-compose.m1-32gb.yml
docker-compose.m1-32gb-app-only.yml
src/main/resources/application.yml
docs/enhancement-implementation-sequences.md
```

## Hardware Assumptions

- Apple Silicon M1 class machine.
- 32 GB system memory.
- Docker Desktop installed.
- Docker Desktop resource allocation:
  - Memory: 24 GB minimum recommended.
  - CPU: 8 cores if available.
  - Swap: 2 GB or more.
  - Disk image: 60 GB or more free space for model downloads and build cache.

## Model Defaults

The M1 compose file defaults to:

```text
LLM_COUNCIL_LOCAL_MODEL=llama3.1:8b
LLM_COUNCIL_LOCAL_ALT_MODEL=mistral:7b
LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.1:8b
```

If Docker inference is too slow, start with smaller models:

```bash
export LLM_COUNCIL_LOCAL_MODEL=llama3.2:3b
export LLM_COUNCIL_LOCAL_ALT_MODEL=qwen2.5:3b
export LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.2:3b
```

## Step 1: Confirm Machine And Docker

From the project root:

```bash
cd "/Users/depoddar/Documents/docs/personal/LLM Council/code/llm-council-full"
uname -m
docker version
docker compose version
```

Expected architecture:

```text
arm64
```

Validate the compose file:

```bash
docker compose -f docker-compose.m1-32gb.yml config >/tmp/llm-council-m1-compose.yml
```

If this fails, fix YAML before starting containers.

## Step 2: Run Unit Tests Locally With Java 25

This validates the Java code without waiting for model downloads:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
PATH=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home/bin:$PATH \
mvn test
```

Expected result:

```text
BUILD SUCCESS
```

If Java 25 is not installed, install it or run only the Docker build path.

## Step 3A: Recommended Path - Native Ollama Plus App-Only Compose

This avoids Docker Desktop's Ollama healthcheck path and usually performs better
on Apple Silicon.

Start native Ollama on the Mac host. If you use the Ollama desktop app, make
sure it is running. If you use the CLI:

```bash
ollama serve
```

In another terminal, pull the models:

```bash
ollama pull "${LLM_COUNCIL_LOCAL_MODEL:-llama3.1:8b}"
ollama pull "${LLM_COUNCIL_LOCAL_ALT_MODEL:-mistral:7b}"
```

Confirm host Ollama is reachable:

```bash
curl -s http://localhost:11434/api/tags
```

Start only the LLM Council app in Docker:

```bash
unset SPRING_AI_OLLAMA_BASE_URL
docker compose -f docker-compose.m1-32gb-app-only.yml up --build
```

The app container connects to host Ollama through:

```text
SPRING_AI_OLLAMA_BASE_URL=http://host.rancher-desktop.internal:11434
LLM_COUNCIL_OLLAMA_LOG_LEVEL=DEBUG
NO_PROXY=localhost,127.0.0.1,host.docker.internal,host.rancher-desktop.internal,host.lima.internal,ollama
```

This default targets Rancher Desktop/Lima. If you switch back to Docker Desktop,
override with:

```bash
export SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434
docker compose -f docker-compose.m1-32gb-app-only.yml up --build
```

Do not set `SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434` for the app-only
Docker flow. Inside the app container, `localhost` means the app container
itself, not the Mac host where Ollama is running.

Confirm the app is healthy:

```bash
curl -s http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

If you manage Ollama in another container or remote machine, set the endpoint
explicitly before starting the app:

```bash
export SPRING_AI_OLLAMA_BASE_URL="http://your-ollama-host:11434"
docker compose -f docker-compose.m1-32gb-app-only.yml up --build
```

Confirm the application image architecture:

```bash
APP_IMAGE=$(docker compose -f docker-compose.m1-32gb-app-only.yml images -q app)
docker image inspect "$APP_IMAGE" --format '{{.Architecture}}/{{.Os}}'
```

For app-only compose, any architecture your Docker engine can run is acceptable.
On native Apple Silicon Docker Desktop this is usually `arm64/linux`; on an
amd64 Docker VM it may be `amd64/linux`. The local model runtime is outside this
container, so app image architecture does not affect local LLM acceleration.

## Step 3B: Full Docker Stack

First run can take a long time because Docker downloads:

- Java and Maven build images.
- Ollama image.
- Local model weights.

Start the stack:

```bash
docker compose -f docker-compose.m1-32gb.yml up --build
```

Wait until the `app` service logs show:

```text
Started LlmCouncilApplication
```

In another terminal, confirm health:

```bash
curl -s http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

Confirm Ollama has models:

```bash
docker compose -f docker-compose.m1-32gb.yml exec ollama ollama list
```

Expected output includes the configured model names.

Confirm the application image is ARM64:

```bash
APP_IMAGE=$(docker compose -f docker-compose.m1-32gb.yml images -q app)
docker image inspect "$APP_IMAGE" --format '{{.Architecture}}/{{.Os}}'
```

Expected:

```text
arm64/linux
```

If the full stack fails with:

```text
container for service "ollama" is unhealthy
```

use Step 3A instead. It is valid to run Ollama separately and point the LLM
Council app at it.

## Step 4: Run A Fast Mock Smoke Test

The mock profile does not call real models. It proves the API, orchestration,
events, artifact writing, and response mapping are functioning.

Create a session:

```bash
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Smoke test the council flow.",
    "context": "Use deterministic mock models.",
    "depthMode": "BALANCED",
    "profileId": "mock"
  }' | jq -r .sessionId)

echo "$SESSION_ID"
```

Run it:

```bash
curl -s -X POST "http://localhost:8080/api/council/sessions/$SESSION_ID/run" | jq
```

Read final session state:

```bash
curl -s "http://localhost:8080/api/council/sessions/$SESSION_ID" | jq
```

Expected fields:

```json
{
  "status": "COMPLETED",
  "profileId": "mock",
  "depthMode": "BALANCED",
  "policyId": "mock-balanced",
  "protocolId": "balanced"
}
```

If `jq` is not installed, run the same commands without `| jq`.

## Step 5: Inspect Events And Artifacts

Events:

```bash
curl -s "http://localhost:8080/api/council/sessions/$SESSION_ID/events" | jq
```

Artifacts:

```bash
curl -s "http://localhost:8080/api/council/sessions/$SESSION_ID/artifacts" | jq
```

Inspect artifacts inside the app container:

```bash
docker compose -f docker-compose.m1-32gb.yml exec app \
  find /data/llm-council/runs -maxdepth 4 -type f | sort
```

Expected balanced artifacts include:

```text
normalized/drafts-generation.json
normalized/anonymized-drafts.json
normalized/reviews.json
normalized/scores-initial.json
private/anonymization-map.json
final/answer.md
final/validation.json
```

## Step 6: Run A Local QUICK Test

Run `QUICK` first. It uses local Ollama models but avoids review and validation.

```bash
LOCAL_SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Should a Java service use sagas or two-phase commit for cross-service workflows?",
    "context": "Assume independent services, separate databases, and customer-facing latency concerns.",
    "depthMode": "QUICK",
    "profileId": "local"
  }' | jq -r .sessionId)

curl -s -X POST "http://localhost:8080/api/council/sessions/$LOCAL_SESSION_ID/run" | jq
```

Expected:

- `policyId` is `local-quick`.
- `protocolId` is `quick`.
- `draftCount` is at least `1`.
- `answer` is non-empty.

If the run fails with `Draft quorum not met: 0/1`, the model call failed before
any draft was produced. The `excludedModels` entry now includes the direct
Ollama URL, provider model, HTTP status, and response body where available.
Check the app's effective environment first:

```bash
docker compose -f docker-compose.m1-32gb-app-only.yml exec app env \
  | grep -E 'SPRING_AI_OLLAMA_BASE_URL|LLM_COUNCIL_LOCAL_MODEL|LLM_COUNCIL_LOCAL_ALT_MODEL|LLM_COUNCIL_LOCAL_CHAIR_MODEL'
```

Expected for app-only M1 testing:

```text
SPRING_AI_OLLAMA_BASE_URL=http://host.rancher-desktop.internal:11434
LLM_COUNCIL_LOCAL_MODEL=llama3.1:8b
LLM_COUNCIL_LOCAL_ALT_MODEL=mistral:7b
LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.1:8b
```

Then confirm host Ollama has the same tagged model names:

```bash
ollama list
curl -s http://localhost:11434/api/tags
```

Do not use `SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434` in Docker app-only
mode. Inside the app container, that points back to the app container, not host
Ollama.

For the full Docker stack, confirm model names in the Ollama service:

```bash
docker compose -f docker-compose.m1-32gb.yml exec ollama ollama list
docker compose -f docker-compose.m1-32gb.yml logs ollama-pull
```

## Step 7: Run A Local BALANCED Test

This test exercises anonymization, review, scoring, synthesis, and validation.
It is much slower than `QUICK`.

```bash
BALANCED_SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Compare event sourcing, audit tables, and append-only logs for regulated transaction history.",
    "context": "The system is Java, Spring Boot, PostgreSQL, and must support audits.",
    "depthMode": "BALANCED",
    "profileId": "local"
  }' | jq -r .sessionId)

curl -s -X POST "http://localhost:8080/api/council/sessions/$BALANCED_SESSION_ID/run" | jq
```

Watch logs while it runs:

```bash
docker compose -f docker-compose.m1-32gb.yml logs -f app
```

Expected:

- `policyId` is `local-balanced`.
- `protocolId` is `balanced`.
- `scoreSummary` is present.
- `validation` is present.

If local models fail to return valid JSON during review, try the mock profile to
confirm orchestration still works, then use smaller or more instruction-following
models.

## Step 8: Optional OCI Or Hybrid Test

Do not use Codex `~/.codex/auth.json` tokens. The service needs runtime model
credentials.

Stop the stack:

```bash
docker compose -f docker-compose.m1-32gb.yml down
```

Start with OCI/OpenAI-compatible environment variables:

```bash
export SPRING_AI_OPENAI_API_KEY="your-runtime-api-key"
export SPRING_AI_OPENAI_BASE_URL="https://your-openai-compatible-endpoint"
export SPRING_AI_OPENAI_CHAT_COMPLETIONS_PATH="/v1/chat/completions"
export OCA_LLM_MODEL="your-model"
export OCA_LLM_REVIEW_MODEL="your-review-model"

docker compose -f docker-compose.m1-32gb.yml up --build
```

Create an OCI session:

```bash
OCI_SESSION_ID=$(curl -s -X POST http://localhost:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Review this architecture decision for operational risk.",
    "context": "Use a balanced council.",
    "depthMode": "BALANCED",
    "profileId": "oci"
  }' | jq -r .sessionId)

curl -s -X POST "http://localhost:8080/api/council/sessions/$OCI_SESSION_ID/run" | jq
```

## Step 9: Common Failures

### App Cannot Reach Ollama

Symptoms:

```text
Connection refused
Ollama HTTP 404
Ollama call failed ... ConnectException
Ollama call failed ... ClosedChannelException
```

Checks:

```bash
docker compose -f docker-compose.m1-32gb.yml ps
docker compose -f docker-compose.m1-32gb.yml logs ollama
docker compose -f docker-compose.m1-32gb.yml exec app env | grep SPRING_AI_OLLAMA_BASE_URL
```

Expected in compose:

```text
SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
```

For app-only compose, expected startup logs include the resolved address and
proxy settings used by the direct Ollama adapter:

```text
Ollama client local-llama3 configured for http://host.rancher-desktop.internal:11434/api/chat with direct networking
```

If `ClosedChannelException` appears, rebuild the app image after pulling the
latest source changes. The current app-only compose file enables adapter DEBUG
logging and sets local `NO_PROXY` values. The direct Ollama adapter also opens
the connection with `Proxy.NO_PROXY`, so any remaining failure is usually a
container-to-host networking issue rather than a model-name issue.

### First Run Is Very Slow

First run loads model weights. Check Ollama logs:

```bash
docker compose -f docker-compose.m1-32gb.yml logs -f ollama
```

If it remains too slow, restart with smaller models:

```bash
docker compose -f docker-compose.m1-32gb.yml down
export LLM_COUNCIL_LOCAL_MODEL=llama3.2:3b
export LLM_COUNCIL_LOCAL_ALT_MODEL=qwen2.5:3b
export LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.2:3b
docker compose -f docker-compose.m1-32gb.yml up --build
```

### Port Conflict

If `8080` or `11434` is already used:

```bash
lsof -i :8080
lsof -i :11434
```

Either stop the conflicting process or edit the host-side port mapping in the
compose file.

### Memory Pressure

Symptoms:

- Docker Desktop becomes unresponsive.
- Ollama exits.
- App logs show out-of-memory or timeout-like failures.

Mitigation:

```bash
docker compose -f docker-compose.m1-32gb.yml down
export LLM_COUNCIL_LOCAL_MODEL=llama3.2:3b
export LLM_COUNCIL_LOCAL_ALT_MODEL=qwen2.5:3b
export LLM_COUNCIL_LOCAL_CHAIR_MODEL=llama3.2:3b
docker compose -f docker-compose.m1-32gb.yml up --build
```

### Maven Build Crashes With `SIGSEGV`, `SIGILL`, Or `linux-amd64`

Symptom during `docker compose -f docker-compose.m1-32gb.yml up --build`:

```text
SIGSEGV
Java VM: OpenJDK 64-Bit Server VM ... linux-amd64
RUN mvn -q -DskipTests package
```

This means the Docker build JVM crashed while Maven was compiling or packaging.
On Apple Silicon this is most commonly seen when Docker runs an amd64 Java 25
build JVM through emulation. The Dockerfile now runs Maven on Docker's
executable build platform, disables Maven tiered compilation to avoid the C1
compiler path, and keeps the final runtime image on the Compose target platform:

```dockerfile
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-25 AS build
ARG MAVEN_OPTS="-XX:-TieredCompilation -XX:+UseSerialGC"
ENV MAVEN_OPTS=$MAVEN_OPTS
FROM --platform=$TARGETPLATFORM eclipse-temurin:25-jre
```

The build JVM may still report `linux-amd64` if your Docker builder itself runs
that way. That is acceptable as long as the Maven crash is gone. The final app
image must report `arm64/linux`.

Rebuild the app image after pulling the updated Dockerfile:

```bash
docker compose -f docker-compose.m1-32gb.yml build --no-cache app
APP_IMAGE=$(docker compose -f docker-compose.m1-32gb.yml images -q app)
docker image inspect "$APP_IMAGE" --format '{{.Architecture}}/{{.Os}}'
docker compose -f docker-compose.m1-32gb.yml up
```

You do not need to set `BUILDPLATFORM` or `TARGETPLATFORM`. Docker BuildKit
provides those automatically. The M1 compose file also declares:

```yaml
services:
  app:
    build:
      platforms:
        - linux/arm64
    platform: linux/arm64
```

If `up --build` fails with the older named-image error:

```text
image with reference llm-council:java25-m1 was found but does not match the specified platform:
wanted linux/arm64, actual: linux/amd64
```

your local Docker image tag is stale and points to an older amd64 image, or you
are running an older compose file that still has `image: llm-council:java25-m1`.
The current M1 compose files do not pin that image tag. Remove the stale tag,
remove old containers, and rebuild:

```bash
docker compose -f docker-compose.m1-32gb.yml down
docker compose -f docker-compose.m1-32gb-app-only.yml down
docker image rm llm-council:java25-m1 2>/dev/null || true
unset DOCKER_DEFAULT_PLATFORM
docker compose -f docker-compose.m1-32gb.yml build --no-cache --pull app
APP_IMAGE=$(docker compose -f docker-compose.m1-32gb.yml images -q app)
docker image inspect "$APP_IMAGE" --format '{{.Architecture}}/{{.Os}}'
docker compose -f docker-compose.m1-32gb.yml up
```

The inspect command must print:

```text
arm64/linux
```

If Maven still crashes, check for a global Docker default platform override:

```bash
echo "$DOCKER_DEFAULT_PLATFORM"
docker buildx ls
```

For M1 local testing, unset an amd64 override:

```bash
unset DOCKER_DEFAULT_PLATFORM
docker compose -f docker-compose.m1-32gb.yml build --no-cache app
```

As a last resort for Docker Desktop/JDK combinations that still crash, force
Maven's JVM fully into interpreter mode for the build:

```bash
docker compose -f docker-compose.m1-32gb.yml build --no-cache app \
  --build-arg MAVEN_OPTS="-Xint -XX:+UseSerialGC"
```

This is slower, but it bypasses JIT compilation during Maven build only. The
runtime container still uses normal JVM execution.

### App Container Fails With `exec /usr/bin/sh: exec format error`

Symptom:

```text
llm-council-m1-32gb-app-only-app-1 | exec /usr/bin/sh: exec format error
```

This means Docker selected an app image architecture that your Docker engine
cannot execute. For the app-only flow, do not force `linux/arm64`; the Java app
can run as either ARM64 or AMD64 because Ollama is running outside the app
container.

Use the current app-only compose file, remove old containers/images, and rebuild:

```bash
docker compose -f docker-compose.m1-32gb-app-only.yml down --remove-orphans --rmi local
docker image rm llm-council:java25-m1 2>/dev/null || true
unset DOCKER_DEFAULT_PLATFORM
unset SPRING_AI_OLLAMA_BASE_URL
docker compose -f docker-compose.m1-32gb-app-only.yml build --no-cache --pull app
docker compose -f docker-compose.m1-32gb-app-only.yml up
```

After startup, verify the app can reach host Ollama using the default compose
setting:

```text
SPRING_AI_OLLAMA_BASE_URL=http://host.rancher-desktop.internal:11434
```

## Step 10: Teardown

Stop containers but keep downloaded models and artifacts:

```bash
docker compose -f docker-compose.m1-32gb.yml down
```

Delete containers and volumes:

```bash
docker compose -f docker-compose.m1-32gb.yml down -v
```

List volumes before deleting:

```bash
docker volume ls | grep llm-council
```

## Pass Criteria

A successful M1 validation means:

- `mvn test` passes locally or the Docker image builds successfully.
- `GET /actuator/health` returns `UP`.
- Mock `BALANCED` run completes.
- Local `QUICK` run completes.
- Events endpoint returns protocol and stage events.
- Artifacts endpoint lists generated files.
- Docker teardown works cleanly.
