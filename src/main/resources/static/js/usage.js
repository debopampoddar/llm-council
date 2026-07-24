// usage.js — what the run consumed.
//
// A send button in front of `multi-cloud-rigorous` is thirty to forty cloud
// calls per question, so the cost of an answer belongs next to the answer.
//
// One rule governs this whole module: **zero is not free.** Every model in this
// application ships unpriced, so a run that renders "$0.00" is almost always
// reporting "nobody told me the price", not "this cost nothing". Rendering it
// as a number would make an expensive cloud council look identical to a local
// one. Unpriced therefore renders as an em dash, everywhere, with no path that
// turns a missing price into a figure.

import { el, pill } from "./dom.js";

/**
 * Format a cost for display.
 *
 * @param cost the estimatedCostUsd field, null when nothing priced
 * @returns "—" for an unpriced run, a dollar figure otherwise
 */
export function formatCost(cost) {
  if (cost === null || cost === undefined) return "—";
  // Sub-cent totals are the common case on a short run; showing $0.00 for a
  // call that did cost something is the same lie in a smaller font.
  if (cost > 0 && cost < 0.01) return "<$0.01";
  return `$${Number(cost).toFixed(2)}`;
}

/** Thousands separators; token counts run to six figures on a rigorous run. */
function formatTokens(count) {
  return Number(count || 0).toLocaleString();
}

/**
 * The usage strip: total tokens, cost, and the qualifiers on both.
 *
 * @param usage the UsageSummary from a CouncilRunResponse, or null
 */
export function renderUsage(usage) {
  if (!usage || !usage.calls) return null;

  const pills = [
    el("span.usage-unit", {}, [
      el("span.v", { text: `${formatTokens(usage.totalTokens)} tokens` }),
      el("span.q", { text: `${usage.calls} call${usage.calls === 1 ? "" : "s"}` }),
    ]),
    el("span.usage-unit", {}, [
      el("span.v", { text: formatCost(usage.estimatedCostUsd) }),
      el("span.q", {
        text: usage.estimatedCostUsd === null || usage.estimatedCostUsd === undefined
          ? "unpriced"
          : "estimated",
      }),
    ]),
  ];

  // The two ways this figure can be short of the truth. They are different
  // faults with different fixes, so they never collapse into one pill.
  if (usage.estimated) {
    pills.push(pill("warn", "some calls reported no usage"));
  }
  if (usage.partiallyPriced) {
    pills.push(pill("warn", "some models unpriced"));
  }

  return el("div.usage", {}, [el("span.lead", { text: "Cost" }), ...pills, renderBreakdown(usage)]);
}

/** Per-model and per-stage rows, collapsed by default — the strip is the headline. */
function renderBreakdown(usage) {
  const models = usage.byModel || [];
  const stages = usage.byStage || [];
  if (!models.length && !stages.length) return null;

  return el("details.usage-detail", {}, [
    el("summary", { text: "breakdown" }),
    models.length ? el("div.usage-group", {}, [
      el("div.usage-hd", { text: "By model" }),
      ...models.map((row) => usageRow(row.modelId, row)),
    ]) : null,
    stages.length ? el("div.usage-group", {}, [
      el("div.usage-hd", { text: "By stage" }),
      ...stages.map((row) => usageRow(row.stage, row)),
    ]) : null,
  ]);
}

function usageRow(label, row) {
  return el("div.usage-row", {}, [
    el("span.n", { text: label }),
    el("span.t", { text: `${formatTokens(row.totalTokens)} tok` }),
    el("span.c", { text: formatCost(row.estimatedCostUsd) }),
  ]);
}
