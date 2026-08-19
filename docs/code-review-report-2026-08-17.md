# LLM Council code review

Date: 2026-08-17  
Scope: Java/Spring Boot/Spring AI backend, orchestration, configuration, persistence, REST/SSE APIs, static web UI, and tests.

## Bottom line

This is a serious implementation, not a toy prompt loop. The stage registry, pinned catalog snapshot, quorum handling, prompt budgeting, explicit partial/failure states, replayable event model, retention policy, configuration validator, and artifact trail are all sound design choices.

It was not correct enough to trust before this review. The most serious defects were in lifecycle concurrency, cancellation, timeout enforcement, post-debate evidence handling, result durability, deletion privacy, and artifact path containment. Those defects are now fixed and covered by regression tests.

Current verdict:

- **Good local/personal application and strong development platform.**
- **Not production-ready for untrusted network users.** It has no authentication or authorization and intentionally relies on loopback binding as its access-control boundary.
- **Do not market every shipped profile as a genuinely independent council.** The local rigorous and multi-cloud profiles have useful diversity. OpenAI, Claude, and Gemini are single-provider policies with correlated chair/validator families; the application reports that correlation, but a warning does not create independence.
- **The orchestration is internally consistent under the deterministic harness and the shipped three-model Ollama council was exercised live.** Repeatable contract tests are still needed before a release claim can extend to OpenAI, Anthropic, Gemini, or arbitrary Ollama versions deployed by users.

Indicative assessment after fixes:

| Area | Assessment | Direct conclusion |
|---|---:|---|
| Core orchestration correctness | 8/10 | Sound stage model and evidence trail; remaining weaknesses are primarily heuristic calibration and provider-output contracts. |
| Lifecycle/concurrency | 8/10 | The concrete races found were fixed. It is still a single-process execution design, not a distributed scheduler. |
| Configuration safety | 8/10 | Unusually thorough validation and warnings. Some shipped policies knowingly fall below the quality bar the warnings describe. |
| User experience | 7/10 | Useful preflight, SSE timeline, trust signals, setup advisor, retry/cancel, and now honest send/delete behavior. Error contracts and cancellation presentation need cleanup. |
| Persistence/privacy | 7/10 | JDBC session/event persistence, filesystem artifacts, retention, path containment, durable result artifacts, and deletion cascade exist. No encryption or user-level access control. |
| Test confidence | 8/10 | 932 passing deterministic tests plus two live three-model Ollama runs. Browser E2E, load, fault-injection, and repeatable cloud-provider suites remain open. |

## Review method

The review used several passes rather than reading controllers in isolation:

1. Repository and build inventory: 200 production Java source files plus the static UI and configuration.
2. Protocol trace: create session through resolution, generation, review, score, debate, revision, synthesis, validation, export, persistence, SSE, chat completion, cancellation, and retention.
3. Failure trace: invalid profile, model timeout, provider transient failure, malformed model output, quorum loss, partial synthesis, cancellation windows, duplicate requests, callback failure, and artifact-store failure.
4. Data-integrity/security pass: path traversal, symlinks, deletion semantics, retention, durable restart behavior, network exposure, untrusted model output, and prompt-context boundaries.
5. UX pass: profile health, setup flow, send/retry/cancel, terminal status consistency, trust signal availability, provider messaging, and deletion expectations.
6. Test pass: restored the deleted behavioral suite, added focused regressions for newly discovered defects, then ran a clean build.

Verification performed:

```text
JAVA_HOME=<JDK 25> mvn clean test
Tests run: 932, Failures: 0, Errors: 0, Skipped: 0
Total time: 12.172 s
Ruby YAML parse: application.yml and all three Compose files valid
Markdown relative-link scan: 9 files checked, 0 missing targets
mvn -DskipTests package: BUILD SUCCESS
git diff --check
No whitespace errors
```

`docker compose config` could not be executed because the Docker CLI is not
installed in this environment. YAML parsing, application-context tests, and the
Compose content regression tests passed; actual container startup remains a
machine-runbook check.

No cloud-provider credentials were used. The live verification used only the
local Ollama service and the installed `llama3.1:8b`, `mistral:7b`, and
`qwen2.5:7b` models. No claim in this report treats that result or mock-provider
success as proof of a cloud-provider contract.

## Must-have defects fixed

### 1. Post-debate scoring used the wrong evidence

The second `SCORE` pass read the combined pre- and post-debate review list. That diluted updated judgments with stale judgments and could hide persistent disagreement. `CouncilContext` now retains a separate post-debate review set, the post-debate artifact contains only that set, and the second score pass consumes only that set.

Files: `CouncilContext`, `ReviewPostDebateStageExecutor`, `ScoreStageExecutor`.

### 2. Rigorous mode paid for a fake second pass when no debate occurred

When debate was skipped, the system still called every reviewer again, called the second scoring pass, and labelled the result post-debate even though there was no debate evidence. That wasted provider calls and produced a misleading before/after comparison. Both stages now publish explicit skip events and preserve the valid initial score.

Files: `ReviewPostDebateStageExecutor`, `ScoreStageExecutor`, `ScorePassLabellingTest`.

### 3. Revision silently changed anonymity semantics

Revised drafts did not preserve the source draft's `anonymous` flag. The revision stage now carries it forward.

File: `RevisionStageExecutor`.

### 4. The Spring AI timeout was not enforced

The request timeout was present in the domain request but ignored by the Spring AI adapter. The first attempted fix timed `ChatClient.call()`, but Spring AI performs the provider work at the terminal response read. The terminal `content()`/`chatResponse()` operations are now inside the timed virtual-thread task. Timeout failures are classified as `MODEL_TIMEOUT`; Spring AI transient exceptions remain retryable.

File: `SpringAiModelClient`.

Qualification: interrupting the virtual thread is best-effort cancellation of the underlying HTTP operation. Provider/client transport timeouts should still be configured because a transport that ignores interruption may continue work in the background.

### 5. Duplicate and overlapping runs could corrupt lifecycle state

The same session could be run twice, and chat turns could overlap. That allowed duplicate provider cost, mixed event/artifact streams, stale summaries, and lost chat updates. Sessions are now single-use, active session IDs are guarded, duplicate/out-of-order requests return HTTP 409, and chat mutation paths are serialized.

Files: `CouncilService`, `CouncilRunStateException`, `CouncilController`, `ChatCouncilService`.

### 6. Async executor races leaked handles or permits

The async runner had three defects:

- a very fast task could finish before its in-flight handle was inserted, leaving a stale handle;
- a completion callback exception was treated as a council failure and could invoke the callback twice;
- cancelling a not-yet-started future could prevent the task's `finally` block and leak a semaphore permit.

The in-flight marker is now installed before execution, callback failure is isolated, and cancellation is owned by the run registry rather than `Future.cancel(false)`.

File: `CouncilRunExecutor`.

### 7. Cancellation could be lost or leave a tombstone

A cancel request could arrive after async acceptance but before the orchestrator registered its context. It could also arrive after orchestrator unregister but before the terminal session status was persisted, leaving a pending cancellation forever. Pending cancellation is now applied at registration and explicitly cleared after terminal persistence.

Files: `RunRegistry`, `CouncilService`.

### 8. Resolution failures left sessions permanently `CREATED`

An unknown profile or invalid profile/depth resolution failed before `RUNNING` was saved, leaving an apparently retryable, retention-protected session. Pre-orchestration failures now persist `FAILED` with the actual reason.

File: `CouncilService`.

### 9. Direct API status disagreed with persisted status

`CouncilRunResponse.from` returned `FAILED` for every terminal context, even when a synthesis existed and the persisted session correctly said `PARTIAL`; cancellation could be reported as success. The DTO now reports `COMPLETED`, `PARTIAL`, `FAILED`, or `CANCELLED` consistently.

File: `CouncilRunResponse`.

### 10. Chat completion was published before the state was stored

A client reacting to `TURN_COMPLETED` could fetch the chat or result immediately and see `RUNNING`/404. Terminal result and chat state are now stored before the terminal event is published.

File: `ChatCouncilService`.

### 11. “Delete chat” did not delete the underlying data

Deleting a chat removed only the chat aggregate. Council sessions, events, raw provider responses, normalized artifacts, final answers, and trust results remained until retention. Deletion now cascades over every turn's council session before removing the chat.

Files: `CouncilSessionCleanup`, `ChatCouncilService`, `RunResultStore`.

### 12. JDBC restart lost terminal trust results

Sessions and events could survive a JDBC restart while `CouncilRunResponse` lived only in memory. The answer remained, but exclusions, score summary, validation result, independence, sycophancy warnings, usage, and integrity assessment disappeared. Terminal result DTOs are now also stored as `final/result.json` and lazily recovered into the bounded cache.

File: `InMemoryRunResultStore`.

### 13. Artifact paths could escape through symlinks

Lexical `..` containment was checked, but a symlink below a session directory could point outside the artifact root. Session IDs are now contained, symbolic-link traversal is rejected, and listing does not follow links.

File: `LocalArtifactStore`.

### 14. Large-council convergence used unpaired data

The convergence detector correctly counted paired members before selecting KS, then passed the full confidence lists—including dropouts and newcomers—to KS. Unpaired extremes could change the result despite carrying no between-round evidence. KS now uses paired samples only.

File: `DebateConvergenceDetector`.

### 15. UI send behavior could lose the user's question

The composer cleared text before send and did not restore it on failure; rapid clicks could submit twice before rerender. It now guards local submission and restores the text when the send fails.

Files: `static/js/chat.js`, `static/js/main.js`.

### 16. Misleading provider and documentation claims

The startup banner said Ollama was “always available” when only its lack of credential requirement was known. It now says local health must be checked. The README no longer claims hidden chain-of-thought output and reports the actual test count. Maven source/report encoding is explicit, and malformed HTML nesting was corrected.

Files: `ProviderAutoConfiguration`, `application.yml`, `LlmCouncilApplication`, `README.md`, `pom.xml`, `static/index.html`.

### 17. Docker rigorous profile did not provision its third member

Both full Docker stacks exposed `local-rigorous`, but their model-pull jobs
downloaded only the primary and alternate models. The configured third member,
`local-qwen`, could therefore fail health preflight or disappear under partial
quorum. The Intel stack also overrode the alternate provider model from Mistral
to Qwen while its `modelFamily` remained `mistral`, corrupting diversity and
validation-independence diagnostics.

Both full stacks now pull and pass through `LLM_COUNCIL_LOCAL_THIRD_MODEL`.
Local model-family fields are environment-overridable, and the Intel stack
declares the alternate as Qwen. Compose regression tests cover the contract.

Files: `application.yml`, all three Docker Compose files,
`DockerComposeConfigurationTest`.

### 18. Local review output could erase valid evidence and still look successful

The observed Ollama run exposed three parser/orchestration defects at once.
Llama emitted one review envelope per draft with prose between them, while the
old first-object parser retained only its self-review and then removed it.
Mistral emitted a compact criterion score object instead of the documented
array, so its whole response was excluded. Qwen was the only reviewer left,
which gave each draft at most one review, made reviewer disagreement
unmeasurable, and cascaded into skipped debate, revision, post-debate review,
and second scoring. Because a final synthesis existed, the run was nevertheless
shown as cleanly `COMPLETED`.

Review parsing now extracts every bounded, balanced top-level JSON object;
accepts criterion arrays and compact score objects; normalizes finite
fractional scores in the valid range; and retains valid sibling reviews when
one entry is malformed. Review evidence is normalized to unique
reviewer/draft pairs, with unknown drafts, duplicates, and self-reviews unable
to inflate quorum. Each reviewer publishes expected and actual non-self review
counts plus exact missing draft IDs. Incomplete evidence marks the run
`PARTIAL`, while the timeline distinguishes a partially scored stage from a
stage that never ran. Both review passes request JSON mode where the adapter
supports it. A subsequent hardening change also gives both review passes one
bounded targeted call for the exact draft IDs omitted from an otherwise parseable
response. Original and recovery outputs remain separate artifacts; coverage still
missing afterward remains `PARTIAL`.

Files: `StructuredOutputParser`, `ReviewEvidence`, `ReviewStageExecutor`,
`ReviewPostDebateStageExecutor`, `ScoreStageExecutor`, `CouncilContext`,
`ProtocolOrchestrator`, `CouncilService`, `CouncilRunResponse`,
`ChatCouncilService`, `static/js/timeline.js`, and `static/css/app.css`.

Implementation status: **all 18 concrete must-have defects in this report are
CLOSED in the reviewed worktree.** The next section contains conditional release
gates that remain OPEN for shared/public deployment; they are not descriptions
of unfixed items above.

## Documentation-to-code audit

Every Markdown document under `docs/` and the root README was cross-checked
against controllers, stores, configuration, Compose files, the POM, workflow,
and current test suite.

| Document | Gap found | Correction |
|---|---|---|
| `README.md` | Claimed chat was memory-only and lacked cancellation/cursor durability; contained two dead links; omitted the durable result and third rigorous model. | Describes memory/JDBC modes, cancellation, cursor replay, deletion cascade, `final/result.json`, all Compose models, and links only to existing runbooks. |
| `library-flow-guide.md` | Hard-coded stores/context/provider claims, combined review evidence, first-object parsing assumptions, and a cross-draft debate-trigger description were stale. | Uses current stores and providers, documents resilient exact-coverage parsing, separate post-debate evidence, and the actual same-draft reviewer-disagreement trigger. |
| `production-readiness-plan.md` | Treated health, failures, persistence, SSE, cancellation, chat, structured-output recovery, and all real-provider verification as future work. | Rewritten as the authoritative current status, records partial parser recovery and manual Ollama evidence, and keeps repeatable provider contracts as a release gate. |
| `production-readiness-implementation-guide.md` | Old proposed snippets looked current and advised unsafe `Future.cancel`; its baseline omitted current review recovery and live verification. | Marked historical, added an as-built matrix and current parser/Ollama evidence, and explicitly rejects the old cancellation authority. |
| `user-configurability-and-ui-plan.md` | “Proposed” status and pre-implementation claims remained unqualified; two tunable debate options and the selectable `average` scoring strategy were absent from its option table. | Records delivered phases and deviations, points future work to the readiness plan, and now mirrors the complete server-owned stage-option specification. |
| `testing-m1-32gb.md` | Broken references, machine-specific path, missing third model/result artifact, and no explanation of conditional rigorous stages or recovered review shapes. | Uses repository-relative setup, covers all rigorous models/artifacts, adds exact review-coverage expectations, and includes a rigorous API verification path with `force-run` guidance. |
| `testing-intel-2019-32gb.md` | Omitted third model, incorrect family metadata, obsolete context advice, and treated malformed reviews and rigorous skips too simplistically. | Documents the Qwen member/family, shipped context guidance, parser recovery/`PARTIAL` behavior, and conditional-debate expectations for CPU-only runs. |
| `licensing-and-distribution.md` | Future AGPL/coordinates could be read as current, merged branches/test/link risks were stale, and it said nothing had verified real Ollama. | Separates current and future licensing state, records CI/link closure, and accurately limits the two manual Ollama audits without treating them as cloud-provider contracts. |
| This report | Did not explicitly distinguish fixed defects from conditional production gates and predated the Compose, parser, and live-verification audits. | Adds closure status, the eighteenth defect, live evidence, and this audit record. |

### Provider/profile cleanup completed

The shipped configuration no longer exposes the removed gateway-specific or
mixed legacy profiles. Public profile IDs are now exactly `default`, `local`,
`openai`, `claude`, `gemini`, and `multi-cloud`; `mock` remains test-only.
Application code, health checks, user-config validation, examples, UI comments,
and every document under `docs/` use the same provider vocabulary.

The cleanup also corrected two runtime defects discovered during verification:

- retired Claude API defaults were replaced with active Claude model IDs;
- Anthropic calls omit sampling temperature because current Claude generations
  reject non-default sampling parameters;
- the Gemini chair was removed from the member roster and a Flash-Lite critic
  was added, so no shipped production policy synthesizes a pool containing its
  own draft.

Regression tests pin the public profile set, validator independence tier,
chair/member separation, deferred cloud health state, and provider-specific
sampling behavior.

### Phase 1 showcase work completed after the review

The repository now has a pull-request workflow for a clean Java 25 build and a
reusable `scripts/verify-repository.sh` gate for YAML, local documentation/image
links, and removed-provider references.

The static UI now includes `/config.html`, an advanced configuration workbench.
It loads or imports the overlay, runs the existing strict parser and semantic
validator, previews the effective catalog diff, locks save to the exact
validated source revision, requires explicit write confirmation, preserves the
atomic-write/backup contract, and makes the restart requirement prominent.

A model can also be probed before it is saved. That endpoint accepts no
credentials, rejects unknown fields and oversized bodies, permits only the four
supported user providers, requires explicit acknowledgement for cloud calls,
uses a fixed non-sensitive prompt with eight output tokens and a fixed timeout,
does not retry, globally throttles calls, and returns stable failure categories
without exposing the raw provider exception. Deterministic tests cover success,
failure sanitization, acknowledgement, strict parsing, credential refusal,
throttling, the negative `System.nanoTime` origin edge case, and the guarantee
that global mock fallback cannot fabricate a successful probe.

The post-implementation verification is 932 tests with zero failures,
errors, or skips on the reviewed machine.

### Live local verification after the review-output fix

Two real runs were executed through the HTTP API against the shipped local
profile and three installed Ollama models on 2026-08-17.

1. The unmodified `local` + `RIGOROUS` preset completed with three drafts, six
   unique non-self reviews, three initial scores, synthesis, independent
   validation, and export. Reviewer disagreement was measurable at `6.25`,
   below the configured `40.0` trigger, so debate and its dependent stages were
   explicitly skipped. This is the intended evidence-driven path, not a quorum
   failure.
2. A temporary user overlay derived from `rigorous` with
   `DEBATE.force-run: true`, full `3/3` draft quorum, and two reviews per draft
   exercised every stage. It completed 20 model calls: three drafts, six initial
   reviews, two debate rounds, three revisions, six post-debate reviews, three
   second-pass scores, synthesis, independent validation, and export. The
   61-event audit contained no incomplete, failed, partial, or skipped stage.

The forced overlay was removed and the verification server was stopped after
the audit. One debate contribution omitted a parseable confidence marker; its
argument remained in the transcript, convergence excluded that confidence, and
the final result disclosed the warning. That is truthful degraded measurement,
not lost review evidence.

The long planning documents intentionally retain historical code sketches and
phase counts. Their top-level status warnings now prevent those snapshots from
being mistaken for current implementation instructions.

## Remaining must-have work

These are not blockers for the default loopback-only personal application. They are blockers for the stated condition.

### Before binding to a non-loopback interface

1. **Add authentication and authorization.** Every session, chat, raw prompt, raw model response, artifact, configuration draft, and config-write endpoint is otherwise available to anyone who can reach the port.
2. **Add CSRF protection and a deliberate CORS policy** if browser credentials are introduced.
3. **Terminate TLS at a trusted reverse proxy or in the application.** API keys are not accepted by the UI, but questions and model outputs can still be sensitive.
4. **Define tenant/data ownership.** UUID knowledge is the only object boundary today; it is not authorization.

The current `server.address=127.0.0.1` default is the correct safe default and should remain.

### Before claiming production-grade council quality

1. **Clearly distinguish provider diversity from model diversity.** OpenAI, Claude, and Gemini balanced/rigorous policies no longer seat their chair as a member and use distinct provider model IDs for member, chair, and validator roles, but their validation remains correlated within one provider/model family. Use `multi-cloud` when provider-level independence is required.
2. **Run gated contract tests against each supported provider.** Verify request options, model identifiers, timeout behavior, JSON compliance, token usage metadata, 429/5xx classification, streaming/non-streaming semantics, and provider-specific maximum output limits.
3. **Calibrate the quality heuristics on labelled data.** Confidence-weighted scoring trusts self-reported confidence; the debate trigger, KS threshold, sycophancy detector, and escalation variance are engineered heuristics, not demonstrated predictors of answer correctness.

## Should-have improvements

### Correctness and API

1. Validate profile/depth existence when creating a session or chat, not only when running it. Early rejection is clearer and avoids creating doomed records.
2. Put synchronous `/sessions/{id}/run` behind the same concurrency admission control as chat runs. It currently bypasses `maxConcurrentRuns` and can tie up servlet threads for minutes.
3. Standardize errors with RFC 9457 `ProblemDetail`. Controllers currently mix plain text handlers with Spring validation JSON, forcing the UI to parse two contracts.
4. Add a `CANCELLED` chat-turn state and event instead of rendering user cancellation as `TURN_FAILED`/“Run failed.”
5. Make `participatingModels` reflect models that actually participated. It currently starts from the policy roster while exclusions are reported separately.
6. Make the result artifact and session/event persistence one documented consistency model. The artifact fallback is durable and practical, but it is not transactionally committed with the JDBC session row.

### Model integration and structured output

1. Use provider-supported structured output/schema controls where Spring AI exposes them. Ollama review calls now request JSON mode and parseable incomplete review responses receive one targeted recovery call; `ModelCallRequest.jsonMode` is still not bound by `SpringAiModelClient`, so cloud correctness relies on prompt compliance plus tolerant parsing.
2. Apply the review parser's balanced-object selection to validation and advisor output. Those paths still use first-`{`/last-`}` extraction and can fail when surrounding prose contains unrelated braces.
3. Expand provider error mapping beyond `TransientAiException` and timeout. Rate limits, authentication, not-found model IDs, and provider 5xx errors should produce stable categories and retry decisions.
4. Propagate a request/correlation ID into provider calls and logs. Session ID is useful, but a single stage can retry and call several models.

### Council quality

1. Do not treat self-reported confidence as a calibrated probability. Label it explicitly as a weighting heuristic in the UI.
2. Revisit sycophancy measurement. Confidence movement is only a proxy for position, word-level Jaccard is brittle to paraphrase, punctuation is retained, and the “majority” confidence includes the member being judged.
3. Preserve and display minority reasoning even when synthesis succeeds. The backend has dissent signals; the final user experience should make the strongest unresolved objection easy to find.
4. Add an abstain/insufficient-evidence path distinct from provider failure and low quorum.
5. Render a deterministic evidence summary alongside free-form synthesis and format scores to a fixed precision. A live chair answer reproduced Java floating-point tails and made a numeric ranking claim that did not match its own listed values; model-based validation approved it. The underlying score artifact was correct, but the prose should not be the only user-facing interpretation of those numbers.

### Privacy and operations

1. Add a direct session-delete API, not only chat deletion and scheduled retention.
2. Tell users explicitly that raw prompts/responses are written under the artifact path and retained for up to 90 days by default.
3. Offer optional artifact encryption or a metadata-only/no-raw-output mode for sensitive deployments.
4. Add disk-space/retention metrics and alerting; event-write failure is deliberately nonfatal and can otherwise go unnoticed.
5. Decide whether configuration changes require restart or implement atomic live reload. The UI currently saves correctly but the operational consequence needs to remain prominent.

### Maintainability

1. Split the largest classes by responsibility: `PromptBuilder`, `UserConfigValidator`, `ConfigSynthesizer`, and `CouncilConfig` are large enough that unrelated changes collide and review becomes difficult.
2. Move provider construction into provider-specific factories and keep `CouncilConfig` focused on composition.
3. Centralize API exception handling in `@RestControllerAdvice`.
4. Replace global method synchronization in `ChatCouncilService` with per-chat locking if request volume grows. The current form is correct but serializes short mutations across unrelated chats.

## Nice-to-have improvements

1. Add browser E2E coverage for composer restoration, reconnect/resume, cancel, delete, accessibility, and trust-panel rendering.
2. Add load tests for many SSE clients, slow consumers, large artifacts, and `maxConcurrentRuns > 1`.
3. Add JaCoCo branch coverage and mutation testing for lifecycle and scoring code. Test count alone is not coverage quality.
4. Tag tests as unit, Spring integration, JDBC contract, and live-provider contract. Keep `mvn test` deterministic and fast; put credentialed/provider tests behind an explicit profile.
5. Reduce test log volume. The suite is fast, but repeated Spring contexts and intentionally rejected 5,001-character input generate far more output than the signal warrants. Do this with test-specific logging that preserves warning-capture tests.
6. Add cost estimates before a rigorous run and live accumulated usage while it runs.
7. Add a downloadable evidence bundle with a documented schema/version and checksum.
8. For multi-instance deployment, replace process-local admission control, cancellation registry, and SSE broker with shared coordination.

## User-friendliness assessment

What is already good:

- The setup advisor separates LLM intent extraction from deterministic config generation.
- Test-only profiles are visibly marked and mock output is not silently substituted for a real provider.
- Preflight profile health blocks obviously uncallable runs.
- The UI exposes stage progress, artifacts, confidence, validation independence, exclusions, sycophancy warnings, and usage rather than showing only a polished answer.
- Partial answers are distinct from clean completion.
- Configuration validation is specific and actionable.
- Advanced users can create/import, validate, diff, and safely save an overlay
  in the browser, with a bounded model-id connectivity check.
- The default loopback bind is safe for a no-auth personal app.

What still causes avoidable friction:

- A bad profile can be selected and stored before it is rejected at run time.
- Cancellation is presented as failure in chat.
- API errors have inconsistent shapes.
- A rigorous single-provider profile can look more independent than it is unless the user reads the warnings carefully.
- `RIGOROUS` debate is conditional by default. The timeline now states the
  measured disagreement and threshold, but users who expect every listed stage
  to run need a derived protocol with `DEBATE.force-run: true`.
- The product does not prominently explain raw artifact persistence and retention.
- There is no direct “delete this run and all evidence” control.

## Test-suite decision

Deleting the previous suite was the wrong tradeoff. The deleted suite was not a slow pile of meaningless generated tests: it contained useful contract tests for both JDBC engines, SSE cursor semantics, retention, advisor/configuration boundaries, prompt budgets, scoring math, failure states, and shipped configuration warnings. In an isolated baseline it ran 817 tests successfully in roughly 16 seconds.

The suite has been restored, stale expectations were corrected, and regressions
were added for the defects in this review. At the original review checkpoint it
was 891 tests in 14.8 seconds; Phase 1 work added configuration-workbench and
model-probe boundary coverage after that measurement.

New/expanded scenarios include:

- pre- versus post-debate evidence separation;
- skipping fake post-debate work;
- partial/cancelled API status;
- duplicate session runs and overlapping chat turns;
- async fast-completion, callback-failure, permit, and cancellation-window races;
- Spring AI terminal-operation timeout and transient classification;
- symlink and lexical artifact traversal;
- large-council KS pairing;
- chat deletion cascade;
- terminal result recovery after memory loss;
- UI send failure preservation;
- end-to-end quick/balanced/rigorous mock protocol and API boundaries.
- observed multi-envelope, compact-criteria, fractional-score, duplicate,
  self-review, malformed-sibling, and missing-coverage review outputs;
- bounded targeted recovery for omitted initial and post-debate reviews;
- truthful `PARTIAL` propagation and partial-score timeline rendering.

“Exhaustive” should not be used literally: no finite suite proves every model output, scheduler interleaving, filesystem race, browser, database, or provider behavior. This suite is broad and fast. Its material gaps are live-provider contracts, browser E2E, load/soak, transport fault injection, and measured branch/mutation coverage.

Recommended developer workflow:

```text
# Fast deterministic suite; currently ~15 seconds on this machine
mvn test

# Clean authoritative check before a PR/release
mvn clean test

# Future: opt-in real provider contracts
mvn verify -Pprovider-contracts
```

Do not solve incremental-build annoyance by deleting coverage. Use JUnit tags/Maven profiles to separate feedback loops while retaining the full suite in CI.

## Final release recommendation

For local development and personal loopback use: **proceed after reviewing the applied changes.**

For a public or shared deployment: **do not release yet.** Add auth/TLS/ownership, repair or demote the weak production policies, and execute live-provider contract tests. Those are release gates, not polish.
