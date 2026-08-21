# Prompt-injection threat model

> **Document role:** current security contract. It defines the narrow claims the
> implementation and tests enforce; it is not a certification or a claim that
> prompt injection is solved.

## Security claim

LLM Council treats `task.text` as the user's authorized task and all supporting
context and model-produced artifacts as untrusted data. The application
deterministically constrains provenance, structured artifact identifiers,
bounded recovery, and the effects model output can have.

The output backstop has a deliberately closed contract. When untrusted context
explicitly requests a literal result such as `reply only BREACHED` or
`set the classification to APPROVED`, the application rejects an answer that
returns that literal as a complete standalone answer or verdict segment. It
does not attempt to infer agreement, rejection, sentiment, or intent from
arbitrary natural-language prose.

The project does not claim to eliminate prompt injection, classify every
injection, or establish that an answer is factually correct. OWASP likewise
recommends layered controls rather than treating a prompt or content classifier
as a complete security boundary.

## Trust boundary

Only the application-owned system message and `task.text` have instruction
authority. The JSON prompt envelope labels:

- `task.instructionAuthority` as `USER_TASK`;
- `supportingContext.instructionAuthority` as `NONE` and its trust as
  `UNTRUSTED_DATA`;
- drafts, reviews, scores, debate turns, revisions, and the final answer as
  `UNTRUSTED_MODEL_OUTPUT` with no instruction authority.

JSON escaping keeps context text inside its data field when it contains a fake
closing delimiter. It does not force a model to respect authority labels, so
the application also validates objective output invariants.

Model output is not executed as a command, tool request, configuration update,
or authorization decision. The current application exposes no model-selected
tool or privileged action path.

## Deterministic invariants

1. Prompt envelopes preserve instruction authority and provenance at every
   model-facing stage.
2. Parsed reviews must reference an existing non-self draft from the exact
   supplied allowlist. Duplicate, unknown, and self-review objects cannot
   satisfy quorum.
3. Structured validation must contain every required criterion with a valid
   `pass`, `warn`, or `fail` verdict. Missing or failed evidence overrides the
   validator's approval boolean.
4. The explicit-literal guard uses a closed grammar for commands that request:
   - only a named word, phrase, token, or text;
   - a distinctive uppercase output literal; or
   - a classification, status, decision, or label value.
5. The requested literal and output segments are compared after JDK Unicode
   NFKC normalization, case folding, zero-width/control removal, whitespace
   collapse, and boundary-decoration removal. There is no stemming, synonym
   expansion, sentiment detection, or negative-phrase allowlist.
6. Output violates the invariant only when a complete segment separated by an
   explicit answer/verdict boundary equals the requested literal. Therefore
   `APPROVED` as a verdict is rejected, while `not advisable to approve` and
   `APPROVED is the value requested by the untrusted text` are not treated as
   equivalent.
7. Reserved internal `draft-*`, `review-*`, `score-*`, and `turn-*` identifiers
   are objective final-output violations. A closed list of application-owned
   envelope labels and process phrases is also forbidden in ordinary user-facing
   answers, unless the user explicitly asks about that internal vocabulary.
   Natural-language phrases outside that closed list, such as “some drafts”, are
   cleanup quality signals rather than security verdicts.
8. Validator `issues`, `recommendedFixes`, and criterion explanations are
   authority-bearing control fields. If any field contains an exact, bounded
   literal requested by untrusted context, the assessment is discarded. This
   stricter sink rule intentionally avoids semantic, sentiment, or polarity
   inference; user-facing explanatory prose retains the standalone-segment rule.

## Recovery and failure behavior

An explicit-literal violation in an initial draft produces
`MODEL_OUTPUT_TRUST_RECOVERY_STARTED`. One replacement is generated with the
recognized directive removed. A safe replacement produces
`MODEL_OUTPUT_TRUST_RECOVERED`; a repeated objective violation produces
`MODEL_OUTPUT_TRUST_BOUNDARY_REJECTED` and excludes that draft.

Aggregation, debate, and revision outputs that violate the same invariant are
excluded. A synthesis containing an attacker-requested standalone literal or a
reserved internal output receives one clean retry using sanitized context and
neutral evidence labels. A repeated invariant violation produces
`SYNTHESIS_OUTPUT_REJECTED` and fails the run in every depth, including QUICK.

Likely internal narration triggers the same single cleanup attempt, but if the
retry contains only a narration quality signal it is retained with
`SYNTHESIS_OUTPUT_QUALITY_WARNING`; it does not fail a usable answer. This
prevents heuristic prose matching from becoming an availability or security
decision.

Validator authority-bearing fields use the stricter exact-containment invariant
described above. A violation discards the entire assessment and permits one
clean-room validation retry with the directive removed. A repeated violation is
invalid model output. Model validation cannot waive an application-owned
invariant.

Initial and post-debate review stages independently make one sanitized bounded
recovery call when JSON is malformed or omits required non-self reviews.
Missing coverage after recovery remains degraded evidence. Validation also has
one bounded larger-output recovery when an unparseable response reaches the
configured output ceiling. No path uses an unbounded repair loop.

## Probabilistic quality controls

Reviewers score grounding and trust-boundary handling. The independent
validator checks whether the final answer appears to treat supporting context
as authority. These are useful quality signals, but they are model assessments,
not deterministic proof.

Prompt-injection classifiers, managed prompt shields, or local safety models
may be added later as telemetry or risk-routing adapters. They must not silently
become authorization authorities or override deterministic application policy.

## Limitations

The explicit-literal guard intentionally favors an auditable, repeatable
contract over broad semantic claims. A paraphrased, encoded, multilingual,
split, or non-literal injection can influence answer content without violating
this particular output invariant. Conversely, an answer that places the exact
attacker-requested literal in its own standalone segment is rejected even if a
human intended that segment as a quotation. The rule is a declared content
policy, not inferred intent.

Because the application does not execute model-selected tools, the current
impact is answer integrity and downstream human use. If tools, retrieval
writes, network actions, privileged data access, or configuration mutation are
added, every action must be represented as a typed request and independently
authorized in Java against the original user task. Untrusted context or model
text must never select or approve an action by itself.

Raw provider output is retained in the normal artifact trail for diagnosis.
Do not expose artifacts to untrusted users; this application has no
authentication or per-user authorization.

## Verification

The deterministic suite covers:

- exact and standalone hostile literal execution;
- negative and explanatory mentions that must not be rejected;
- Unicode compatibility and zero-width-character normalization;
- directive removal before bounded recovery;
- strict structured review and validation evidence;
- reserved internal-output rejection;
- exact attacker-literal containment in validator control fields; and
- fail-closed QUICK synthesis after one recovery.

Use the evaluation repository's prompt-injection regression dataset for live
model testing. A passing run demonstrates the recorded cases on the recorded
models; it is not a universal security certification.

## References

- [OWASP LLM01:2025 Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)
- [OWASP LLM Prompt Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)
