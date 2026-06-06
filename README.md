# llm-council (Java)

Java + Spring Boot implementation of an **LLM Council** – a multi-model deliberation pipeline inspired by Andrej Karpathy’s original LLM Council.[web:4][web:19]

Instead of trusting a single model, a council of LLMs:

1. Independently answers your question.
2. Anonymously reviews and scores each other’s drafts.
3. (Optionally) Debates contentious points.
4. A chair model synthesizes a final answer, preserving dissent and uncertainty.

## Goals

- Provide a **JVM-native** implementation using Spring Boot and Spring AI.
- Make the **protocol pluggable**: “quick”, “balanced”, “rigorous” council flows.
- Offer clear extension points for new providers, models, and stages.
- Be production-friendly: observable, testable, and configurable.

## Architecture

- `api` – REST controller for creating sessions and running the council.
- `application` – `CouncilService` plus event publishing abstraction.
- `orchestration` – `ProtocolOrchestrator`, `StageExecutor`s, prompts, debate, validation, export.
- `model` – `ModelRegistry`, `ModelClient` abstractions and implementations (Spring AI, OpenAI-compatible, Ollama, mock).
- `domain` – `CouncilSession`, `DepthMode`, `CouncilStatus`, etc.
- `persistence` – `SessionStore` and `ArtifactStore` (in-memory + file-backed implementations).
- `export` – `ExportManifest`, `ExportPackageService` for packaging artifacts (e.g., ZIP bundles).

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Access credentials for at least one LLM provider supported by Spring AI
  (e.g., OpenAI, Anthropic, Ollama, or a compatible gateway).

### Configuration

The default configuration lives in `src/main/resources/application.yml`:

```yaml
council:
  protocols:
    quick:
      description: Fast low-cost council.
      ordered-stages: [GENERATE, ANONYMIZE, SYNTHESIZE]
    balanced:
      description: Default real council.
      ordered-stages: [GENERATE, ANONYMIZE, REVIEW, SCORE, SYNTHESIZE, VALIDATE]
      stage-options:
        SCORE:
          artifact-label: initial
    rigorous:
      description: High-rigor council with bounded debate and export.
      ordered-stages: [GENERATE, ANONYMIZE, REVIEW, SCORE, DEBATE, SCORE, SYNTHESIZE, VALIDATE, EXPORT]
      stage-options:
        SCORE:
          artifact-label: initial
        DEBATE:
          max-rounds: 2
          debate-trigger-score-variance: 120.0
          debate-trigger-dissent-count: 2
          force-run: true
          preserve-dissent: true
        EXPORT:
          export-raw-artifacts: false
          artifact-label: redacted
```

You can override these values per environment using standard Spring Boot configuration mechanisms.

Model/provider configuration is handled by `ModelClientConfig` and `CouncilProperties`.

### Build

```bash
mvn clean package
```

### Run

```bash
java -jar target/llm-council-*.jar
```

By default, the app runs on port `8080`. Health and metrics are exposed via Spring Boot Actuator.

### API (high-level)

> Note: This is a simplified overview; you can add Springdoc/OpenAPI for full schema documentation.

- `POST /api/council/sessions`
    - Creates a council session.
    - Body: `CreateSessionRequest` (question, optional context, depth mode, protocol ID, council profile ID).
    - Response: `SessionResponse` with `sessionId` and initial status.

- `POST /api/council/sessions/{sessionId}/run`
    - Runs the configured protocol for the session.
    - Response: `CouncilEventResponse` including final synthesized answer and artefact references.

- `GET /api/council/sessions/{sessionId}`
    - Returns status, events, and any exported artifacts for the session.

### Extending

#### Add a new model

1. Implement `CouncilModelClient` (or configure a Spring AI `ChatClient` in `ModelClientConfig`).
2. Add a `ModelProfile` entry referencing the provider model ID, default tokens, temperature, etc.
3. Register the profile in `ModelRegistry`.
4. Reference the new model in a `CouncilProfile`’s `memberModelIds`.

#### Add a new protocol

1. Define a new protocol ID and `ordered-stages` sequence in configuration.
2. Optionally add `stage-options` for stages like `DEBATE`, `SCORE`, or `EXPORT`.
3. Use the new protocol ID from the client (e.g. `balanced`, `rigorous`, or your own).

### Attribution

This project is conceptually inspired by Andrej Karpathy’s original **LLM Council** repository and associated blog content.[web:4][web:19]

Please review the upstream license and ensure your usage complies with it if you incorporate additional assets or prompts.

## License

TBD – choose a license compatible with your intended usage and any upstream components (MIT/Apache-2.0 are common choices).