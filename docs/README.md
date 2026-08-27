# Documentation Guide

Use this page to choose the right document. The repository contains both current
operational references and historical implementation records; they should not be
read as if they have the same authority.

## Start Here

| Goal | Document |
|---|---|
| Run the application quickly | [Project README](../README.md#quick-local-demo) |
| Run OpenAI or Claude locally | [Cloud quick start](cloud-quickstart.md) |
| Understand the request and stage flow | [Library flow guide](library-flow-guide.md) |
| Test on an Apple Silicon Mac | [M1 32 GB runbook](testing-m1-32gb.md) |
| Test on an older Intel Mac | [Intel 2019 32 GB runbook](testing-intel-2019-32gb.md) |
| Understand security boundaries | [Prompt-injection threat model](prompt-injection-threat-model.md) |
| Decide whether deployment is safe | [Production-readiness plan](production-readiness-plan.md) |
| Record a demo or write a blog post | [Showcase and blog guide](showcase-and-blog-guide.md) |
| Review the original code audit | [Code-review report](code-review-report-2026-08-17.md) |
| Understand configurable UI decisions | [Configurability and UI implementation record](user-configurability-and-ui-plan.md) |
| Understand publishing and licensing decisions | [Licensing and distribution record](licensing-and-distribution.md) |

## Document Authority

### Current references

- `README.md` is the public product overview and quickest supported path.
- `library-flow-guide.md` describes the current application and API flow.
- `prompt-injection-threat-model.md` defines the current security claim and its
  explicit limitations.
- `production-readiness-plan.md` is the authoritative list of deployment gaps and
  release gates.
- The two hardware runbooks are current operational guides for their named
  machines.

### Historical records

- `code-review-report-2026-08-17.md` records the review findings, fixes, and
  remaining recommendations at that point in time.
- `production-readiness-implementation-guide.md` contains old implementation
  sketches. Its status matrix is useful, but its code samples are not drop-in code.
- `user-configurability-and-ui-plan.md` records how the configuration features
  evolved. The shipped behavior and deviations are explicitly marked.
- `licensing-and-distribution.md` is a decision record for a possible future
  licensing/distribution model. The repository's current `LICENSE` file remains
  authoritative.

## Related Project

Quality and efficiency claims belong in the independent
[`llm-council-evaluation`](https://github.com/debopampoddar/llm-council-evaluation)
repository. It contains versioned plans, datasets, raw evidence conventions,
statistics, and a report-review handbook. Do not use deterministic application
tests as evidence that a council produces better answers.

## Safe Claim Boundary

It is accurate to say that this repository demonstrates a configurable,
observable Java/Spring multi-model orchestration system with deterministic
correctness and security backstops. It is not accurate to claim universal
prompt-injection resistance, independent validation for every provider profile,
production multi-tenancy, or a proven quality advantage over a strong direct
model.
