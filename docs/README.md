# Documentation Guide

Use this page to choose the right operational document.

## Start Here

| Goal | Document |
|---|---|
| Run the application quickly | [Project README](../README.md#start-here-your-first-local-run) |
| Run OpenAI or Claude locally | [Cloud quick start](cloud-quickstart.md) |
| Persist chats and sessions locally | [SQLite persistence](operations-and-development-guide.md#durable-sqlite-persistence) |
| Understand the request and stage flow | [Library flow guide](library-flow-guide.md) |
| Configure, observe, test, or extend the utility | [Operations and development guide](operations-and-development-guide.md) |
| Call the service from another application | [HTTP API reference](http-api-reference.md) |
| Understand security boundaries | [Prompt-injection threat model](prompt-injection-threat-model.md) |

## Document Authority

### Current references

- `README.md` is the public product overview and quickest supported path.
- `library-flow-guide.md` describes the current application and API flow.
- `operations-and-development-guide.md` describes runtime setup, credentials,
  metrics, configuration, verification, Docker paths, and extension boundaries.
- `http-api-reference.md` is the concise endpoint reference for API consumers.
- `prompt-injection-threat-model.md` defines the current security claim and its
  explicit limitations.
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
