# HTTP API Reference

This reference is for API consumers. For the browser workflow, start with the
[root README](../README.md). For stage and data-flow explanations, use the
[library flow guide](library-flow-guide.md).

All examples assume a local service at `http://127.0.0.1:8080`.

## Before A Run: Preflight

Check whether the profile and selected depth can run before creating a session:

```bash
curl "http://127.0.0.1:8080/api/council/profiles/local/health?depthMode=QUICK"
```

For an Ollama-backed profile, preflight checks the provider's available tags
against the configured model IDs. A health response includes the selected
policy, protocol, model availability, and warnings. Use it before a real local
run when a missing model would otherwise turn into a quorum failure.

For OpenAI and Anthropic profiles, preflight also blocks a missing or placeholder
credential before the run starts. It deliberately does not call a cloud endpoint;
use the explicit, acknowledged model probe when you need to verify a cloud model
ID and account access.

## One-Shot Council Sessions

Create a session:

```bash
curl -X POST http://127.0.0.1:8080/api/council/sessions \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is the best approach to distributed transactions?",
    "context": "Optional factual background",
    "contextPurpose": "EVIDENCE",
    "depthMode": "BALANCED",
    "profileId": "mock"
  }'
```

`contextPurpose` defaults to `EVIDENCE`. Choose `ANALYSIS_SUBJECT` only when
the task itself is to analyse quoted content, such as a prompt-injection sample.
It does not grant the quoted content instruction authority.

Run the session:

```bash
curl -X POST http://127.0.0.1:8080/api/council/sessions/{sessionId}/run
```

Read the completed result:

```bash
curl http://127.0.0.1:8080/api/council/sessions/{sessionId}/result
```

An unfinished result is `404`, not an indication that the run failed. A result
contains the final state, warnings, model failures, scores, validation evidence,
and `answerDisplayable`. If required validation rejects the answer,
`answerDisplayable` is `false` and the top-level answer is intentionally empty.

Read the session, events, or artifact list:

```bash
curl http://127.0.0.1:8080/api/council/sessions/{sessionId}
curl http://127.0.0.1:8080/api/council/sessions/{sessionId}/events
curl http://127.0.0.1:8080/api/council/sessions/{sessionId}/artifacts
```

Cancel a running council:

```bash
curl -X DELETE http://127.0.0.1:8080/api/council/sessions/{sessionId}/run
```

Cancellation is honoured at a stage boundary. A model call already in flight is
allowed to complete and its result is discarded. Cancelling a completed run is a
no-op rather than an error.

## Stable Failure Categories

Run responses preserve structured failure data for automation:

```json
{
  "failureCategory": "PROVIDER_UNAVAILABLE",
  "modelFailures": [
    {
      "modelId": "local-llama3",
      "provider": "ollama",
      "providerModelId": "llama3.1:8b",
      "category": "PROVIDER_UNAVAILABLE",
      "message": "Ollama provider is unreachable"
    }
  ]
}
```

| Category | Meaning |
|---|---|
| `PROVIDER_UNAVAILABLE` | The provider endpoint could not be reached or returned a provider-level failure. |
| `MODEL_NOT_FOUND` | The configured provider model is unavailable. |
| `MODEL_TIMEOUT` | The model or provider call timed out. |
| `MODEL_CALL_FAILED` | The provider call failed without a more specific category. |
| `CONFIGURATION_ERROR` | The selected profile cannot run as configured. |
| `INVALID_MODEL_OUTPUT` | Model output could not be parsed or normalised as required. |
| `VALIDATION_FAILED` | The final validation stage rejected the synthesis. |
| `QUORUM_NOT_MET` | Too few model calls succeeded for the selected policy. |

## Chat API V1

Chat is a usability layer over the same council engine. Each message creates a
linked council session, starts the work asynchronously, and attaches the answer
back to the chat turn.

Create a chat:

```bash
curl -s -X POST http://127.0.0.1:8080/api/council/chats \
  -H "Content-Type: application/json" \
  -d '{"profileId":"local","depthMode":"QUICK","initialContext":"Demo discussion"}'
```

Send a message:

```bash
curl -s -X POST "http://127.0.0.1:8080/api/council/chats/{chatId}/messages" \
  -H "Content-Type: application/json" \
  -d '{"message":"Compare sagas and two-phase commit for microservices."}'
```

The message endpoint returns while the turn is `RUNNING`. Stream progress or
read the completed chat with:

```bash
curl -N http://127.0.0.1:8080/api/council/chats/{chatId}/events
curl -s http://127.0.0.1:8080/api/council/chats/{chatId}
```

Delete a chat only when its local record and linked sessions are no longer
needed:

```bash
curl -X DELETE http://127.0.0.1:8080/api/council/chats/{chatId}
```

Deletion cascades through linked sessions, persisted events, cached results,
and artifact directories.

## Requirement Advisor API

The setup wizard is a client of these APIs; a browser is optional.

```bash
# Models and providers that are usable on this machine.
curl http://127.0.0.1:8080/api/council/advisor/environment

# Free text to a closed-choice requirement. The model ID must be offered by
# the environment. A non-local model requires cloud acknowledgement.
curl -X POST http://127.0.0.1:8080/api/council/advisor/extract \
  -H 'Content-Type: application/json' \
  -d '{"text":"a careful local council for reviewing code","modelId":"local-chair"}'

# Requirement to configuration, with rationale, validation, and a diff.
curl -X POST http://127.0.0.1:8080/api/council/advisor/synthesize \
  -H 'Content-Type: application/json' \
  -d '{"requirement":{"privacy":"LOCAL_ONLY","cost":"FREE_ONLY","rigor":"RIGOROUS","councilSize":3,"adversarialEmphasis":true}}'

# Store an unapplied proposal. It is rechecked whenever it is read.
curl -X PUT http://127.0.0.1:8080/api/council/advisor/proposal \
  -H 'Content-Type: application/json' \
  -d '{"requirement":{"privacy":"LOCAL_ONLY","rigor":"BALANCED"}}'
```

Read or discard an unapplied proposal:

```bash
curl http://127.0.0.1:8080/api/council/advisor/proposal
curl -X DELETE http://127.0.0.1:8080/api/council/advisor/proposal
```

If the environment has no models it can seat, synthesis returns a well-formed
response with a null profile ID and remediation information. That is not a
malformed request.

## Advanced Configuration API

The advanced workbench uses the same public loopback API:

```bash
curl http://127.0.0.1:8080/api/council/config/schema
curl http://127.0.0.1:8080/api/council/config/export

# One connectivity call. Cloud calls also require acknowledgeCloudCall: true.
curl -X POST http://127.0.0.1:8080/api/council/config/models/probe \
  -H 'Content-Type: application/json' \
  -d '{"provider":"ollama","providerModelId":"llama3.1:8b"}'
```

Malformed requests return `400`. Calls within a model-probe cooldown return
`429` with `Retry-After`. Provider failures return a stable status such as
`MODEL_NOT_FOUND`, `MODEL_TIMEOUT`, or `PROVIDER_UNAVAILABLE` without exposing
the raw provider exception.

Saved configuration goes through `PUT /api/council/config/draft`, which performs
the strict validation and atomic file replacement used by the workbench. A
restart is required before the saved overlay becomes active.
