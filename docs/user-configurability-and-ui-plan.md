# User Configurability, Web UI, and Requirement Advisor — Implementation Plan

**Status:** proposed
**Target version:** 2.1.0 → 2.4.0 (one minor per phase group)
**Audience:** an implementing engineer or LLM agent with no prior context on this repo beyond `CLAUDE.md`

---

## 0. Purpose and decisions already made

This plan covers three enhancements to LLM Council:

1. **User-defined models, profiles, and policies** — today everything is fixed in `src/main/resources/application.yml`.
2. **A web UI** for chat and council observation, served by the existing Spring Boot app.
3. **A requirement-driven configuration generator** (UI wizard + CLI) so non-technical users never hand-write YAML.

The following decisions are **settled**. Do not relitigate them during implementation.

| # | Decision | Rationale |
|---|---|---|
| D1 | Config gains a **user overlay layer** delivered in two steps: a file overlay merged at boot (Phase 1), then live hot-reload via a swappable catalog (Phase 4). | Ships value before the refactor lands; the refactor is designed for from Phase 0 so nothing is thrown away. |
| D2 | **Credentials are never accepted, stored, or echoed by the app.** API keys stay in the environment / `.env`. The UI reports which providers are active and names the exact environment variable to set for the inactive ones. | Keeps `CouncilConfig.hasRealCredential` the single source of truth and guarantees no secret ever lands in an overlay file, artifact, export, or log. |
| D3 | UI is **vanilla HTML/CSS/JS** under `src/main/resources/static/`, consuming the existing REST + SSE endpoints. No Node, no npm, no bundler, no `frontend-maven-plugin`. | `mvn package` stays a single self-contained jar with zero JS toolchain. The only non-trivial UI work is an `EventSource` stage timeline, which is ~150 lines of DOM code. |
| D4 | Users **tune** built-in protocols within validated clamps. They **cannot** reorder, add, or remove stages. `ANONYMIZE` and `REVIEW` can never be removed from a protocol a user can select. | Anonymized peer review and adversarial roles are the product, not a default. A user must not be able to silently produce a sycophantic council that still reports as healthy. |
| D5 | Single user, localhost, **no authentication**. | Personal-use tool. Every endpoint below assumes a trusted local caller. If this ever becomes multi-user, the config-write endpoints in Phases 3–5 are the ones that need authorization first. |
| D6 | Durable persistence targets **SQLite (default) and H2** through one JDBC implementation. A filesystem store may be added later; artifacts stay on the filesystem regardless. | One `JdbcSessionStore` plus a driver on the classpath covers both engines — and MySQL/Postgres later — without a third implementation to maintain. |
| D7 | The Phase 0 catalog reads are **one endpoint** (`GET /api/council/catalog?include=…`), not six. Runtime data (chats, sessions, artifacts) stays on its own resources. | Under Phase 4 hot reload, six separate fetches can straddle a swap and produce an internally inconsistent view. One request returns one snapshot with one `generation`. |
| D8 | There is **no such thing as a UI-only endpoint** without authentication, and the plan will not pretend otherwise. Protection is: bind to loopback, no permissive CORS, and a namespace split that signals API *stability*, not access control. | Anything a browser can call, `curl` can call. `Referer`/`X-Requested-With` checks are trivially forged and buy false confidence. See §3.6. |
| D9 | "Resume" means three different things and each is scoped separately: **continue a chat**, **re-run a question**, and **resume an interrupted run from a stage checkpoint**. | Only the third is hard. Conflating them leads to building the expensive one when the cheap two were what was actually wanted. See Phase 2B. |

---

## 1. Current architecture — the seams that matter

Read this section before writing code. Three properties of the existing design drive every decision below.

### 1.1 Config is materialized into immutable singletons at boot

`application.yml` → `CouncilProperties` (`@ConfigurationProperties(prefix = "council")`) → beans built in `CouncilConfig`:

- `@Bean Map<String, CouncilProfile> councilProfiles()`
- `@Bean Map<String, CouncilPolicy> councilPolicies()`
- `@Bean ModelRegistry modelRegistry()` — immutable, holds `ModelProfile` + `ModelClient` per model id
- `@PostConstruct initRegistries()` → `ProtocolDefinitionRegistry.register(...)`

Consumers:

| Consumer | Injects |
|---|---|
| `CouncilPolicyResolver` | `Map<String, CouncilProfile>`, `Map<String, CouncilPolicy>` |
| `ProfileHealthService` | both maps + `ModelRegistry` |
| 8 stage executors (`GenerationStageExecutor`, `ReviewStageExecutor`, `ReviewPostDebateStageExecutor`, `ScoreStageExecutor` via aggregation, `DebateStageExecutor`, `RevisionStageExecutor`, `SynthesisStageExecutor`, `ValidateStageExecutor`, `AggregationStageExecutor`) | `ModelRegistry` |
| `ProtocolOrchestrator` | `ProtocolDefinitionRegistry` |

**Injecting `Map<String, CouncilProfile>` by generic type is fragile** — Spring resolves it by collecting beans of type `CouncilProfile`, so you cannot introduce a second differently-named map of the same type. Phase 0 removes this pattern.

### 1.2 Startup validation is deliberately fail-fast — and must stay that way *for built-ins only*

`CouncilConfigurationValidator.validate(props)` plus `CouncilConfig.validateConfiguration(...)` throw `IllegalStateException` on any unknown cross-reference. That is correct for shipped config.

It is **wrong for user config**. A typo in a user-defined policy must not brick the application. The overlay layer therefore validates **fail-soft**: reject the offending entity, record a structured `ConfigIssue`, boot with the remaining valid entities, and surface the issues via API and UI. Built-in config keeps fail-fast semantics unchanged.

### 1.3 Ollama model discovery already exists — it is just not exposed

`OllamaProviderHealthChecker` (`src/main/java/com/debopam/llmcouncil/model/OllamaProviderHealthChecker.java`) already GETs `/api/tags`, parses the installed model names, and returns them in `ProviderHealth.knownProviderModels`. Phase 5's wizard needs exactly this list so it can only ever propose models the user has actually pulled. Extract it rather than reimplementing it.

### 1.4 What is missing for a UI

- No `src/main/resources/static/` directory at all.
- No `GET /api/council/profiles` (only `GET /api/council/profiles/{id}/health`).
- No `GET /api/council/chats` listing and no `ChatSessionStore.findAll()`.
- `GET /api/council/sessions/{id}/artifacts` returns **paths only**, no content — the UI cannot render drafts, reviews, or scores.
- No run cancellation, no SSE reconnect cursor (both called out as known gaps in `CLAUDE.md`).
- All four stores (`InMemorySessionStore`, `InMemoryChatSessionStore`, `InMemoryEventPublisher`, and the chat broker) are `ConcurrentHashMap`s with **no eviction and no durability**. Restart clears everything.

### 1.5 Pre-existing defects folded into this plan

These were found while surveying the code for this plan. They are not new work invented by it, and each is assigned to a phase below.

| # | Finding | Evidence | Assigned |
|---|---|---|---|
| F1 | **"Fresh Eyes" validation is not independent in 5 of 7 shipped profiles.** `local-balanced`, `local-rigorous`, `gemini-balanced`, `gemini-rigorous` set `validatorModelId == chairModelId` (identical model id). `oci-*` and `hybrid-*` use two ids that both default to `${OCA_LLM_MODEL:gpt-5.4}`. Only `multi-cloud-*` is genuinely independent. `CouncilConfigurationValidator` checks *member* diversity but never compares chair to validator. | `application.yml` policies; `CouncilConfigurationValidator.warnLowDiversity` | Phase 0 (§3.9) + Phase 1 (§4.7) |
| F2 | **Prompts are never length-bounded.** `PromptBuilder` contains no truncation of any kind; `synthesisMessages` concatenates every draft, review, score line, and debate round. Ollama ships `num-ctx: 4096` while local models are configured for 1200–1800 output tokens each, so a 3-member rigorous synthesis prompt exceeds the window and Ollama silently discards context. Gets worse under §2.2, which permits 8-member policies. | `PromptBuilder`; `application.yml` `num-ctx` | Phase 1 (§4.8) |
| F3 | **Token counts are captured then discarded.** `OllamaDirectModelClient` parses `prompt_eval_count`/`eval_count` and `SpringAiModelClient` reads usage metadata into `ModelCallResult`, but all 8 executor call sites use only `.text()`. Nothing aggregates, so a `multi-cloud-rigorous` run (30–40 cloud calls) reports no cost signal. | `ModelCallResult`; the 8 `.call(` sites | Phase 2 (§5.6) |
| F4 | **No run cancellation.** `CouncilRunExecutor.submit` discards the `Future`. With `max-concurrent-runs` defaulting to 1, one long run blocks all others with no way to stop it. | `CouncilRunExecutor` | Phase 2 (§5.7) |
| F5 | **Unbounded in-memory growth.** No eviction anywhere; events are the highest-cardinality data at dozens per run. | `InMemoryEventPublisher.eventsBySession` | Phase 2A (§7.8) |

---

## 2. The capability boundary — what a user may and may not change

This table is the specification. Phase 1 implements it as executable validation; the UI in Phase 3 renders it as form affordances. Anything not listed as editable is not editable.

### 2.1 Editable — models

A user defines a **model binding**, not a provider integration.

| Field | Editable | Constraint |
|---|---|---|
| `id` | yes | `^[a-z0-9][a-z0-9-]{1,62}$`. Must not collide with a built-in id unless the entity sets `overrides: <built-in-id>` explicitly. |
| `provider` | yes, **from a closed set** | One of `ollama`, `openai`, `anthropic`, `gemini`, `openai-compatible`. `mock` is rejected in the user layer. |
| `providerModelId` | yes | Non-blank, ≤ 200 chars. For `ollama`, warn (do not reject) if not present in `/api/tags`. |
| `defaultOutputTokens` | yes | 64 – 32000 |
| `temperature` | yes | 0.0 – 2.0 |
| `timeoutSeconds` | yes | 5 – 900 |
| `role` | yes | `MEMBER` \| `CHAIR` \| `VALIDATOR` |
| `councilRole` | yes | `PROPOSER` \| `CRITIC` \| `SYNTHESIZER` |
| `modelFamily` | yes | ≤ 40 chars, lowercase slug. Blank permitted but triggers a diversity warning. |
| `retryMaxAttempts` | yes | 0 – 5 |
| `retryBaseDelayMs` | yes | 100 – 30000 |
| `testOnly` | **no** | Forced to `false` in the user layer. Only shipped mock models are `testOnly`. |

### 2.2 Editable — policies

Full create / update / shadow. Fields map 1:1 to `CouncilProperties.PolicyProps`.

| Field | Constraint |
|---|---|
| `protocolId` | Must resolve in the merged catalog (built-in or a user-derived protocol from §2.4). |
| `memberModelIds` | 1 – 8 entries, each resolving in the merged catalog, no duplicates. Rejected if any resolves to a `testOnly` model and the owning profile is not `testOnly`. |
| `chairModelId` | Must resolve. |
| `validatorModelId` | Optional; if present must resolve. Required when `validationRequired` is `true`. |
| `minimumSuccessfulDrafts` | 1 – `memberModelIds.size()` |
| `minimumReviewsPerDraft` | 0 – `memberModelIds.size() - 1` |
| `validationRequired`, `allowPartial` | boolean |

### 2.3 Editable — profiles

| Field | Constraint |
|---|---|
| `id` | Same slug rule as models. |
| `displayName` | 1 – 80 chars. |
| `defaultDepth` | `QUICK` \| `BALANCED` \| `RIGOROUS` |
| `depthPolicies` | Must define at least the `defaultDepth` entry; every referenced policy must resolve. Missing depths are inherited from the shadowed built-in if this profile overrides one, otherwise rejected at request time with a clear message. |
| `testOnly` | **no** — forced `false`. |

Users **may** shadow the built-in `default` profile to repoint its depth policies at their own policies. That is the primary intended use.

### 2.4 Editable — derived protocols (tuning only)

A user protocol is always a **clone of a built-in** with stage-option overrides:

```yaml
protocols:
  my-rigorous:
    derivedFrom: rigorous     # required; must name a built-in protocol
    description: "Rigorous, but only two debate rounds"
    stageOptions:
      DEBATE:
        max-rounds: 2
```

`orderedStages` is **not** an accepted key in the user layer — supplying it is a validation error, not a silent ignore.

Allowed stage-option keys and clamps (keys verified against the executors; do not invent new ones):

| Stage | Key | Type | Clamp | Built-in default | Integrity-reducing |
|---|---|---|---|---|---|
| `DEBATE` | `min-rounds` | int | 1 – 3 | 2 | no |
| `DEBATE` | `max-rounds` | int | 1 – 5, must be ≥ `min-rounds` | 3 | no |
| `DEBATE` | `ks-convergence-threshold` | double | 0.01 – 0.50 | 0.10 | no |
| `DEBATE` | `debate-trigger-score-variance` | double | 0.0 – 1000.0 | 120.0 | no |
| `DEBATE` | `sycophancy-threshold` | double | 0.30 – 0.95 | 0.70 | **yes** above 0.85 |
| `DEBATE` | `force-run` | boolean | — | false | no |
| `SCORE` | `scoring-strategy` | enum | must match a registered `ScoringStrategy.name()` | `confidence-weighted` | no |
| `SCORE` | `artifact-label` | string | `^[a-z0-9-]{1,32}$` | — | no |
| `SCORE` | `escalation-variance-threshold` | double | 0.0 – 1000.0 | 120.0 | no |
| `SCORE` | `escalation-policy` | enum | must match an `EscalationPolicy` constant | `SYNTHESIZE_WITH_DISSENT` | no |
| `SYNTHESIZE` | `preserve-dissent` | boolean | — | true | **yes** when false |
| `EXPORT` | `export-raw-artifacts` | boolean | — | false | no |

Any key not in this table is a validation error. Options marked *integrity-reducing* are permitted but must (a) produce a warning on the config, (b) be rendered with a visible caution in the UI, and (c) set `integrityReduced: true` on every run that uses them, surfaced in `CouncilRunResponse` and the run timeline.

### 2.5 Editable — runtime knobs

| Key | Clamp |
|---|---|
| `council.runtime.max-concurrent-runs` | 1 – 8 |
| `council.runtime.chat-recent-turn-count` | 1 – 20 |
| `council.persistence.artifactBasePath` | absolute path, must be writable; rejected if it resolves inside the application working directory |

### 2.6 Not editable — hard boundary

| Thing | Why | Where it lives |
|---|---|---|
| **New provider types** | Requires a new `ModelClient` implementation. | `CouncilConfig.buildClient(...)` — Java only |
| **Credentials of any kind** | D2. The overlay reader must reject any key matching `(?i)(api-?key\|token\|secret\|password\|credential)` anywhere in the user file, with an error naming the offending path. | environment / `.env` only |
| `council.allowMockFallback` | Product guarantee that a real profile never silently degrades to mock output. | locked `false`; overlay key rejected |
| `orderedStages` / the `StageType` enum | D4 | `application.yml` + Java |
| `testOnly` on any entity | Prevents mock models leaking into real councils. | forced `false` in overlay |
| Stage-option keys outside §2.4 | Unknown keys are silently ignored by `ProtocolStageOptions`, so accepting them means accepting typos that look like they worked. | validation allowlist |
| Clamp bounds themselves | Otherwise the boundary is self-defeating. | Java constants |
| Deletion of built-in entities | Built-ins may be **shadowed** or **hidden from the UI picker**, never deleted — a removed built-in would break the mock profile and the test suite. | `CouncilCatalog` merge rules |

---

## 3. Phase 0 — Catalog seam and read APIs ✅ IMPLEMENTED

**Goal:** introduce the indirection every later phase needs, and the read endpoints the UI needs, with **zero behaviour change**.
**Depends on:** nothing. **Blocks:** every other phase.
**Status:** shipped. 103 tests green (70 pre-existing, unchanged).

Deviations from this section as originally written, all deliberate:

1. **The catalog is built in a `@Bean`, not `@PostConstruct`.** `@PostConstruct` on a `@Configuration` class runs when the configuration object is initialised, which is *before* its own `@Bean` methods execute — so it cannot see the `ModelRegistry` bean. `councilCatalogHolder(ModelRegistry)` takes the registry as a parameter instead, which also makes anything injecting the holder transitively depend on a fully-built catalog. `CouncilCatalogHolder` is created by that bean method rather than component-scanned.
2. **`CouncilContext.catalog()` is nullable.** Production always supplies it; the 4-argument constructor remains for tests that build a context directly. `modelRegistry()` throws a clear `IllegalStateException` when unbound rather than NPE-ing.
3. **HTTP path traversal returns 404, not 400.** Spring's path matching rejects `..` segments before the handler runs. The store-level guard (`LocalArtifactStore.resolve`) still throws `IllegalArgumentException` and is covered directly by `LocalArtifactStoreTest`; the HTTP 404 is defence in depth ahead of it.
4. **`ValidationIndependence` is reported on `CouncilRunResponse` and the catalog, not yet on `ValidationArtifact`.** That record is produced by the structured output parser; stamping it there belongs with the Phase 2 timeline work that renders it.
5. **Docker compose files now set `LLM_COUNCIL_BIND_ADDRESS=0.0.0.0`,** because loopback binding inside a container would make the published port unreachable. `DockerComposeConfigurationTest` still passes.

### 3.1 New: `config/CouncilCatalog.java`

An immutable snapshot of everything resolved from configuration.

```java
public record CouncilCatalog(
        ModelRegistry modelRegistry,
        Map<String, CouncilProfile> profiles,
        Map<String, CouncilPolicy> policies,
        Map<String, ProtocolDefinition> protocols,
        Map<String, ConfigOrigin> origins,   // "model:local-llama3" -> BUILT_IN | USER
        List<ConfigIssue> issues,            // empty in Phase 0
        Instant builtAt,
        long generation                      // monotonic; increments on each Phase 4 swap
) {
    public CouncilCatalog {
        profiles = Map.copyOf(profiles);
        policies = Map.copyOf(policies);
        protocols = Map.copyOf(protocols);
        origins = Map.copyOf(origins);
        issues = List.copyOf(issues);
    }
}
```

Supporting types in the same package:

```java
public enum ConfigOrigin { BUILT_IN, USER, USER_OVERRIDE }

public record ConfigIssue(
        Severity severity,      // ERROR | WARNING
        String entityKey,       // "policy:my-balanced"
        String field,           // "memberModelIds[2]" or null
        String message,         // human-readable, actionable
        String remediation      // nullable; e.g. "Run: ollama pull mistral:7b"
) {
    public enum Severity { ERROR, WARNING }
}
```

### 3.2 New: `config/CouncilCatalogHolder.java`

```java
@Component
public class CouncilCatalogHolder {
    private final AtomicReference<CouncilCatalog> current = new AtomicReference<>();

    public CouncilCatalog get() { /* throws IllegalStateException if not yet initialised */ }
    void initialise(CouncilCatalog catalog) { /* package-private; single use at boot */ }
    // swap(...) is added in Phase 4 — do not add it now.
}
```

`CouncilConfig` builds the catalog in `@PostConstruct initRegistries()` and calls `initialise(...)`.

### 3.3 Changed files

| File | Change |
|---|---|
| `config/CouncilConfig.java` | Keep `modelRegistry()` as a `@Bean` (stage executors still inject it in Phase 0). **Delete** `@Bean councilProfiles()` and `@Bean councilPolicies()`; build those maps inside `initRegistries()` and put them in the catalog instead. |
| `application/CouncilPolicyResolver.java` | Constructor takes `CouncilCatalogHolder`. `resolve(...)` reads `holder.get()` **once** into a local and resolves profile + policy from that single snapshot. Behaviour and error messages unchanged. |
| `application/ProfileHealthService.java` | Same substitution. Also read `ModelRegistry` from the catalog rather than injecting it. |
| `orchestration/ProtocolDefinitionRegistry.java` | Mark `register(...)` as deprecated-for-removal in a Javadoc note; keep it working. Phase 4 removes it. |
| `application/CouncilService.java` | When a run starts, capture `holder.get()` and store the snapshot on `CouncilContext` (see §3.4). |
| `orchestration/CouncilContext.java` | Add a `CouncilCatalog catalog` field, set at construction, exposed as `catalog()` plus a convenience `modelRegistry()`. Non-null, never mutated. |

### 3.4 Why the snapshot goes on `CouncilContext`

`ModelRegistry` is injected into 8 stage executors. In Phase 4 a hot swap must not change the model set out from under a council that is halfway through `DEBATE`. Putting the snapshot on `CouncilContext` — which is already the single mutable state object threaded through every stage — gives per-run isolation for free.

**Phase 0 does not change the executors.** It only adds `ctx.catalog()` so Phase 4's executor migration is a mechanical find-and-replace of `this.modelRegistry` → `ctx.modelRegistry()`.

### 3.5 New read endpoints — one catalog resource, not six

Per D7, everything derived from `CouncilCatalog` is served by a **single endpoint** with an `include` filter. The five entity types are five projections of one immutable snapshot; serving them separately means a client assembling a config screen issues five requests that, after Phase 4, may span a hot swap and disagree with each other.

```
GET /api/council/catalog?include=profiles,models&includeTestOnly=false
```

| Parameter | Default | Meaning |
|---|---|---|
| `include` | all sections | Comma-separated subset of `profiles,policies,models,protocols,providers,issues`. Unknown section name → 400 listing the valid names. |
| `includeTestOnly` | `false` | When false, omit `testOnly` profiles and any model only they reference. |

Response — **omitted sections are absent, not null**, so a client can distinguish "not requested" from "empty":

```json
{
  "generation": 1,
  "builtAt": "2026-07-21T10:00:00Z",
  "profiles":  [ { "id": "local", "displayName": "...", "defaultDepth": "BALANCED",
                   "availableDepths": ["QUICK","BALANCED","RIGOROUS"],
                   "policyIdsByDepth": {...}, "testOnly": false, "origin": "BUILT_IN" } ],
  "policies":  [ { ...CouncilPolicy fields..., "origin": "BUILT_IN",
                   "validationIndependence": "SELF_VALIDATION" } ],
  "models":    [ { "id": "local-llama3", "provider": "ollama", "providerModelId": "llama3.1:8b",
                   "role": "MEMBER", "councilRole": "PROPOSER", "modelFamily": "llama",
                   "origin": "BUILT_IN", "clientKind": "LIVE" } ],
  "protocols": [ { "id": "rigorous", "description": "...", "orderedStages": [...],
                   "stageOptions": {...}, "origin": "BUILT_IN", "derivedFrom": null } ],
  "providers": [ { "provider": "ollama", "active": true, "reason": null,
                   "requiredEnvVar": null, "discoveredModels": ["llama3.1:8b","mistral:7b"] },
                 { "provider": "anthropic", "active": false,
                   "reason": "API key is a placeholder or missing",
                   "requiredEnvVar": "SPRING_AI_ANTHROPIC_API_KEY", "discoveredModels": [] } ],
  "issues":    [ ]
}
```

Implementation notes:

- `clientKind ∈ {LIVE, UNAVAILABLE, MOCK}` is derived by inspecting the client instance type in the registry. **Never expose credentials, key fragments, or key lengths** — `providers[].reason` is a fixed human-readable string, never the offending value (D2).
- `generation` is read once at the top of the handler and the whole response built from that one `CouncilCatalog` reference. Do not call `holder.get()` more than once per request.
- `providers[].discoveredModels` is populated for `ollama` via `OllamaModelDiscoveryService` (§3.7) and is empty for every other provider. It must never block the response — the discovery service returns an empty list on timeout rather than throwing.
- The endpoint is cheap and uncached. Do not add `ETag` handling; `generation` already gives the client a change signal.

**Runtime resources stay separate** — they have different lifecycles and change on every run, whereas the catalog changes only on reload:

| Method | Path | Response | Notes |
|---|---|---|---|
| GET | `/api/council/chats` | `List<ChatSummaryResponse>` | In `ChatController`. `{chatId, profileId, depthMode, status, turnCount, firstUserMessage, createdAt, updatedAt}`, ordered by `updatedAt` descending. |
| DELETE | `/api/council/chats/{id}` | 204 | In `ChatController`. 409 if the chat has a `RUNNING` turn. |
| GET | `/api/council/sessions/{id}/artifacts/**` | `String` | Artifact **content**. Path canonicalised against the session directory; escapes rejected with 400. See §3.7. |

`GET /api/council/profiles/{id}/health` stays exactly as it is — it performs live network probes and must not be folded into the catalog read.

### 3.6 API exposure — what is and is not achievable

Per D8. This section exists because "make these endpoints available only to the UI" is a reasonable-sounding requirement that has no honest implementation without authentication.

**What does not work, and must not be implemented:** checking the `Referer` or `Origin` header to detect "came from our UI"; requiring a custom header like `X-Requested-With`; embedding a shared secret in the served JavaScript. All are trivially reproduced by any client, and all create the impression of a boundary where none exists. A reviewer seeing them will assume the endpoints are protected.

**What actually works, in order of value:**

1. **Bind to loopback.** Set `server.address=${LLM_COUNCIL_BIND_ADDRESS:127.0.0.1}` in `application.yml`. This is the real control: the API is unreachable from the network regardless of what any client sends. Make it an overridable property so Docker deployments (which need `0.0.0.0`) can opt out deliberately — and log a `WARN` at boot when the bind address is not loopback and no token is configured.
2. **Do not enable permissive CORS.** Spring's default is same-origin, which is correct. Never add `@CrossOrigin` or a global `CorsConfigurationSource` with `*`. This is what stops a random website the user visits from driving their council through their browser.
3. **Force preflight on every mutating endpoint.** Config writes (Phase 3), `apply` (Phase 4), and run control (Phase 2B) must accept `application/json` only and reject `application/x-www-form-urlencoded`, `multipart/form-data`, and `text/plain`. Those three content types make a POST a CORS "simple request" that fires cross-origin without preflight; requiring JSON forces a preflight the default CORS policy will deny. **This is the concrete CSRF defence** and it matters from Phase 3 onward, when endpoints start changing state.
4. **Optional shared token, deferred.** If the app is ever exposed beyond loopback, add a `OncePerRequestFilter` checking `Authorization: Bearer ${LLM_COUNCIL_API_TOKEN}` when that variable is set, no-op when it is not. Roughly 30 lines. Do not build it before there is a reason.

**Namespace split — a stability contract, not a security boundary.** Serve the UI's convenience endpoints under `/api/ui/**` and keep `/api/council/**` as the stable, documented API. `/api/ui/**` may change shape without notice between versions; `/api/council/**` may not. Document it in exactly those terms and never describe `/api/ui/**` as private or protected. Under this split, `GET /api/council/catalog` stays public (it is genuinely useful to scripts), while aggregate view-model endpoints added purely to save the UI a round trip belong under `/api/ui/**`.

### 3.7 Supporting changes for the listings

- `chat/ChatSessionStore.java` — add `List<ChatSession> findAll();` and `boolean delete(String chatId);`. Implement in `InMemoryChatSessionStore` (order by `updatedAt` descending).
- `persistence/ArtifactStore.java` — add `Optional<String> readArtifact(String sessionId, String relativePath);`. Implement in `LocalArtifactStore`.
- New `model/OllamaModelDiscoveryService.java` — extract the `/api/tags` fetch+parse currently inlined in `OllamaProviderHealthChecker` into a reusable `@Component` with `List<String> installedModels()` returning an empty list (never throwing) when Ollama is unreachable. Refactor `OllamaProviderHealthChecker` to delegate to it. **This must not change `OllamaProviderHealthCheckerTest` behaviour** — keep the existing constructor signature working.

### 3.8 Path traversal guard (mandatory)

`readArtifact` is the only endpoint that maps user input to a filesystem path. Implement it as:

```java
Path base = Path.of(artifactBasePath, sessionId).toAbsolutePath().normalize();
Path target = base.resolve(relativePath).toAbsolutePath().normalize();
if (!target.startsWith(base)) throw new IllegalArgumentException("Artifact path escapes session directory");
```

Add a test that `../../../etc/passwd` and an absolute path both return 400.

### 3.9 Validator independence tiers (fixes F1)

`CouncilConfigurationValidator` gains a graduated check comparing chair to validator. It is a **warning, never a boot failure** — a single-model machine must still be able to run a council, consistent with how `warnLowDiversity` already behaves.

```java
public enum ValidationIndependence { INDEPENDENT, CORRELATED, SELF_VALIDATION }
```

| Condition | Tier | Behaviour |
|---|---|---|
| Chair and validator have different `modelFamily` | `INDEPENDENT` | silent |
| Different model id, but same `modelFamily` **or** same resolved `providerModelId` | `CORRELATED` | boot `WARN`; badge in the Phase 2 timeline |
| `chairModelId.equals(validatorModelId)` | `SELF_VALIDATION` | boot `WARN`; prominent badge; reported validation confidence capped at 0.6 |

Details:

- Compare **resolved** `providerModelId`, not the raw property string, so `oci-gpt-5-4` and `oci-reviewer` both defaulting to `gpt-5.4` are correctly detected as `CORRELATED`.
- Policies with no validator (`validatorModelId` blank, `validationRequired: false`) are exempt — no validation was claimed, so nothing is misreported.
- Add `boolean acknowledgeSelfValidation` to `PolicyProps` (default `false`). When `true`, suppress the boot warning but **still** stamp the tier on the artifact and the API response. A user with an 8 GB machine has made an informed trade-off and should not be nagged; a user who did it by accident should still find out.
- Expose the tier as `policies[].validationIndependence` in the catalog response (§3.5), on `ValidationArtifact`, and on `CouncilRunResponse`.

**Rationale to preserve in the Javadoc:** rubber-stamped validation is worse than no validation, because a "validated" marker makes a user trust an answer more. When the validator shares the chair's weights it shares every one of its blind spots, so the pipeline should degrade the *claim*, not silently degrade the guarantee.

`PromptBuilder` change for the `SELF_VALIDATION` tier: do not reveal that the validating model authored the synthesis, frame the task as an external audit, set a finding quota ("identify at least three specific errors or unsupported claims"), and use a temperature distinct from the chair's. Same-weights critique with a fresh context and adversarial framing still catches self-contradiction, unsupported leaps, and rubric violations — it cannot catch shared knowledge errors, and the tier badge is what communicates that limit.

### 3.10 Tests for Phase 0

- `CouncilCatalogTest` — defensive copies hold; mutating an input map after construction does not affect the catalog.
- `CouncilPolicyResolverTest` — **update existing test** to construct a `CouncilCatalogHolder`; all current assertions must still pass unchanged.
- `ProfileHealthServiceTest` — same update.
- `CatalogEndpointTest` — `include` filtering, omitted-vs-empty section distinction, unknown section → 400, `includeTestOnly` behaviour, and an assertion that **no response field contains any configured credential value**.
- `ValidationIndependenceTest` — one case per tier, including the same-`providerModelId`-different-id case and the `acknowledgeSelfValidation` suppression.
- `ArtifactPathTraversalTest` — new, per §3.8.
- `ChatSessionStoreTest` — new; `findAll` ordering and `delete` semantics.

**Done when:** `mvn test` is green with the existing 70 tests still passing, `curl localhost:8080/api/council/catalog` returns every section, and the boot log warns that `local-balanced` and `gemini-balanced` are `SELF_VALIDATION`.

---

## 4. Phase 1 — User configuration overlay ✅ IMPLEMENTED (1A/1B/1C/1D)

**Goal:** users define models, policies, profiles, and derived protocols in a file outside the jar; invalid entries degrade gracefully.
**Depends on:** Phase 0.

### 4.1 File location and format

Resolution order, first match wins:

1. `--council.userConfigPath=<path>` / `COUNCIL_USER_CONFIG_PATH` env var
2. `${LLM_COUNCIL_HOME:-$HOME/.llm-council}/council-user.yml`

The file is **optional**. Absence is normal and logged at `INFO`, not `WARN`.

Do **not** use `spring.config.additional-location` or `spring.config.import`. Spring's property merge would union the `council.models` list and deep-merge maps with no origin tracking, no fail-soft behaviour, and no way to distinguish a user override from a built-in. Load and parse it explicitly.

Schema (all sections optional):

```yaml
version: 1
models:
  - id: my-qwen
    provider: ollama
    providerModelId: qwen2.5:14b
    defaultOutputTokens: 1600
    temperature: 0.3
    timeoutSeconds: 300
    role: MEMBER
    councilRole: CRITIC
    modelFamily: qwen
policies:
  my-balanced:
    protocolId: balanced
    memberModelIds: [local-llama3, my-qwen]
    chairModelId: local-chair
    validatorModelId: local-chair
    minimumSuccessfulDrafts: 2
    minimumReviewsPerDraft: 1
    validationRequired: true
    allowPartial: true
profiles:
  my-council:
    displayName: My laptop council
    defaultDepth: BALANCED
    depthPolicies:
      QUICK: local-quick
      BALANCED: my-balanced
      RIGOROUS: my-balanced
protocols:
  my-rigorous:
    derivedFrom: rigorous
    stageOptions:
      DEBATE:
        max-rounds: 2
runtime:
  maxConcurrentRuns: 2
```

### 4.2 New files

| File | Responsibility |
|---|---|
| `config/user/UserConfigDocument.java` | Record mirroring the YAML above. Jackson `YAMLFactory` binding with `FAIL_ON_UNKNOWN_PROPERTIES = true` so typos are errors, not silent no-ops. |
| `config/user/UserConfigLoader.java` | Resolves the path, reads bytes, parses to `UserConfigDocument`. Returns `Optional.empty()` when absent. Any I/O or parse failure becomes a single `ConfigIssue(ERROR, "file", ...)` — never an exception that reaches boot. |
| `config/user/UserConfigValidator.java` | Implements §2 as executable rules. Input: `UserConfigDocument` + the built-in catalog. Output: `ValidationReport(List<ConfigIssue> issues, UserConfigDocument sanitised)` where `sanitised` has every `ERROR`-bearing entity removed. |
| `config/user/StageOptionSpec.java` | The §2.4 clamp table as data: `record StageOptionSpec(StageType stage, String key, Type type, Object min, Object max, Object defaultValue, boolean integrityReducing)`. Also serves the UI form generator in Phase 3 — **make this the single source of truth, not a duplicated constant list.** |
| `config/user/CatalogMerger.java` | Merges built-in + sanitised user document into a `CouncilCatalog`, stamping `ConfigOrigin` per entity. |
| `config/user/SecretScanner.java` | Rejects any user-config key or string value matching the D2 credential pattern before validation runs. |

### 4.3 Merge rules

1. Built-in entities are loaded first; they always resolve.
2. A user entity whose id does not exist in built-ins is added with origin `USER`.
3. A user entity whose id **does** exist is applied as an override with origin `USER_OVERRIDE`. For maps (`profiles.depthPolicies`, `protocols.stageOptions`) the override is a **per-key merge**, not a replacement, so a user can change one depth without redeclaring all three. For lists (`memberModelIds`) the override is a **full replacement** — a partial list merge has no sensible semantics.
4. Overriding a `testOnly` built-in (`mock`, `mock-*`) is an `ERROR`.
5. Validation runs against the **merged** catalog, not the user document alone, so cross-references to built-in ids resolve.
6. If removing an `ERROR` entity orphans a reference from a valid entity, the referring entity is also removed, with a `ConfigIssue` explaining the cascade. Iterate to a fixed point.

### 4.4 Changed files

- `config/CouncilConfig.java` — `initRegistries()` becomes: build built-in catalog → load user doc → scan for secrets → validate → merge → `holder.initialise(merged)`. Built-in validation stays fail-fast; user validation is fail-soft.
- `config/CouncilProperties.java` — add the missing `RuntimeProps` and `HealthProps` nested classes. They are currently read only via `@Value` in `CouncilRunExecutor` and `ChatCouncilService`, so `council.runtime.*` is not actually bound to the properties object. Bind them, then have those two classes take the bound values. **This is a latent inconsistency in the current code, not new work invented by this plan.**
- `api/CouncilController.java` — no new endpoint needed; the `issues` section of `GET /api/council/catalog` (§3.5) already carries `List<ConfigIssue>` from the active catalog, which is what the UI's startup-problems banner reads.

### 4.5 Startup logging contract

On boot, log exactly one summary line at `INFO`:

```
User config: 3 models, 1 policy, 1 profile loaded from /Users/x/.llm-council/council-user.yml (2 warnings, 0 errors)
```

Then each issue on its own line at `WARN` (warnings) or `ERROR` (errors). Never log the file contents.

### 4.7 Shipped-config fix for validator independence (fixes F1) ✅ IMPLEMENTED — shipped separately as 1C

Config-only, no Java. The local fix costs nothing: `mistral:7b` is already pulled as part of the documented local setup and is already loaded during GENERATE, so using it as validator adds no download and no extra peak RAM. VALIDATE runs alone at the end of the pipeline, after SYNTHESIZE, so the validator never needs to be co-resident with the members — the ceiling is set by the GENERATE fan-out.

`validatorModelId` cannot simply point at `local-mistral`, because the existing role check requires `VALIDATOR` or `CHAIR` and `local-mistral` is `MEMBER`. Add a second logical binding over the same provider model:

```yaml
- id: local-validator
  provider: ollama
  providerModelId: ${LLM_COUNCIL_LOCAL_ALT_MODEL:mistral:7b}
  defaultOutputTokens: 1200
  temperature: 0.2
  timeoutSeconds: ${LLM_COUNCIL_LOCAL_TIMEOUT_SECONDS:240}
  role: VALIDATOR
  councilRole: CRITIC
  modelFamily: mistral
```

Then repoint:

| Policy | Change | Resulting tier |
|---|---|---|
| `local-balanced`, `local-rigorous` | `validatorModelId: local-validator` | `INDEPENDENT` |
| `hybrid-balanced`, `hybrid-rigorous` | `validatorModelId: local-validator` | `INDEPENDENT` |
| `gemini-balanced`, `gemini-rigorous` | `validatorModelId: gemini-validator` | `CORRELATED` — best achievable in a single-provider profile, still better than identical |
| `oci-*` | document that `OCA_LLM_REVIEW_MODEL` should be set to a different model than `OCA_LLM_MODEL` | `CORRELATED` until the operator differentiates them |

Two deviations from this section as originally written:

1. **Gemini needed its own `gemini-validator` binding**, not a direct repoint to `gemini-flash`. The existing role check requires the validator to be `VALIDATOR` or `CHAIR`, and `gemini-flash` is `MEMBER` — the same obstacle that made `local-validator` necessary. Both are second logical bindings over an already-configured provider model.
2. **`hybrid-*` reached `INDEPENDENT` rather than staying `CORRELATED`.** The hybrid profile already runs Ollama members, so pointing validation at `local-validator` against a GPT-family chair is both genuinely independent and cheaper than a second OCI call. Not in the original plan; taken because it was free.

Update `docs/testing-m1-32gb.md` and `docs/testing-intel-2019-32gb.md` if they enumerate the local model set.

### 4.8 Prompt budgeting (fixes F2) ✅ IMPLEMENTED — shipped separately as 1B

New `orchestration/PromptBudget.java`, applied by `PromptBuilder` to every multi-input prompt (`aggregationMessages`, `reviewMessages`, `debateMessages`, `synthesisMessages`).

- **Budget source.** Add `contextWindowTokens` to `ModelProps` / `ModelProfile` (editable per §2.1, clamp 1024 – 1_000_000, default 4096 for `ollama` and 128000 for cloud providers). The budget for a prompt is `contextWindowTokens - defaultOutputTokens - SAFETY_MARGIN` where `SAFETY_MARGIN = 512`.
- **Estimation.** A `chars / 4` heuristic is sufficient and requires no tokenizer dependency. Deliberately conservative — over-estimating tokens truncates slightly early, which is safe; under-estimating silently loses content, which is not.
- **Allocation.** Instructions and the user question are never truncated. The remaining budget is divided evenly across the variable-length sections (drafts, reviews, scores, debate), then evenly within each section across its items, so no single long draft starves the others.
- **Truncation marker.** Cut at a paragraph boundary where possible and append `\n\n[... truncated: N characters omitted ...]` so downstream models can see that content was removed rather than silently reasoning over a fragment.
- **Signal.** On any truncation call `ctx.addWarning(...)` and publish a `CONTEXT_BUDGET_EXCEEDED` event naming the stage, the model, the estimated tokens, and the budget. This surfaces in `CouncilRunResponse.warnings` and the Phase 2 timeline.
- **Validation hook.** Implemented in `CouncilConfigurationValidator` rather than `UserConfigValidator`, which does not exist until Phase 1A. The estimate is **protocol-aware**: reviews and debate are only charged when the protocol actually runs those stages, so `quick` is not warned about evidence it never accumulates. Move or extend this when 1A lands.

Deviations from this section as originally written:

1. **`CHARS_PER_TOKEN` is 3.5, not 4.** Assuming fewer characters per token claims less room than the model really has. Erring this way truncates slightly early; erring the other way overflows and loses content silently, which is the failure being fixed.
2. **Allocation is max-min fair, not evenly split.** An even split would strand a quarter of the window on a two-line score summary while the drafts were being cut. Sections and items that need less than their share release the surplus for redistribution.
3. **The context window derivation is shared** via `ModelContextWindows`, used by both `CouncilConfig` and the validator. The first implementation had the validator read the raw configured value, which meant it silently skipped every model relying on the provider default — that is, all the local models the check exists for.
4. **Six prompts are budgeted, not four**: synthesis, review, post-debate review, debate, aggregation, and revision. Each keeps an unbudgeted overload so existing callers and tests are unchanged.

**What the warning reports on the shipped local config** (`num-ctx: 4096`):

| Policy | Chair prompt room | Evidence produced | Overrun |
|---|---|---|---|
| `local-balanced` | ~2,296 tokens | ~4,200 | 1.8× |
| `local-rigorous` | ~2,296 tokens | ~11,100 | **4.8×** |

Before this change Ollama silently discarded the excess, so a rigorous local run synthesised its answer from roughly a fifth of the council's work with no signal anywhere.

**1D — window sizing (shipped with 1B).** Budgeting made the overflow visible and safe; it did not make it go away. The remedy is capacity, and the required figure was measured by booting at each window size and reading the warnings:

| `num-ctx` | Over-budget policies |
|---|---|
| 4096 (previous default) | `local-balanced`, `local-rigorous` |
| 8192 | `local-rigorous` |
| 12288 | `local-rigorous` |
| **16384** | none |

Shipped defaults are now 16384 (`application.yml`, both M1 compose files) and 8192 for Intel, whose 3B models at 700 output tokens produce less evidence. `ShippedContextBudgetTest` fails if any shipped policy goes back over budget — verified to fail by reverting the default to 4096.

Cost is KV cache, roughly 2 GiB per resident 8B-class model at 16384 versus 0.5 GiB at 4096. Documented in the README together with the levers for smaller machines.

### 4.10 Deviations in the delivered 1A

1. **`orderedStages` is prevented by the type, not by a rule.** `UserProtocol` has no such component, so strict binding rejects it at parse time. A test asserts the record component does not exist, which is a stronger guarantee than a validation branch someone could later delete.
2. **The cascade sweep is defensive, not load-bearing.** Because models are validated before policies and policies before profiles, an entity referencing a rejected one is caught directly. The fixed-point loop remains as a safety net for future orderings.
3. **Ids may not end in a hyphen.** `^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$`. Ids become map keys and URL path segments; a trailing separator is a nuisance rather than a choice. Found by a boundary test.
4. **`SecretScanner` patterns are anchored at key position** rather than matching substrings, because `defaultOutputTokens` and `contextWindowTokens` contain "token" and a loose pattern would reject ordinary configuration.
5. **The prompt-budget validation hook stayed in `CouncilConfigurationValidator`.** It applies to the merged catalog, so user policies are covered by the existing check without duplicating it in `UserConfigValidator`.
6. ~~**`CouncilProperties` runtime/health binding was not added.**~~ **Closed.** `council.runtime.*` is now bound via `RuntimeProps`, resolved into `CouncilRuntimeSettings` on the catalog, and read from there by `CouncilRunExecutor`, `ChatCouncilService`, and `LocalArtifactStore`. The overlay's `runtime` section applies at boot. Live re-sizing of the executor's semaphore remains Phase 4 work; boot-time application is what Phase 1 promised ("restart to apply").

### 4.9 Tests

- `UserConfigLoaderTest` — absent file, malformed YAML, unknown top-level key, unknown field inside a model.
- `UserConfigValidatorTest` — one case per boundary row in §2: each clamp at min-1, min, max, max+1; `testOnly` rejection; `mock` provider rejection; `orderedStages` rejection; unknown stage-option key rejection; credential-pattern rejection.
- `CatalogMergerTest` — new entity, override entity, per-key map merge, list replacement, cascade removal to fixed point, origin stamping.
- `UserConfigBootTest` — `@SpringBootTest` with a deliberately broken user config on the classpath; asserts the context starts and the catalog's `issues` section reports the errors.
- `PromptBudgetTest` — under-budget input passes through byte-identical; over-budget input truncates the variable sections but never the instructions or question; markers are present; the warning and event fire exactly once per truncated stage.

**Done when:** dropping a `council-user.yml` with a new Ollama model and a profile referencing it makes that profile appear in `GET /api/council/catalog?include=profiles` and run successfully; corrupting one entity in that file leaves every other profile working and reports the failure in the `issues` section.

---

## 5. Phase 2 — Web UI, read and run

**Goal:** a usable chat UI with a live council timeline. No config editing yet.
**Depends on:** Phase 0.

### 5.0 What the UI must not bury

The council's value is not the answer alone — a single model produces one of
those in a fraction of the time. The value is knowing **how much to trust it**:
whether members genuinely disagreed, whether the validator was independent,
whether the chair saw all the evidence. Every one of those signals already
exists in the API; a UI that renders the answer prominently and hides the rest
would reduce the product to an expensive single-model call with extra latency.

The governing principle, applied throughout the phases so far, is **degrade the
claim, never silently the guarantee**. In the UI that means:

| Signal | Source | Rendering rule |
|---|---|---|
| Sycophancy warnings | `CouncilRunResponse.sycophancyWarnings`, plus `DEBATE_SYCOPHANCY_WARNING` events | **Never collapsed by default.** A council whose members echoed each other reached consensus without substance; that is the failure this product exists to detect. |
| Preserved dissent | inside the synthesised answer | Render as its own section, not as trailing prose. Users skim to the recommendation and stop. |
| `validationIndependence` | `CouncilRunResponse`, `catalog.policies[]` | A persistent badge. A `SELF_VALIDATION` run must never look like an `INDEPENDENT` one — a "validated" marker makes a reader trust an answer *more*, so an unqualified checkmark on self-review is actively misleading. |
| `warnings` | `CouncilRunResponse.warnings` | Includes `CONTEXT_BUDGET_EXCEEDED`. A run synthesised from truncated evidence must say so on the result, not only in the boot log. |
| `excludedModels` / `modelFailures` | `CouncilRunResponse` | A 3-member council that silently ran with 2 is a different result. Show the roster that actually participated. |
| Profile health | `GET /profiles/{id}/health` | **The highest-value element in the whole UI.** It converts the most common failure — a model that was never pulled — from a confusing `QUORUM_NOT_MET` after several minutes into an obvious message before sending. |

Two rules follow from the table:

1. **Confidence must be qualified by its own provenance.** Never show a
   confidence figure or a validation verdict without the independence tier next
   to it. The number means something different when the chair graded itself.
2. **Absence of a signal is not the same as absence of a problem.** When a
   protocol skips validation (QUICK) or debate never ran, say so explicitly
   rather than leaving an empty panel that reads as "nothing to report".

### 5.1 Files

```
src/main/resources/static/
├── index.html            # chat view (default route)
├── config.html           # Phase 3 placeholder in this phase
├── css/app.css           # single stylesheet, CSS custom properties, light + dark via prefers-color-scheme
└── js/
    ├── api.js            # thin fetch wrapper over /api/council/**; one place for error mapping
    ├── sse.js            # EventSource lifecycle: connect, retry with backoff, dedupe by event id
    ├── chat.js           # chat list, message composer, turn rendering
    ├── timeline.js       # council stage timeline + artifact drill-down
    └── health.js         # profile picker with live health badge
```

Add `WebMvcConfigurer` only if a SPA fallback route is needed; with `index.html` at the static root, Spring Boot serves it at `/` with no Java changes. Prefer zero Java.

### 5.2 Chat view (`index.html`)

Three-pane layout, collapsing to one column below 768px:

- **Left:** chat list from `GET /api/council/chats`, "New chat" button, delete affordance.
- **Centre:** turn stream. User messages plain; assistant turns render markdown. Turn states map to `ChatTurnStatus` — `RUNNING` shows the live timeline inline, `PARTIAL` shows the answer with a warning banner carrying `failureReason`, `FAILED` and `REJECTED` show the reason and a retry button.
- **Right:** run inspector — the selected turn's council detail.

New-chat flow: profile dropdown from `GET /api/council/catalog?include=profiles`, depth radio, then a **preflight** call to `GET /api/council/profiles/{id}/health?depthMode={depth}` rendered as a green/amber/red badge with per-model detail. If `runnable` is false, disable send and show each model's `detail` plus its remediation (for Ollama, `ProviderHealth.knownProviderModels` gives you "installed: llama3.1:8b, mistral:7b" for free). **This is the single highest-value UI element** — it converts the most common failure mode (quorum error from a model that was never pulled) into an obvious pre-send message.

### 5.3 Council timeline (`timeline.js`)

Subscribe to `GET /api/council/chats/{id}/events`. The stream already multiplexes three event names: `snapshot` (a `ChatResponse`), `chat` (`ChatEvent`), and `council` (`CouncilEvent`).

Render the council stream as an ordered stage list driven by `STAGE_STARTED` / `STAGE_COMPLETED` / `STAGE_FAILED` / `STAGE_SKIPPED`, with `MODEL_CALL_FAILED` attaching an inline error to the owning stage. Show elapsed time per stage.

Expand a completed stage to show its evidence, fetched lazily from `GET /api/council/sessions/{id}/artifacts` then `.../artifacts/{path}`:

| Stage | Panel content |
|---|---|
| `GENERATE` | one card per draft, model id labelled |
| `ANONYMIZE` | the alias mapping — this is what makes anonymized review legible rather than magic |
| `REVIEW` | reviewer → draft matrix with per-criterion scores |
| `SCORE` | ranked drafts, strategy name, variance |
| `DEBATE` | per-round contributions, convergence figure, **sycophancy warnings displayed prominently** |
| `SYNTHESIZE` | final answer + preserved dissent section |
| `VALIDATE` | verdict, confidence, issues |

Two product rules for this view: **sycophancy warnings and preserved dissent are never collapsed by default**, and a run with `integrityReduced: true` (§2.4) carries a persistent badge. The anti-sycophancy machinery is the reason the product exists; the UI should make it visible rather than bury it behind a disclosure triangle.

### 5.4 SSE robustness

`ChatController.events` currently replays full history on connect and has no cursor. For Phase 2, handle it client-side: track the highest-seen event sequence per stream and drop duplicates on reconnect. Reconnect with exponential backoff capped at 30s. A server-side `Last-Event-ID` cursor is deferred; note it in the code as a known limitation matching `CLAUDE.md`.

### 5.6 Token and cost accounting (fixes F3)

The UI is what makes this urgent: once a send button sits in front of `multi-cloud-rigorous` (30–40 cloud calls per question), invisible spend becomes a real problem.

- Add `recordUsage(String modelId, StageType stage, Long promptTokens, Long completionTokens, Duration latency)` to `CouncilContext`, backed by a `CopyOnWriteArrayList<UsageRecord>` for consistency with the existing concurrent collections.
- Call it at **all 8** executor `.call(` sites. Every one currently discards everything but `.text()`. Token fields are nullable by contract — a null contributes zero tokens and sets `estimated: true` on the aggregate.
- Add `costPer1kInputTokens` / `costPer1kOutputTokens` to `ModelProps` (editable per §2.1, default `0.0`, clamp 0 – 1000). Zero means "unpriced", not "free" — render it as "—", never as "$0.00", so an unpriced cloud model is never mistaken for a free one.
- Add `UsageSummary` to `CouncilRunResponse`: total tokens, per-model and per-stage breakdown, estimated cost, and `estimated` when any provider omitted usage data.
- UI: a running token/cost counter in the timeline, and a **pre-send estimate** next to the health badge derived from the policy's member count, protocol stages, and `defaultOutputTokens`. Order-of-magnitude accuracy is enough; the goal is preventing surprise, not billing.

### 5.7 Run cancellation (fixes F4)

- `CouncilRunExecutor.submit` currently discards the `Future`. Retain it in a `ConcurrentHashMap<String, Future<?>>` keyed by session id, removed in the existing `finally` block alongside the permit release.
- Add `volatile boolean cancelled` plus `cancel()` / `isCancelled()` to `CouncilContext`.
- `ProtocolOrchestrator` checks `context.isCancelled()` at the top of the stage loop — the same place it already checks `isTerminal()` — and emits `STAGE_SKIPPED` with reason `cancelled` for each remaining stage. **Cancellation is honoured at stage boundaries only.** An in-flight model call is not interrupted; it completes and its result is discarded. Document this, because a user cancelling a 4-minute Ollama generation will wait for that call to finish and the UI must say so.
- `DELETE /api/council/sessions/{id}/run` → 202 with the current status. Sets the flag, then `future.cancel(false)`. Never `cancel(true)` — interrupting a virtual thread mid-HTTP-call leaves the Ollama connection in an undefined state.
- Session transitions to `CANCELLED` (the enum value already exists and is currently unused). The chat turn transitions to `FAILED` with reason `"Cancelled by user"`.
- This matters more than it looks: with `max-concurrent-runs` defaulting to 1, an unwanted run blocks every other run until it finishes.

### 5.8 Tests

- `StaticResourceTest` — `GET /` returns 200 `text/html`; `GET /js/app.js` resolves.
- `ChatListingApiTest` — create two chats, list, delete, verify 409 while running.
- `UsageAccountingTest` — usage accumulates across stages; null token fields degrade to `estimated: true`; cost maths is correct for a known token count.
- `CancellationTest` — cancel between stages skips the remainder and lands `CANCELLED`; cancelling an already-finished run is a no-op returning current status, not an error.
- No JS test framework. Keep the JS free of build steps and verify by hand against the mock profile.

**Done when:** `profileId=mock` end-to-end in a browser — create chat, send message, watch stages stream, expand drafts and reviews, see the final answer, cancel a run mid-flight, and see a token count.

---

## 6. Phase 2A — Durable persistence (SQLite / H2)

**Goal:** sessions, chats, and events survive a restart.
**Depends on:** Phase 2 (the UI is what makes durability matter — chat history that vanishes on restart reads as broken rather than demo-grade).
**Independent of** the catalog refactor, so this can proceed in parallel with Phases 3–4.

### 6.1 One implementation, two engines

Per D6. SQLite and H2 are not two stores — they are one `JdbcSessionStore` with a different `spring.datasource.url` and a different driver on the classpath. Postgres and MySQL come free later by the same route.

```yaml
council:
  persistence:
    type: ${LLM_COUNCIL_PERSISTENCE:memory}     # memory | jdbc
    artifactBasePath: ...                        # unchanged — artifacts stay on the filesystem
spring:
  datasource:
    url: ${LLM_COUNCIL_JDBC_URL:jdbc:sqlite:${user.home}/.llm-council/council.db}
```

Selection is `@ConditionalOnProperty(name = "council.persistence.type", havingValue = "jdbc")` on the JDBC beans and `havingValue = "memory", matchIfMissing = true` on the existing in-memory ones. `memory` stays the default so tests, the mock profile, and existing users are untouched.

**SQLite is the recommended default** for personal use: a single file, no daemon, no port, nothing to install. H2 file mode is the fallback for anyone who would rather not add a native dependency. Add both drivers as `runtime` scope:

```xml
<dependency><groupId>org.xerial</groupId><artifactId>sqlite-jdbc</artifactId><scope>runtime</scope></dependency>
<dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
```

**A filesystem store for sessions is deliberately out of scope.** Artifacts are blobs and `LocalArtifactStore` already handles them correctly — that stays. But sessions and chats as loose JSON files means no atomic update, no ordering without reading every file, and a directory scan for every listing: a hand-rolled, worse database. SQLite already *is* the single-file option, and is durable and queryable besides. If a filesystem store is added later it should be for artifact portability, not for session state.

### 6.2 Do not use JPA — the domain is records

This is the decision most likely to be got wrong. `CouncilSession`, `CouncilEvent`, and `ChatTurn` are Java `record`s. **JPA entities cannot be records** — Hibernate requires a no-arg constructor, non-final fields, and setters. Adding `spring-boot-starter-data-jpa` therefore forces a parallel entity model plus a mapping layer, duplicating the domain for a query surface that is only `findById`, `findAll` ordered by `updatedAt`, and `delete`.

Use **JSON-document-in-a-row over `JdbcTemplate`** (`spring-boot-starter-jdbc`, already transitively available). Scalar columns exist only for filtering and ordering; the record itself round-trips through the existing Jackson `ObjectMapper`. Records stay records, there is no ORM, and adding a field to `CouncilSession` needs no migration at all.

### 6.3 Schema

```sql
CREATE TABLE IF NOT EXISTS council_session (
  id            VARCHAR(64) PRIMARY KEY,
  profile_id    VARCHAR(64) NOT NULL,
  status        VARCHAR(32) NOT NULL,
  created_at    TIMESTAMP   NOT NULL,
  updated_at    TIMESTAMP   NOT NULL,
  document      TEXT        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_session_updated ON council_session(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_status  ON council_session(status);

CREATE TABLE IF NOT EXISTS chat_session (
  id         VARCHAR(64) PRIMARY KEY,
  profile_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP   NOT NULL,
  updated_at TIMESTAMP   NOT NULL,
  document   TEXT        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_updated ON chat_session(updated_at DESC);

CREATE TABLE IF NOT EXISTS council_event (
  id          VARCHAR(64) PRIMARY KEY,
  session_id  VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMP   NOT NULL,
  seq         BIGINT      NOT NULL,
  stage       VARCHAR(32),
  type        VARCHAR(48) NOT NULL,
  model_id    VARCHAR(64),
  payload     TEXT        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_session ON council_event(session_id, seq);
```

`seq` is a per-session monotonic counter assigned on publish. It exists for the SSE reconnect cursor (§5.4 currently handles dedupe client-side) and for deterministic replay ordering, which `occurred_at` alone cannot guarantee at millisecond resolution under virtual-thread fan-out.

**Portability:** the DDL is the only engine-specific part — `TEXT` vs `LONGTEXT` vs `CLOB`. Use **Flyway** with vendor-specific migration locations (`db/migration/{vendor}`). With a JSON-column design the schema will almost never change, so Flyway is near-zero maintenance and far cheaper than retrofitting versioning onto databases that already hold user data.

### 6.4 New files

| File | Responsibility |
|---|---|
| `persistence/jdbc/JdbcSessionStore.java` | `SessionStore` over `council_session`. Upsert via `MERGE`/`INSERT … ON CONFLICT` per vendor. |
| `persistence/jdbc/JdbcChatSessionStore.java` | `ChatSessionStore` over `chat_session`, including `findAll` and `delete` from §3.7. |
| `persistence/jdbc/JdbcEventStore.java` | `EventStore` over `council_event`. |
| `persistence/jdbc/DocumentMapper.java` | Shared `RowMapper` + serialize helpers; one place for the Jackson round-trip. |
| `chat/ChatSessionSnapshot.java` | Serialization form for `ChatSession` — see §6.5. |
| `application/EventStore.java`, `application/EventBroker.java` | The split described in §6.6. |
| `persistence/RetentionService.java` | §6.8. |
| `application/InterruptedRunSweeper.java` | §6.9. |

### 6.5 `ChatSession` needs a snapshot type

Unlike the other domain types, `ChatSession` is a mutable `synchronized` class with a private `List<ChatTurn>`, no no-arg constructor, and no setters — Jackson cannot round-trip it as-is.

Add a package-private record and two methods:

```java
record ChatSessionSnapshot(String id, String profileId, DepthMode depthMode,
                           String summary, List<ChatTurn> turns,
                           Instant createdAt, Instant updatedAt) {}

// on ChatSession:
synchronized ChatSessionSnapshot toSnapshot() { ... }
static ChatSession fromSnapshot(ChatSessionSnapshot snapshot) { ... }
```

**Do not add Jackson annotations to `ChatSession` itself.** Its synchronization is load-bearing — `handleCompletion` in `ChatCouncilService` mutates turns from virtual threads while SSE readers call `turns()`. `fromSnapshot` needs a constructor that accepts `createdAt`/`updatedAt` rather than calling `Instant.now()`, so add a private full constructor.

### 6.6 Split `EventPublisher` into store and broker

**Constraint discovered during Phase 2, which shapes the cursor design:** the chat
SSE stream multiplexes three independently-ordered sources — the chat snapshot,
the chat event log, and one council event log per turn. `Last-Event-ID` carries a
single id, which locates a position in whichever source sent last and says
nothing about the others; resuming from it would skip events on every source but
one. So `since(sessionId, seq)` alone is not enough. Either the cursor is
composite (one position per source, encoded into the frame id), or all three
sources share one monotonic sequence. **Prefer the shared sequence** — a single
`seq` allocated per stream connection across all sources makes the cursor a
single integer and keeps the client's dedupe as a backstop rather than the
mechanism. Decide this before implementing `since(...)`, not after.

`EventPublisher` currently conflates two jobs: `publish`/`history` are persistence, `subscribe` is live pub/sub for SSE. A durable implementation still needs the in-memory subscriber fan-out — you cannot serve an SSE stream from a table.

```java
public interface EventStore  { CouncilEvent append(CouncilEvent e); List<CouncilEvent> history(String sessionId); List<CouncilEvent> since(String sessionId, long seq); }
public interface EventBroker { void publish(CouncilEvent e); AutoCloseable subscribe(String sessionId, Consumer<CouncilEvent> listener); }
```

Keep `EventPublisher` as a thin composite that appends then publishes, so **no existing caller changes**. `InMemoryEventPublisher` becomes `InMemoryEventStore` + `InMemoryEventBroker`; the broker is always in-memory regardless of `persistence.type`.

`since(sessionId, seq)` is what finally allows a proper server-side `Last-Event-ID` cursor, closing the SSE reconnect gap named in `CLAUDE.md`. Wire it into `ChatController.events` in this phase: read the `Last-Event-ID` header, replay from `since(...)` instead of full `history(...)`, then subscribe.

### 6.7 Write path and ordering

`CouncilService.runCouncil` already does save → run → save, and each save is independent, so no transaction spanning a run is needed or wanted — a council run takes minutes and must never hold a database transaction open. Individual `save` calls are single-statement upserts.

Event writes are on the hot path of every stage. Append synchronously (they are small, and SQLite handles thousands of inserts per second), but **never let an event-store failure fail a council run**: catch, log at `WARN`, and continue. Losing an observability record is acceptable; losing a 10-minute council run because a disk was full is not.

### 6.8 Retention (fixes F5)

Durability moves unbounded growth from RAM to disk; it does not remove it. Add `RetentionService` as a `@Scheduled` task (hourly) plus a one-shot run at boot:

```yaml
council:
  persistence:
    retention:
      maxSessions: ${LLM_COUNCIL_MAX_SESSIONS:500}
      maxAgeDays: ${LLM_COUNCIL_MAX_AGE_DAYS:90}
      maxEventsPerSession: ${LLM_COUNCIL_MAX_EVENTS_PER_SESSION:2000}
```

Delete sessions exceeding either bound, oldest first, and cascade to their events and artifact directories. **Never delete a session in `RUNNING` or `CREATED` status** regardless of age. The in-memory stores get the same caps so the eviction gap is closed for `type: memory` users too.

### 6.9 Interrupted-run recovery

Durable sessions make this possible for the first time, and it closes the "queued-run recovery" gap named in `CLAUDE.md`.

- Add `INTERRUPTED` to `CouncilStatus`.
- `InterruptedRunSweeper` runs as an `ApplicationRunner` at boot: every session in `RUNNING` status is by definition orphaned, because no run survives a restart. Transition each to `INTERRUPTED` with `failureReason: "Run was interrupted by an application restart"`, and mark any owning chat turn `FAILED` with the same reason.
- Without this, the UI shows a spinner forever on a run that will never finish. Roughly 20 lines, and it turns a silent hang into an honest error.

### 6.10 Tests — keep `mvn test` hermetic

`CLAUDE.md` promises the suite is fast and hermetic with no network. Preserve that.

- Write one **abstract contract test per interface** — `SessionStoreContractTest`, `ChatSessionStoreContractTest`, `EventStoreContractTest` — with an abstract `createStore()` factory. Subclass once per implementation. This is the pattern that keeps the in-memory and JDBC stores behaviourally identical.
- Run the JDBC subclasses against **in-process H2 and SQLite only**, on a temp-file database per test via `@TempDir`. Both are ordinary jars — no daemon, no container, no network.
- **Do not add Testcontainers to the default build.** MySQL/Postgres exercise a code path identical to the one H2 already covers. If real-engine coverage is ever wanted, put it behind a `-Pintegration` Maven profile that CI runs and `mvn test` does not.
- `ChatSessionSnapshotTest` — round-trip preserves turn order, status, timestamps, and summary; `createdAt` is not reset by `fromSnapshot`.
- `RetentionServiceTest` — bounds enforced oldest-first; `RUNNING` sessions never deleted.
- `InterruptedRunSweeperTest` — a `RUNNING` session at boot becomes `INTERRUPTED` and its chat turn becomes `FAILED`.

**Done when:** with `LLM_COUNCIL_PERSISTENCE=jdbc`, a chat with three turns survives a restart, appears in `GET /api/council/chats`, and a run killed mid-flight shows as `INTERRUPTED` rather than spinning.

---

## 7. Phase 2B — Session resume and re-run

**Goal:** users can return to previous work.
**Depends on:** Phase 2A (nothing is resumable if nothing is persisted).

### 7.1 Three different features behind one word

Per D9. Scope these separately — conflating them means building the expensive one when the cheap two were what was wanted.

| | Meaning | Cost | Where |
|---|---|---|---|
| **R1** | **Continue a chat.** Reopen an old conversation and keep asking. | Nearly free | §7.2 |
| **R2** | **Re-run a question.** Take an old session's question and run it again, optionally with a different profile or depth. | Small | §7.3 |
| **R3** | **Resume an interrupted run.** Continue a run that died mid-pipeline, from the last completed stage. | The real work | §7.4 |

### 7.2 R1 — Continue a chat (already works)

`POST /api/council/chats/{id}/messages` already accepts a new message on any existing chat, and `ChatCouncilService.buildCouncilContext` already rebuilds context from the stored summary plus recent turns. **No API change is required.** What was missing is only that chats did not survive restart (Phase 2A) and could not be listed (§3.5).

One addition: allow overriding profile and depth for a single turn, since a user resuming an old chat often wants a different depth than they started with.

```
POST /api/council/chats/{chatId}/messages
{ "message": "...", "profileId": "local", "depthMode": "RIGOROUS" }   // both optional
```

Both fields are optional and default to the chat's own settings. When supplied, store them **on the turn**, not on the chat — turns are already the unit that carries a `councilSessionId`, and rewriting chat-level settings would retroactively misdescribe earlier turns.

### 7.3 R2 — Re-run a question

```
POST /api/council/sessions/{id}/rerun
{ "profileId": "multi-cloud", "depthMode": "RIGOROUS" }   // both optional
```

Creates a **new** session with the same `question` and `context`, the supplied (or inherited) profile and depth, and a `rerunOf` field pointing at the original. Never mutates the original — council runs are evidence, and overwriting them destroys the audit trail the artifact store exists to provide.

Add `String rerunOf` to `CouncilSession` (nullable) and surface it in `SessionResponse`. The UI shows "re-run of <question>" with a link, and offers this from any completed, failed, or interrupted run. This is the natural answer to "that local run was disappointing, try it on the cloud council" and is far cheaper than R3.

### 7.4 R3 — Resume an interrupted run from a checkpoint

The genuinely valuable case: a `RIGOROUS` local run is ~10 minutes and 30+ model calls, and on a cloud profile it costs real money. Losing it to a restart is expensive.

This is tractable because the design already cooperates: `ProtocolOrchestrator.run` is a plain indexed `for` loop over `protocol.orderedStages()`, stage executors are stateless, `CouncilContext` is the single state object, and everything it accumulates (`Draft`, `ReviewArtifact`, `ScoreArtifact`, `DebateRound`, `ValidationArtifact`, `ModelFailure`) is a record.

#### 7.4.1 Checkpoint by stage *index*, never by stage type

**This is the detail most likely to be got wrong.** The `rigorous` protocol lists `SCORE` twice:

```yaml
orderedStages: [GENERATE, ANONYMIZE, REVIEW, SCORE, DEBATE, REVISE, REVIEW_POST_DEBATE, SCORE, SYNTHESIZE, VALIDATE, EXPORT]
```

A checkpoint recording "completed stages: [GENERATE, ANONYMIZE, REVIEW, SCORE]" is ambiguous — it cannot distinguish the first `SCORE` from the second, and a resume keyed by type would skip the post-debate scoring entirely and silently produce a differently-scored answer. **Checkpoint the integer index of the last completed stage.** Resume starts the loop at `lastCompletedIndex + 1`.

#### 7.4.2 `CheckpointStore`

```java
public interface CheckpointStore {
    void save(String sessionId, RunCheckpoint checkpoint);
    Optional<RunCheckpoint> find(String sessionId);
    void delete(String sessionId);
}

public record RunCheckpoint(
        String sessionId,
        String policyId,
        String protocolId,
        long catalogGeneration,      // §3.1 — guards against config drift
        int lastCompletedStageIndex,
        Instant savedAt,
        ContextSnapshot context
) {}
```

`ContextSnapshot` mirrors `CouncilContext`'s accumulated state: drafts, reviews, scores, debate rounds, excluded models, model failures, warnings, sycophancy warnings, synthesis result, score summary, validation, plus usage records from §5.6.

**`failureCause` is a `Throwable` and must not be serialized.** Persist `failedStage`, the failure category, and the message as strings; on rehydration reconstruct a plain exception carrying that message. Attempting to serialize arbitrary exception graphs is a reliable source of failure.

Implement over the existing `ArtifactStore` (`writeJson(sessionId, "checkpoint.json", …)`) rather than a new table. Checkpoints are per-session blobs, they are exactly what the artifact store is for, and this keeps them working for `persistence.type: memory` users too.

#### 7.4.3 Orchestrator changes

- `ProtocolOrchestrator.run(session, profile, policy, RunCheckpoint resumeFrom)`; the existing 3-arg signature delegates with `null`.
- When `resumeFrom` is non-null, construct `CouncilContext` and rehydrate it, then start the loop at `lastCompletedStageIndex + 1`. Emit `PROTOCOL_RESUMED` with the resume index, and `STAGE_SKIPPED` with reason `already completed in previous attempt` for each skipped stage, so the timeline stays honest about what actually ran in this attempt.
- After each successful stage, write the checkpoint. Do this **after** the `STAGE_COMPLETED` event so a checkpoint always represents a state the event stream has already reported. Wrap in try/catch — a checkpoint write failure must degrade to a `WARN` and let the run continue, never kill a run that is otherwise healthy.
- Delete the checkpoint on `PROTOCOL_COMPLETED`. Retain it on failure so the run stays resumable.

`CouncilContext` needs a rehydration path: add a package-private constructor or `restoreFrom(ContextSnapshot)`. Keep the collections `CopyOnWriteArrayList` and populate via `addAll`.

#### 7.4.4 Config drift guard

If configuration changed between the crash and the resume, the resumed run would mix evidence produced under two different catalogs — different models, quorum, or stage options. That is a silent correctness hazard.

Compare `checkpoint.catalogGeneration` with the current `CouncilCatalog.generation`, and also compare `policyId`/`protocolId`:

- **Match** → resume normally.
- **Mismatch** → refuse by default with `409` and a message naming what changed, offering R2 (re-run) as the alternative. `?force=true` resumes anyway and adds a permanent `ctx.addWarning(...)` recording that the run spans two configurations.

#### 7.4.5 API

```
GET  /api/council/sessions/{id}/checkpoint     → {resumable, lastCompletedStage, stageIndex,
                                                  totalStages, savedAt, configDrift}
POST /api/council/sessions/{id}/resume?force=  → 202, CouncilRunResponse (async, like chat runs)
```

`resume` is valid only from `INTERRUPTED` or `FAILED` status. From `COMPLETED` it returns `409` pointing at `/rerun`. It acquires a permit from `CouncilRunExecutor` exactly as a fresh run does, so `max-concurrent-runs` still applies.

#### 7.4.6 Where resume is not worth it

Do not attempt sub-stage resume. If `GENERATE` fanned out to five models and died after three, the resumed run re-runs all five. Tracking partial fan-out state would mean checkpointing inside the virtual-thread executor, and the saving does not justify the complexity or the concurrency risk. **Stage boundaries are the only checkpoint granularity.** Say so in the Javadoc so nobody later mistakes it for an oversight.

### 7.5 UI

- Chat list (§5.2) gains a "Resume" affordance on any chat whose last turn is `FAILED` with an interrupted reason.
- A session detail view shows the checkpoint state as "Stopped after REVIEW (4 of 11 stages)" with **Resume** and **Re-run** buttons, and disables Resume with an explanatory tooltip when `configDrift` is true.
- Skipped-because-already-completed stages render visually distinct from skipped-because-failed. A user must be able to tell what ran in this attempt versus what was inherited.

### 7.6 Tests

- `RunCheckpointTest` — round-trip of every `ContextSnapshot` field; `Throwable` is not serialized; the reconstructed failure carries the original message.
- `StageIndexResumeTest` — **the `rigorous` double-`SCORE` case explicitly.** Resume after index 3 (first `SCORE`) must still execute index 7 (second `SCORE`). A type-keyed implementation fails this test; that is the point of it.
- `ResumeConfigDriftTest` — generation mismatch returns 409; `force=true` resumes and records the warning.
- `ResumeApiTest` — resume from `COMPLETED` returns 409 pointing at rerun; resume respects the concurrency permit.
- `RerunTest` — creates a new session, leaves the original untouched, sets `rerunOf`.

**Done when:** kill the app mid-`DEBATE` on a rigorous run, restart, see the session as `INTERRUPTED`, resume it, and watch it continue from the correct stage index with the earlier drafts and reviews intact.

---

## 8. Phase 3 — Configuration UI (writes the overlay, restart to apply)

**Goal:** edit the Phase 1 overlay through forms instead of a text editor.
**Depends on:** Phases 1, 2.

### 8.1 New endpoints (`api/ConfigController.java`, `/api/council/config`)

| Method | Path | Body → Response | Notes |
|---|---|---|---|
| GET | `/draft` | → `UserConfigDocument` | Current overlay file contents, or an empty document. |
| POST | `/validate` | `UserConfigDocument` → `ValidationReportResponse` | **Pure function, no writes.** Runs §2 validation against the live built-in catalog. This is the endpoint the UI calls on every form change. |
| POST | `/preview` | `UserConfigDocument` → `CatalogDiffResponse` | Merged-catalog diff: added / overridden / removed entities, plus resulting profile list. |
| PUT | `/draft` | `UserConfigDocument` → `ValidationReportResponse` | Writes the overlay file **only if there are zero `ERROR` issues**. Writes atomically (temp file + `ATOMIC_MOVE`) and keeps one `.bak`. Returns `restartRequired: true`. |
| GET | `/schema` | → `ConfigSchemaResponse` | Machine-readable form spec: field names, types, clamps, enum values, help text — generated from `StageOptionSpec` and the §2 rules, not hand-written in JS. |
| GET | `/export` | → YAML download | For sharing a setup. |
| POST | `/import` | YAML → `ValidationReportResponse` | Validate-only; the user still confirms before `PUT /draft`. |

The `/schema` endpoint matters: it keeps the clamp table in exactly one place. If the UI hard-codes ranges, they will drift from the validator within one release.

### 8.2 UI (`config.html`)

Four tabs: **Models**, **Policies**, **Profiles**, **Protocols**, plus a **Providers** panel.

- Built-in entities render read-only with an "Override" button that clones them into the draft.
- Every field is generated from `GET /api/council/config/schema` — ranges become `min`/`max` on number inputs, enums become selects.
- Live validation: debounce 300ms, `POST /validate`, render issues inline against the offending field via `ConfigIssue.field`.
- Before save: show the `POST /preview` diff and require explicit confirmation.
- After save: a persistent "Configuration saved — restart to apply" banner. **Do not fake a live apply in this phase.**
- **Providers panel** implements D2: a table of provider → active/inactive → the exact env var to set. It contains no input fields. Copy for an inactive provider reads, verbatim: *"Set `SPRING_AI_ANTHROPIC_API_KEY` in your environment or `.env` file and restart. This application never stores API keys."*

### 8.3 Tests

- `ConfigControllerTest` — validate rejects each §2 violation with the right `field`; `PUT /draft` refuses to write when errors exist; atomic write leaves a `.bak`; the secret scanner rejects a document containing an `api-key` field with a 400 that does not echo the value.
- `ConfigSchemaTest` — every `StageOptionSpec` entry appears in the schema response; asserts the count so a new option cannot be added without appearing in the UI.

---

## 9. Phase 4 — Hot reload

**Goal:** applying config no longer requires a restart.
**Depends on:** Phase 3.

### 9.1 Changes

1. `CouncilCatalogHolder` gains `void swap(CouncilCatalog next)` — a single `AtomicReference.set` after the new catalog is fully built and validated. Increment `generation`.
2. New `config/CouncilCatalogFactory.java` — extract catalog construction out of `CouncilConfig` so it can run at boot **and** on demand. Boot path and reload path must share one code path; two paths will diverge.
3. **Client lifecycle.** Rebuilding the catalog rebuilds `ModelClient` instances. `OllamaDirectModelClient` holds only a URI, `SpringAiModelClient` wraps an injected `ChatClient`, `RetryableModelClient` wraps another client — none hold pooled connections or threads today, so discarding them is safe. **Verify this before implementing.** If any client ever gains a resource, `ModelClient` needs a `close()` and the old catalog needs draining once no run references it.
4. **Migrate the 8 stage executors** from injected `ModelRegistry` to `ctx.modelRegistry()`. Mechanical, but it is what makes in-flight runs immune to a swap. Remove the `ModelRegistry` constructor parameter from each.
5. Remove `ProtocolDefinitionRegistry` entirely; `ProtocolOrchestrator` reads protocols from `ctx.catalog()`.
6. `POST /api/council/config/apply` — validate, build, swap, return the new `generation` and issue list. Rejects with 409 if any run is active, unless `?force=true`.
7. `CouncilRunExecutor.maxConcurrentRuns` is a `Semaphore` sized at construction. A changed value takes effect by replacing permits: track the configured size and `release`/`acquire` the delta. Do not recreate the executor while runs are in flight.
8. Broadcast a `CONFIG_RELOADED` event so open UI tabs refresh their profile lists.

### 9.2 Concurrency contract to document in code

> A council run resolves its catalog snapshot exactly once, at `CouncilService.runCouncil` entry, and stores it on `CouncilContext`. Every stage in that run sees the same models, policies, and protocol regardless of concurrent reloads. A reload affects only runs started after the swap.

### 9.3 Tests

- `CatalogSwapTest` — a run started before a swap completes against the old snapshot; a run started after uses the new one.
- `HotReloadIntegrationTest` — add a profile via `POST /apply`, assert it appears in `GET /profiles` and is runnable without restart.
- Re-run the full existing suite; the executor migration touches the most-tested classes in the repo.

---

## 10. Phase 5 — Requirement Advisor (wizard + CLI)

**Goal:** a non-technical user describes what they want in plain language and gets a validated configuration.
**Depends on:** Phases 1, 3. Phase 4 optional but makes it feel instant.

### 10.1 The core architectural rule

> **The LLM produces intent. Deterministic Java produces configuration.**

An LLM asked for YAML will hallucinate model ids, provider names, and stage types, and you will spend the rest of the phase validating garbage. Instead, the LLM's only job is mapping free text onto a small, closed, structured record. Everything downstream is testable, offline-capable Java.

```
free text ──(optional LLM)──▶ CouncilRequirement ──(deterministic)──▶ UserConfigDocument
                                      ▲                                        │
                              form fallback                                    ▼
                            (no model needed)                        validate → preview → confirm
```

### 10.2 `advisor/CouncilRequirement.java`

Closed enums only — no free-form strings that a synthesizer would have to interpret.

```java
public record CouncilRequirement(
        Privacy privacy,          // LOCAL_ONLY | CLOUD_OK | PREFER_LOCAL
        Latency latency,          // FAST | MODERATE | PATIENT
        Cost cost,                // FREE_ONLY | LOW | UNCONSTRAINED
        Rigor rigor,              // QUICK | BALANCED | RIGOROUS
        int councilSize,          // 1..8, clamped
        Set<Domain> domains,      // CODE | WRITING | ANALYSIS | RESEARCH | GENERAL
        boolean adversarialEmphasis
) {}
```

### 10.3 `advisor/ConfigSynthesizer.java` — deterministic, no LLM

Inputs: `CouncilRequirement` + the live catalog + `OllamaModelDiscoveryService.installedModels()` + provider availability from `CouncilConfig.hasRealCredential`.

Algorithm:

1. **Candidate pool.** `LOCAL_ONLY` → only `ollama` models whose `providerModelId` is actually installed. `CLOUD_OK` / `PREFER_LOCAL` → add models whose provider has a real credential. **Never propose a model that is not installed or whose provider is inactive** — this is the single biggest quality win over an LLM writing YAML.
2. **Diversity selection.** Choose `councilSize` members maximising distinct `modelFamily`. If only one family is available, emit a `WARNING` issue explaining that heterogeneity is reduced — do not silently proceed.
3. **Roles.** Assign `councilRole` round-robin `PROPOSER → CRITIC → PROPOSER…`; with `adversarialEmphasis`, weight to ≥50% `CRITIC`. Chair prefers the largest-context model with `role=CHAIR`; validator prefers a **different family from the chair** (Fresh Eyes is pointless if the validator is the chair's twin).
4. **Protocol.** `rigor` maps to `quick` / `balanced` / `rigorous`. `latency=FAST` with `rigor=RIGOROUS` derives a tuned protocol with `DEBATE.max-rounds: 2` and reports the trade-off.
5. **Quorum.** `minimumSuccessfulDrafts = max(1, ceil(size * 0.6))`; `minimumReviewsPerDraft = size >= 3 ? 1 : 0`.
6. Emit a `UserConfigDocument` for all three depths, plus a plain-English **rationale** list — one sentence per decision, quoting the requirement that drove it.

This class must be pure and fully unit-testable with no network and no model.

### 10.4 `advisor/RequirementExtractor.java` — the optional LLM front end

- Uses the configured local Ollama model via the existing `OllamaDirectModelClient`, with `jsonMode`.
- Prompt supplies the closed enum vocabulary and demands a single JSON object matching `CouncilRequirement`.
- Parse via `StructuredOutputParser`-style strict handling: unknown enum value → fall back to that field's default and record a note. **Never** let the model emit ids, providers, or stage names.
- If no Ollama model is available, or extraction fails, the wizard falls back to the form. Extraction is a convenience, never a dependency.
- Treat the user's free text as data. It must not be able to alter which providers are considered or bypass §2 — the synthesizer only ever reads the `CouncilRequirement` record, so this is structural rather than prompt-based.

### 10.5 Surfaces

**Web wizard** (`setup.html`) — 5 steps: describe (free text, or skip to the form) → review the extracted requirement as editable form controls → environment report (installed Ollama models, active providers, with remediation commands) → proposed config with per-decision rationale and a diff → confirm, which calls `PUT /api/council/config/draft` (Phase 3) or `POST /apply` (Phase 4).

**CLI** — a new `advisor/SetupCommandRunner.java` as an `ApplicationRunner` active only under `--council.setup`:

```bash
java -jar target/llm-council-2.0.0.jar --council.setup
```

Interactive prompts, same synthesizer, prints the YAML, asks before writing, exits without starting the web server (`spring.main.web-application-type=none` when the flag is present).

### 10.6 Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/council/advisor/environment` | Installed Ollama models, active providers, remediation hints |
| POST | `/api/council/advisor/extract` | free text → `CouncilRequirement` + confidence + `usedModel` (or `null` when the form fallback applies) |
| POST | `/api/council/advisor/synthesize` | `CouncilRequirement` → `{document, rationale, issues}` |

### 10.7 Tests

- `ConfigSynthesizerTest` — the bulk of the phase. One case per requirement combination: local-only with two installed models; local-only with **zero** installed models (must produce an actionable error, not an empty council); cloud-ok with only Ollama credentialed; `councilSize` exceeding available models; single-family pool warning; validator-family-differs-from-chair rule.
- `RequirementExtractorTest` — mocked `ModelClient` returning good JSON, malformed JSON, unknown enum values, and an outright refusal.
- `AdvisorEndToEndTest` — synthesize → validate → merge produces a runnable catalog with zero errors.

---

## 11. Sequencing and effort

| Phase | Deliverable | Rough size | Risk |
|---|---|---|---|
| 0 | Catalog seam, merged catalog endpoint, validator tiers (F1) | ~700 LOC, 6 changed classes | Low — mechanical, well covered by existing tests |
| 1 | User config overlay, shipped-config fix (F1), prompt budget (F2) | ~1100 LOC, 7 new classes | Medium — §2 validation is the bulk |
| 2 | Chat + timeline UI, cost accounting (F3), cancellation (F4) | ~1200 LOC JS/CSS/HTML, ~400 LOC Java | Low — read-only against existing APIs |
| 2A | Durable persistence (SQLite/H2), retention (F5), interrupted-run sweep | ~700 LOC, 8 new classes | Medium — contract tests carry it |
| 2B | Resume and re-run | ~600 LOC | Medium-high — the stage-index detail in §7.4.1 is the trap |
| 3 | Config UI + write endpoints | ~700 LOC | Medium — atomic write, schema generation |
| 4 | Hot reload | ~400 LOC, touches 10 classes | **High** — concurrency; do not start before Phase 2 is proven |
| 5 | Requirement Advisor | ~800 LOC | Medium — synthesizer is pure and testable; LLM part is optional by design |

Recommended order: **0 → 1 → 2 → 2A → 2B**, then 3 → 4 → 5.

Phases 0→1→2 are independently shippable and deliver most of the practical value. 2A and 2B are independent of the catalog refactor and can proceed in parallel with 3–4 if there is a second implementer. Phase 4 carries the only serious concurrency risk and is deliberately last among the config phases.

Dependency graph:

```
0 ──┬── 1 ──┬── 3 ── 4
    │       └── 5      (5 also needs 1)
    └── 2 ── 2A ── 2B
```

## 12. Invariants that must survive every phase

Verify each before declaring any phase done:

1. `mvn test` green, Java 25, no downgrade of `maven.compiler.release`.
2. `council.allowMockFallback` remains `false` and is not user-settable. `UnavailableModelClient` still produces actionable messages.
3. The public API still never accepts a raw `protocolId`. Callers pick `profileId` + `depthMode`; Phase 3 lets a user *define* a profile, not bypass one.
4. No credential is ever written to the overlay file, an artifact, an export, a log line, or an API response.
5. Built-in config errors remain fail-fast `IllegalStateException`; only the user layer is fail-soft.
6. `ANONYMIZE` and `REVIEW` are present in every protocol a user can select. Anti-sycophancy signals (sycophancy warnings, preserved dissent, `integrityReduced`) are surfaced, never hidden.
7. `DockerComposeConfigurationTest` reads the compose files as text — if any phase touches a compose file, update the test in the same commit.
8. Every new public type and method carries Javadoc with `@param`/`@return`, matching the existing files.
9. `mvn test` stays hermetic: no network, no containers, no daemon. Durable-store tests run against in-process H2/SQLite only; real-engine coverage lives behind `-Pintegration`.
10. `council.persistence.type` defaults to `memory`, so an existing user who pulls these changes sees no behaviour change until they opt in.
11. No endpoint is ever described as private, internal-only, or UI-only unless it is actually authenticated. `/api/ui/**` is a stability contract, not an access boundary (D8).
12. A council run resolves its catalog snapshot once and never re-reads it — true for hot reload (Phase 4) and for resume, where the checkpoint's `catalogGeneration` guards against config drift.
13. Re-running or resuming never mutates the original session. Council runs are evidence; the artifact trail must stay intact.

---

## 13. Open questions for later

Not blocking any phase, recorded so they are not rediscovered.

- **Overlay schema migration.** The user file carries `version: 1`. Before any built-in model or policy id is ever renamed, there must be an alias/deprecation path — user files reference built-ins by name, and a rename silently orphans them. Decide this before the first id rename, not after.
- **`SycophancyDetector` uses Jaccard similarity**, a lexical proxy. It catches copy-paste agreement but not semantic capitulation reworded. A known ceiling on that signal rather than a defect; revisit if debate quality data suggests it is missing real cases.
- **Sub-stage resume** is deliberately out of scope (§7.4.6). Revisit only if telemetry shows `GENERATE` fan-out repeatedly dying partway on slow local hardware.
- **Multi-user.** Everything here assumes a single trusted local caller. If that changes, the order of work is: shared-token filter → per-user ownership on sessions and chats → authorization on the Phase 3/4 config-write endpoints.
