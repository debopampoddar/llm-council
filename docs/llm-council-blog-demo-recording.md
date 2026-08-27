# LLM Council Blog Demo: Screen-Recording Script

Use this script to make a short, evidence-safe companion video for
[Building an Inspectable LLM Council in Java](llm-council-inspectable-orchestration-blog.md).
It is a recording plan, not a benchmark protocol. Do not use it to imply that a
council has been proven better than a direct model.

## Deliverable

- Format: 16:9, 1080p if the machine allows it, 4–6 minutes, captions on.
- Audio: clear voiceover; do not record system notifications or private
  conversations.
- Scope: local loopback application only, with no credentials, customer data,
  private prompts, artifact paths, or terminal windows on screen.
- End card: project repository, evaluation repository, and the limitations
  statement below.

## Demo setup

1. Use a clean worktree and record the application commit and date.
2. Start the app with loopback binding. Do not expose it to a network for the
   recording.
3. Confirm Ollama is running and required tags are installed.
4. Close unrelated applications and disable notifications.
5. Use synthetic, non-sensitive prompts only. Do not enter cloud credentials in
   the UI or show environment variables.
6. Capture `QUICK` first. If recording `BALANCED` or `RIGOROUS`, leave enough
   time for a real run or label any edit/time jump clearly.

## Script and shot list

| Time | Screen action | Suggested narration / caption |
|---|---|---|
| 0:00–0:20 | Start on the LLM Council chat page. Select `local` and `QUICK`; hold on the health gate and roster. | “LLM Council is a Java and Spring Boot application for running a configurable model council. Users choose a profile and depth, not a raw protocol.” |
| 0:20–0:40 | Point to `no validation`, available model roles, and policy name. | “The first useful signal arrives before a prompt is sent: this is a local QUICK path with no model-validation stage. That is a trade-off the UI makes explicit.” |
| 0:40–1:10 | Enter the sample incident question below and send it. Keep the question fully visible for a beat. | “I am using a bounded synthetic operational question. The goal is to show execution evidence, not to use this response as a quality benchmark.” |
| 1:10–1:35 | Show the running timeline. If the run takes longer, cut only between complete states and add an on-screen “elapsed time omitted” caption. | “Stages are persisted as the protocol runs. A cancellation request stops at a stage boundary rather than pretending an in-flight model call never existed.” |
| 1:35–2:05 | Show the completed timeline, then the trust strip: validation, member count, sycophancy, tokens, calls, and latency. | “The final answer carries its execution context. No validation and no measured sycophancy are limits of this run, not positive claims.” |
| 2:05–2:30 | Expand generation/synthesis evidence once. Point to warnings, partial state, or preserved dissent if present. | “The useful artifact is the answer plus the audit trail: stages, warnings, dissent, and recovery state.” |
| 2:30–3:10 | Open Guided setup, skip to choices, choose Local only, then show Environment. | “The advisor proposes only models that this machine can actually run. A language model may help express intent, but deterministic Java builds the configuration.” |
| 3:10–3:40 | Show the local-only proposal. Highlight members, chair, validator, and quorum. Do not click confirmation/save. | “Nothing is written until the proposal has been reviewed and explicitly confirmed.” |
| 3:40–4:20 | Return to the result or show the article’s troubleshooting table. | “A blocked model, partial run, no validation label, or missing dissent signal should be interpreted—not hidden by a polished final answer.” |
| 4:20–4:50 | Show the repository limitation/evaluation links or a simple end card. | “This project does not claim that a council is proven to beat a strong direct model. New quality claims require independent, blinded evidence.” |

## Sample prompt

```text
We run a Java 25 payment API on Kubernetes. After a configuration deployment,
p99 latency rose from 120 ms to 2.4 s while CPU and memory stayed flat. Rolling
back restored p99 to 130 ms. Give the likely cause, the next three diagnostic
checks in order, what evidence would falsify your diagnosis, and a safe rollout
plan. Separate confirmed facts from assumptions.
```

## Captures already prepared for the article

| Asset | Purpose | Notes |
|---|---|---|
| `assets/blog/01-local-quick-preflight.png` | Profile, depth, health gate, roster | Local `QUICK`; no validation is visible. |
| `assets/blog/02-live-quick-result.png` | Completed run, stages, trust strip | Real local run; treat as an observability example, not a benchmark. |
| `assets/blog/03-setup-environment.png` | Environment-aware setup | Shows installed local models and provider-state semantics. |
| `assets/blog/04-configuration-proposal.png` | Local-only configuration preview | Shows roles and quorums before any confirmation/write. |

## Final publication checks

- [ ] The video and all screenshots came from the exact commits named in the post.
- [ ] Prompts, output artifacts, chat history, terminal paths, and credentials
      have been reviewed for private data.
- [ ] Any elapsed-time edit is visibly disclosed; no UI state is passed off as a
      continuous live run if it was not.
- [ ] The recording says `QUICK` is unvalidated and does not portray it as a
      multi-model cross-check.
- [ ] The recording distinguishes a missing dissent signal from agreement.
- [ ] The evaluation statement says historical evidence did not establish a
      RIGOROUS advantage and that fresh independent evidence is still required.
- [ ] The final frame says the service is for controlled local use and is not a
      public, authenticated, multi-tenant deployment.
