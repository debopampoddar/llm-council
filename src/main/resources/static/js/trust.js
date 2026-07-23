// trust.js — everything that qualifies an answer.
//
// The council's value is not the answer alone; a single model produces one of
// those far faster. The value is knowing how much to trust it. So every signal
// that weakens a claim renders *above* the prose, never below it and never
// behind a disclosure triangle — a reader skims to the recommendation and
// stops, and anything under it becomes a footnote to a conclusion they have
// already accepted.
//
// Two rules shape this whole module:
//
// 1. Confidence never appears without its provenance. A "validated" marker
//    makes a reader trust an answer *more*, so attaching one to a run where the
//    chair graded its own synthesis is actively misleading. The number and the
//    independence tier are one fused element with no path that renders either
//    alone.
//
// 2. Absence of a signal is not absence of a problem. "No sycophancy detected"
//    is only true if detection ran — it starts at debate round 1, so a run
//    where debate never happened measured nothing at all. Saying "none" there
//    would be a claim the run did not earn.

import { el, pill } from "./dom.js";
import { renderInline } from "./markdown.js";

const INDEPENDENCE = {
  INDEPENDENT: ["ok", "independent validator"],
  CORRELATED: ["warn", "correlated validator"],
  SELF_VALIDATION: ["qual", "self-validated"],
  NOT_APPLICABLE: ["mute", "no validation"],
};

/**
 * The confidence figure fused to the provenance that qualifies it.
 *
 * <p>Deliberately one element. There is no way to render the number without the
 * tier, because the number means something different when the chair was also
 * the validator.
 */
function confidenceUnit(result) {
  const [tone, label] = INDEPENDENCE[result.validationIndependence] || ["mute", "independence unknown"];

  if (!result.validation) {
    // No VALIDATE stage ran — QUICK skips it. An empty space here would read as
    // "nothing to report"; it has to read as "this was never checked".
    return el("span.conf-unit.conf-none", {}, [
      el("span.v", { text: "not validated" }),
      el("span.q", { text: label }),
    ]);
  }

  const confidence = result.validation.confidence;
  return el(`span.conf-unit.tier-${tone}`, {}, [
    el("span.v", { text: `${confidence == null ? "—" : Number(confidence).toFixed(2)} confidence` }),
    el("span.q", { text: label }),
  ]);
}

/**
 * Render the trust strip.
 *
 * @param result  the CouncilRunResponse
 * @param context {debateRan, testOnly}
 */
export function renderTrustStrip(result, context = {}) {
  const participating = result.participatingModels || [];
  const excluded = result.excludedModels || [];
  const sycophancy = result.sycophancyWarnings || [];
  const warnings = result.warnings || [];
  const total = participating.length + excluded.length;

  const pills = [confidenceUnit(result)];

  pills.push(excluded.length
    ? pill("warn", `${participating.length} of ${total} members`)
    : pill("ok", `${participating.length} member${participating.length === 1 ? "" : "s"}`));

  if (excluded.length) pills.push(pill("crit", `${excluded.length} excluded`));

  // The three-way distinction that matters most on this strip.
  if (sycophancy.length) {
    pills.push(pill("crit", `${sycophancy.length} sycophancy flag${sycophancy.length === 1 ? "" : "s"}`));
  } else if (context.debateRan) {
    pills.push(pill("ok", "no sycophancy"));
  } else {
    pills.push(pill("warn", "sycophancy not measured"));
  }

  if (warnings.length) pills.push(pill("warn", `${warnings.length} warning${warnings.length === 1 ? "" : "s"}`));
  if (result.status !== "COMPLETED") pills.push(pill("crit", result.status));
  if (context.testOnly) pills.push(pill("warn", "synthetic output"));

  return el("div.trust", {}, [el("span.lead", { text: "Trust" }), ...pills]);
}

/** Sycophancy findings. Never collapsed — this is the failure the product exists to detect. */
export function renderSycophancy(result) {
  const warnings = result.sycophancyWarnings || [];
  if (!warnings.length) return null;

  return el("div.syc", {}, [
    el("div.hd", {}, [
      el("h4", { text: `Sycophancy detected — ${warnings.length} finding${warnings.length === 1 ? "" : "s"}` }),
      pill("crit", "never collapsed"),
    ]),
    ...warnings.map((warning) => el("p.row", { text: warning })),
    el("p.exp", {
      text: "These members moved toward the prior speaker without changing their reasoning. The consensus in this answer was reached by agreement rather than by argument — weight it accordingly.",
    }),
  ]);
}

/** Models that dropped out, and what that means for the result. */
export function renderExclusions(result) {
  const excluded = result.excludedModels || [];
  const failures = result.modelFailures || [];
  if (!excluded.length && !failures.length) return null;

  const total = (result.participatingModels || []).length + excluded.length;
  return el("div.banner.b-crit", {}, [
    el("span.bt", { text: `${excluded.length} model${excluded.length === 1 ? "" : "s"} excluded from this run` }),
    el("span.bd", {
      text: `The council ran with ${(result.participatingModels || []).length} of ${total} members. Quorum was met, so the run completed — but this is not the council that was configured.`,
    }),
    ...failures.map((failure) =>
      el("span.bd.mono", { text: `${failure.modelId}: ${failure.category} — ${failure.message || "no detail"}` })),
  ]);
}

/** Run warnings, each with what it means rather than only its code. */
export function renderWarnings(result) {
  const warnings = result.warnings || [];
  if (!warnings.length) return null;

  return warnings.map((warning) => {
    const truncated = warning.includes("CONTEXT_BUDGET_EXCEEDED") || warning.toLowerCase().includes("budget");
    return el("div.banner.b-warn", {}, [
      el("span.bt", { text: warning }),
      truncated
        ? el("span.bd", { text: "Evidence for the final answer was cut to fit the chair's context window, so the synthesis did not see every review." })
        : null,
    ]);
  });
}

// The synthesis prompt asks the chair for a numbered structure whose third
// section is "important dissent". Real models follow that loosely, so this is
// best-effort by nature — which is exactly why a failure to find the section is
// reported as a failure to find it, never as an absence of dissent.
const DISSENT_HEADING = /^\s*(?:\d+[.)]\s*)?(?:important\s+)?dissent\s*[:\-—]?\s*/i;
const NEXT_HEADING = /^\s*(?:\d+[.)]\s*)?(?:unresolved\s+risks?|risks?|confidence|caveats?)\s*[:\-—]/i;

function extractDissent(answer) {
  if (!answer) return null;
  const lines = answer.split("\n");
  const start = lines.findIndex((line) => DISSENT_HEADING.test(line));
  if (start === -1) return null;

  const collected = [lines[start].replace(DISSENT_HEADING, "").trim()];
  for (let i = start + 1; i < lines.length; i += 1) {
    if (NEXT_HEADING.test(lines[i])) break;
    collected.push(lines[i]);
  }
  const text = collected.join("\n").trim();
  return text.length ? text : null;
}

/**
 * The preserved-dissent section.
 *
 * <p>Rendered as its own block rather than left as trailing prose, and always
 * present: the three cases — dissent found, preservation disabled, no section
 * produced — are different claims and must not collapse into one another.
 *
 * @param answer           the synthesised answer
 * @param preserveEnabled  whether the protocol had preserve-dissent on
 */
export function renderDissent(answer, preserveEnabled) {
  const dissent = extractDissent(answer);

  if (preserveEnabled === false) {
    return el("div.dissent.dissent-off", {}, [
      el("div.hd", {}, [el("h4", { text: "Preserved dissent" }), pill("warn", "disabled")]),
      el("p", { text: "This protocol has dissent preservation switched off, so the chair was never asked to surface minority positions. Their absence here says nothing about whether any existed." }),
    ]);
  }

  if (!dissent) {
    return el("div.dissent.dissent-none", {}, [
      el("div.hd", {}, [el("h4", { text: "Preserved dissent" }), pill("mute", "no section found")]),
      el("p", { text: "The chair did not produce a labelled dissent section. That is not the same as the council having agreed — it means this run has no record either way." }),
    ]);
  }

  return el("div.dissent", {}, [
    el("div.hd", {}, [el("h4", { text: "Preserved dissent" }), pill("qual", "always shown")]),
    el("p", {}, renderInline(dissent)),
  ]);
}

/**
 * Signals that are absent because a stage never ran.
 *
 * <p>An empty panel reads as "nothing to report". These say which check did not
 * happen and why, so a reader can tell an unearned clean result from a real one.
 */
export function renderAbsentSignals(skippedStages) {
  if (!skippedStages.length) return null;

  return el("div.emptysig", {}, [
    el("div.hd", { text: "Checks that did not run" }),
    ...skippedStages.map((stage) =>
      el("p", { text: `${stage.name}: ${stage.noopReason}` })),
    skippedStages.some((s) => s.name === "DEBATE")
      ? el("p.note", { text: "Sycophancy detection begins at debate round 1, so with no debate it never ran. This answer was not stress-tested by disagreement." })
      : null,
  ]);
}
