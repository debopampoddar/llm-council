# LLM Council Production Readiness Plan

Status: current as of 2026-08-17. This is the authoritative forward-looking
plan. The longer [implementation guide](production-readiness-implementation-guide.md)
contains historical design sketches; its status matrix, not the old snippets,
determines what is still open.

## Current baseline

The application already provides:

- Java 25, Spring Boot, Spring AI, direct Ollama, OpenAI, Anthropic,
  and Gemini/Vertex integrations;
- configuration-owned profiles, policies, and protocols with fail-fast built-in
  validation and fail-soft user overlays;
- provider/model health preflight and explicit failure categories;
- retries for transient failures and bounded model-call timeouts;
- QUICK, BALANCED, and RIGOROUS orchestration with anonymized review, quorum,
  scoring, optional debate, revision, dissent, synthesis, and Fresh Eyes
  validation;
- bounded in-memory stores by default and optional JDBC persistence on H2 or
  SQLite for sessions, chats, and events;
- asynchronous chat runs, cancellation, SSE replay cursors, restart interruption
  recovery, retention, and chat deletion cascade;
- local artifact storage with path containment and a durable terminal result at
  `final/result.json`;
- a static browser UI, health gate, stage timeline, trust signals, and a
  requirement-to-configuration advisor;
- an advanced configuration workbench with strict YAML import, validation,
  catalog preview, atomic save, export, and a bounded live model probe;
- pull-request CI for the clean Java 25 build and repository documentation/
  configuration checks;
- 932 deterministic JUnit tests in the current baseline.

This is a capable local/personal application. It is not ready for untrusted or
shared network deployment.

## Status matrix

| Capability | Status | Direct assessment |
|---|---|---|
| Provider/model health | Implemented | Preflight resolves the selected profile and depth and checks callability. |
| Failure categories | Implemented | Provider, timeout, output, quorum, validation, cancellation, partial, and configuration outcomes are distinct. |
| Startup/config validation | Implemented | Built-ins fail fast; user overlay errors are reported without taking down valid configuration. |
| Retry and timeout | Implemented with transport qualification | The terminal Spring AI call is timed and transient categories retry. Underlying transports still need their own timeouts because interrupt cancellation is best effort. |
| Session/chat persistence | Implemented, opt-in | Memory is the default. JDBC supports H2 and SQLite; artifacts remain filesystem-backed. |
| SSE recovery | Implemented | A shared per-chat sequence supports `Last-Event-ID` and query cursor replay. Live fan-out remains process-local. |
| Cancellation | Implemented at orchestration boundaries | Provider work may continue briefly if its transport ignores interruption. Chat currently presents cancellation as failure. |
| Concurrency control | Partial | Async chat uses a global permit and rejects saturation. The synchronous run endpoint bypasses that permit; there is no durable queue or distributed lease. |
| Observability | Partial | Events, artifacts, usage, health, and Actuator exist. Dedicated latency/failure/queue metrics and production dashboards do not. |
| API security | Not implemented | No authentication, authorization, ownership, rate limiting, or application-managed TLS. Default loopback binding is the only safe boundary. |
| Structured output recovery | Partial | Review parsing recovers multiple envelopes, compact criteria, fractional scores, and valid siblings, enforces exact coverage, and makes one targeted call for omitted drafts. Wholly unparseable review output, validation output, and advisor output do not receive a bounded repair call. |
| Real-provider verification | Partial | Two manual three-model Ollama runs now cover the conditional and forced full rigorous paths. The normal suite remains hermetic and there is no repeatable provider-contract Maven profile or cloud-provider gate. |
| Browser/load/fault testing | Not implemented | No browser E2E, load/soak, database contention, or network fault-injection suite. |
| Pull-request CI | Implemented | Java 25 `clean verify`, YAML parsing, local Markdown/image links, and removed-provider regression checks run on pull requests and `main`. |
| Configuration workbench | Implemented | Import, strict validation, diff preview, confirmed atomic save, export, restart guidance, and a guarded model-id probe are available at `/config.html`. |

## Must have before shared or public deployment

### 1. Authentication, authorization, and ownership

Add an authenticated principal and persist owner identity on chats, sessions,
artifacts, and event streams. Enforce ownership in every read, write, cancel,
delete, and SSE path. Add CSRF protection where cookie authentication is used,
rate limits, request-size limits, and explicit CORS policy.

Acceptance criteria:

- one user cannot enumerate, read, cancel, or delete another user's data;
- artifact access is authorized by session ownership, not merely by path safety;
- Docker/shared deployments fail closed when authentication is absent;
- security tests cover horizontal access attempts and SSE reconnects.

### 2. Production policy quality

Remove or demote shipped policies that seat the chair as a voting member, reuse
the same provider model under different logical IDs, or label correlated
validation as independent. Keep startup warnings, but do not treat warnings as a
substitute for honest defaults.

Acceptance criteria:

- every profile marketed as rigorous has review disagreement that is measurable;
- validation independence labels match provider and model-family reality;
- a profile that cannot meet its declared quality tier is blocked or explicitly
  downgraded in API/UI output.

### 3. Real-provider contract tests

Add an opt-in `provider-contracts` Maven profile. Cover Ollama, Spring AI
OpenAI, Anthropic, and Gemini adapters with minimal live calls and
assert timeout, usage extraction, structured output, model-not-found, auth, and
transient failure mapping.

The default `mvn test` must stay hermetic. Credentials must come only from the
environment or CI secrets.

### 4. CI on pull requests — implemented

`.github/workflows/ci.yml` now runs a clean Java 25 `mvn verify` and
`scripts/verify-repository.sh` for pull requests and pushes to `main`. The
repository script parses YAML, checks local Markdown/image targets, and prevents
the removed provider configuration from returning. Live-provider contracts
remain deliberately outside this hermetic job.

## Should have

### 5. Unified run admission and durable scheduling

Route both synchronous and chat runs through one admission policy. Add bounded
queue state, queue position, cancellation before start, per-provider/model
limits, and a durable lease if more than one application instance is supported.
Do not use raw `Future.cancel` as the lifecycle authority; the run registry and
persisted state must remain authoritative.

### 6. Consistent API and chat status contracts

Return one machine-readable error envelope across validation, conflict,
capacity, provider, and orchestration failures. Add a first-class `CANCELLED`
chat turn state instead of rendering cancellation as generic failure. Expose
retryability and safe operator guidance without leaking credentials or prompts.

### 7. Metrics and operational limits

Add Micrometer metrics for stage/model latency, retries, categorized failures,
quorum loss, validation rejection, queue pressure, cancellation, prompt
truncation, token usage, and artifact/storage failures. Define cardinality-safe
tags and alert thresholds before adding dashboards.

### 8. Structured-output repair

The review stages now make one bounded targeted request when a parseable response
omits required drafts and preserve the original and recovery artifacts, usage, and
events. Extend bounded repair to wholly unparseable review output and malformed
validation/advisor JSON. Never silently invent missing scores or confidence.

### 9. Browser, load, and fault testing

Add browser E2E for send/retry/cancel/delete/reconnect, load tests for admission
and SSE fan-out, and fault tests for slow transports, callback failure, database
locks, filesystem errors, and restart during each lifecycle window.

## Nice to have

- Token-aware multi-turn summarization rather than recent-turn/character bounds.
- Object-store artifact backend with encryption and lifecycle policy.
- Resume or re-run as a new immutable session, guarded by catalog generation.
- Hot reload using an immutable catalog generation per run.
- Per-profile cost budgets and optional user approval before expensive runs.
- Better semantic sycophancy/calibration evaluation based on measured datasets.

## Release gates

For local loopback/personal use:

- `mvn clean test` passes;
- provider health passes for the selected profile/depth;
- the user understands that prompts, raw model output, and results are written
  to the artifact directory;
- non-loopback binding is not used without an external trusted access boundary.

For shared/public use, all four must-have items above are release gates. Until
then, the direct recommendation is **do not expose this service to an untrusted
network**.
