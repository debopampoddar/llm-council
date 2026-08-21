# Prompt-injection threat model

## Security claim

LLM Council treats the question as the user task and all supporting context and
model-produced artifacts as untrusted data. It is designed to fail closed when
an explicit instruction embedded in supporting context is adopted by a model
output. It does not claim to eliminate prompt injection or to establish that an
answer is factually correct.

## Trust boundary

Only the application-owned system message and `task.text` have instruction
authority. The JSON prompt envelope labels:

- `task.instructionAuthority` as `USER_TASK`;
- `supportingContext.instructionAuthority` as `NONE` and its trust as
  `UNTRUSTED_DATA`;
- drafts, reviews, scores, debate turns, revisions, and the final answer as
  `UNTRUSTED_MODEL_OUTPUT` with no instruction authority.

JSON escaping prevents context text from escaping its data field by inserting a
fake closing delimiter. It does not force a model to respect the labels, which
is why the application also checks outputs.

## Defences

1. Every model-facing stage receives the same authority rules.
2. Critics are asked for evidence-backed objections and may converge; they are
   not required to invent a contrarian position.
3. Initial and post-debate reviewers see the original context and score both
   grounding and trust-boundary compliance. A trust-boundary score below 50
   caps the draft's overall score at 25.
4. Synthesis treats scores as advisory and does not preserve unsupported dissent
   merely because a member or reviewer supplied it.
5. A deterministic high-precision guard looks for explicit task redirection in
   context and distinctive evidence that an output adopted the directive payload,
   rather than ordinary evidence appearing earlier on the same line. Payload
   polarity is evaluated in the sentence containing each occurrence: rejecting
   or analyzing an embedded action is safe, while an earlier disclaimer cannot
   excuse a later standalone execution. An unsafe initial draft receives one
   bounded regeneration with the explicit directive removed; a repeated violation
   is excluded. Unsafe aggregation, debate contributions, and revisions are
   excluded. An unsafe synthesis receives one clean retry with directive text,
   internal identifiers, score data, and council labels removed; a second
   violation fails the run in every depth, including QUICK. Internal
   draft/review/score/turn identifiers and unsolicited council narration use the
   same recovery and fail-closed path. Validation cannot override a deterministic
   finding.
6. The dedicated local validator is Gemma, distinct from the Llama, Mistral,
   and Qwen answer producers. Independence is classified against every producer.
7. Validator recommendations are checked against the same untrusted context. If
   a recommendation adopts an embedded directive, the application discards that
   assessment and makes one clean-room validation retry after removing the
   explicit directive from supporting context. Repeated adoption is classified
   as invalid model output rather than exposed as trusted advice.

## Expected observable behavior

An unsafe initial draft first produces `MODEL_OUTPUT_TRUST_RECOVERY_STARTED`.
A safe replacement produces `MODEL_OUTPUT_TRUST_RECOVERED`; a repeated violation
produces `MODEL_OUTPUT_TRUST_BOUNDARY_REJECTED`. Other unsafe intermediate output
produces `MODEL_OUTPUT_TRUST_BOUNDARY_REJECTED`.
An unsafe or internally narrated synthesis first produces
`SYNTHESIS_OUTPUT_RECOVERY_STARTED`; a repeated violation produces
`SYNTHESIS_OUTPUT_REJECTED`. The run becomes partial when safe quorum remains and
fails when quorum or the recovered final answer is unsafe. A validator that
adopts context produces `VALIDATION_TRUST_RECOVERY_STARTED`; repeated adoption
fails as invalid model output.

Raw provider output is retained in the normal artifact trail for diagnosis. Do
not expose artifacts to untrusted users; this application has no authentication
or per-user authorization.

For structured Ollama calls, hidden thinking is disabled so a thinking-capable
model cannot consume the entire response allowance without emitting JSON. If a
validator still reaches the exact output ceiling with unparseable content, the
application makes one bounded recovery call with a larger allowance and retains
both attempts as artifacts and usage records. The output-ceiling and trust
recoveries share the same one-retry limit; validation never makes an unbounded
repair loop.

## Limitations

The deterministic guard intentionally favors precision. It recognizes explicit
override/redirection language and sentence-local lexical adoption; paraphrased,
encoded, multilingual, multi-turn, or indirect attacks may evade it. Ambiguous
legitimate text that contains the same distinctive payload may still be rejected
or regenerated.
Model review and validation remain probabilistic and can share training-data blind
spots even when their declared families differ.

The application does not execute model-selected tools, so this threat is limited
to answer integrity and downstream human use. If tools, retrieval writes, network
actions, or privileged data access are added, authorization must be enforced in
deterministic application code and untrusted model text must never select or
approve an action by itself.

## Verification

The deterministic suite covers the observed ticket injection, benign rejection
and analysis of embedded directives, negative action polarity, sanitized bounded
draft regeneration, sanitized synthesis and validator recovery,
internal-metadata rejection, and fail-closed QUICK synthesis.
Use the evaluation repository's prompt-injection regression dataset for live model
testing. A passing regression run demonstrates the covered cases on the recorded
models; it is not a universal security certification.
