# Showcase And Blog Guide

This guide turns the repository into a short, evidence-backed demonstration. It
does not manufacture screenshots or quality claims; capture them from a fresh run
of the exact commit being presented.

## One-Sentence Positioning

> LLM Council is a Java/Spring Boot system that turns multi-model disagreement
> into inspectable drafts, reviews, debate, synthesis, validation, and evidence—
> with explicit trust boundaries and a separate evaluation harness.

## Intended Audience

- Java and platform engineers learning production-style LLM orchestration.
- Architects evaluating multi-model workflows beyond simple voting.
- Engineering leaders interested in traceability, failure semantics, and honest
  evaluation rather than an unqualified “more models are better” claim.

## Five-Minute Demo

### 1. Establish the environment

Show that the application is bound to loopback and that the intended local models
are installed:

```bash
ollama list
curl -fsS http://127.0.0.1:8080/actuator/health
```

State the limitation plainly: this is a trusted local application, not a
multi-tenant service.

### 2. Show the profile before spending calls

Open <http://127.0.0.1:8080>. Select `local` and start with `QUICK`. Point out the
health gate, model roster, depth choice, and validation-independence label.

### 3. Ask a question that benefits from disagreement

Use a bounded engineering question with explicit constraints, for example:

> We run a Java 25 payment API on Kubernetes. After a configuration deployment,
> p99 latency rose from 120 ms to 2.4 s while CPU and memory stayed flat. Rolling
> back restored p99 to 130 ms. Give the likely cause, the next three diagnostic
> checks in order, what evidence would falsify your diagnosis, and a safe rollout
> plan. Separate confirmed facts from assumptions.

Run QUICK first. If the local machine is healthy and time permits, repeat with
BALANCED or RIGOROUS. Do not imply that the more expensive result is better merely
because it has more stages.

### 4. Expand the evidence, not only the final answer

Show:

- draft generation and anonymization;
- exact review coverage and any partial state;
- whether disagreement was measurable;
- debate/revision only when it actually ran;
- synthesis and validation status;
- validator independence, usage, latency, warnings, dissent, and sycophancy signals.

The most differentiated part of the project is the inspectable protocol and its
failure semantics, not the chat box.

### 5. Show safe customization

Open <http://127.0.0.1:8080/setup.html> and demonstrate that the Requirement
Advisor proposes only models available in the environment. Then open
<http://127.0.0.1:8080/config.html> and show strict validation, preview, explicit
confirmation, and restart-to-apply behavior. Do not enter credentials into either
surface.

### 6. Close with evaluation evidence

Open the separate evaluation repository and show:

- a versioned plan, dataset, and rubric;
- a report with reliability, deterministic checks, quality, efficiency, judge
  independence, and limitations;
- the difference between gitignored working results and tracked published evidence.

State the current result honestly: the historical local ablation did not establish
a RIGOROUS advantage and drove subsequent hardening. The next publishable claim
requires fresh independent evidence.

## Screenshots To Capture

Capture these from a fresh run and redact prompts or artifacts that contain private
data:

1. The profile/depth selection and healthy-model roster.
2. A completed timeline with at least generation, synthesis, and validation.
3. Expanded review or debate evidence showing why an intermediate stage ran or
   was skipped.
4. The final answer trust strip with independence, confidence, warnings, dissent,
   usage, and latency visible.
5. The Requirement Advisor environment step and proposed configuration diff.
6. The advanced workbench validation/preview screen.
7. An evaluation report's reliability, deterministic-check, and primary-comparison
   sections, including limitations.

For every screenshot, record the application commit, evaluation commit when
applicable, model tags, profile, depth, date, and whether the worktree was clean.

## Claims You Can Make

- “Implements configurable QUICK, BALANCED, and RIGOROUS multi-model protocols in
  Java/Spring Boot.”
- “Persists inspectable stage evidence and surfaces partial/failure states.”
- “Separates user instruction authority from untrusted context and model artifacts,
  with bounded deterministic recovery for declared invariants.”
- “Reports validator correlation instead of presenting every profile as independent.”
- “Includes a separate, resumable evaluation harness with direct and same-model
  baselines, blinded mirrored judging, deterministic checks, and human-review support.”
- “Has 962 deterministic application tests at v2.0.2. The separate evaluation
  repository documents its own test count, dataset revision, and evidence.”

## Claims You Must Not Make

- “The council is proven to outperform a strong direct model.”
- “Prompt injection is solved.”
- “Every profile has an independent validator.”
- “The system is production-ready, secure for public internet exposure, or
  multi-tenant.”
- “A model validator or one LLM judge establishes factual truth.”
- “Historical results validate code changed after those runs.”

## Suggested Blog Structure

1. **Problem:** one model response is opaque; naive voting hides disagreement.
2. **Design:** profiles resolve to configuration-owned policies and protocols.
3. **Flow:** draft → anonymize → review → score → optional debate/revision →
   synthesis → validation → export.
4. **Engineering details:** typed Java records, bounded retries, quorum, prompt
   budgets, persistence, SSE, telemetry, and explicit partial states.
5. **Trust boundary:** context is data, not authority; explain the narrow
   deterministic guarantees and limitations.
6. **Evaluation:** why the harness is separate, what baselines matter, and what
   the historical result actually showed.
7. **What failed:** include the adversarial propagation and internal-output leakage
   findings and how they changed the design.
8. **What remains:** fresh security regression, new held-out evidence, independent
   judges/human review, provider contracts, and authentication before shared use.

## Publication Checklist

- [ ] Both repositories are on clean, recorded commits.
- [ ] CI is green for both commits.
- [ ] The local model tags and context window are recorded.
- [ ] A fresh prompt-injection regression meets the current 29/0/0 per-variant gate.
- [ ] The fast held-out diagnostic is reviewed manually, not only by check count.
- [ ] Any quality claim uses a new frozen dataset and judge families outside every
      candidate member/chair/validator path.
- [ ] A blinded human-review subset is imported for a publishable comparison.
- [ ] Selected evidence is copied into `evaluation/published/` without secrets or
      private prompts.
- [ ] Screenshots show limitations and trust signals rather than cropping them out.
- [ ] The blog links to exact commits and tracked reports.
- [ ] License terms are stated from the actual `LICENSE` files, not future plans.
