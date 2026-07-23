// artifacts.js — stage evidence, fetched lazily and rendered per artifact type.
//
// Nothing is prefetched. A rigorous run writes 16 files and most stages are
// never opened, so bodies are pulled only when a stage is expanded.
//
// On anonymisation: the stored artifacts carry modelId next to
// `anonymous: true`, and reviews carry reviewerId in clear. That is correct
// behaviour — the alias blinds the *reviewer's prompt*, which is where
// authorship bias would enter — but a panel captioned "anonymised" above a
// table of model names invites the reader to conclude the whole mechanism is
// theatre. The caption says what the aliases actually do.

import { el, pill } from "./dom.js";

/**
 * Which artifacts belong to a timeline row.
 *
 * <p>SCORE runs twice, so the pass is resolved from the label the stage
 * reported rather than guessed from position.
 */
export function artifactsForStage(row) {
  const scoreEvent = row.detail.find((d) => d.type === "SCORE_COMPLETED");
  switch (row.name) {
    case "GENERATE": return ["normalized/drafts-generation.json"];
    case "ANONYMIZE": return ["private/anonymization-map.json", "normalized/anonymized-drafts.json"];
    case "REVIEW": return ["normalized/reviews.json"];
    case "REVIEW_POST_DEBATE": return ["normalized/reviews-post-debate.json"];
    case "SCORE": return [`normalized/scores-${scoreEvent?.payload?.label || "initial"}.json`];
    case "SYNTHESIZE": return ["final/answer.md"];
    case "VALIDATE": return ["final/validation.json"];
    case "EXPORT": return ["exports/manifest.json"];
    default: return [];
  }
}

const table = (headers, rows) =>
  el("div.tbl-scroll", {}, [
    el("table", {}, [
      el("thead", {}, [el("tr", {}, headers.map((h) => el("th", { text: h })))]),
      el("tbody", {}, rows.map((cells) =>
        el("tr", {}, cells.map((cell) =>
          el(typeof cell === "number" ? "td.num" : "td", { text: String(cell ?? "—") }))))),
    ]),
  ]);

function renderDrafts(drafts) {
  return el("div.cards", {}, drafts.map((draft) =>
    el("div.card", {}, [
      el("div.ch", {}, [
        pill("mute", draft.modelId || "unknown", { led: false }),
        el("span.cid", { text: draft.draftId }),
      ]),
      el("p.ct", { text: draft.text || "" }),
    ])));
}

function renderAliasMap(map) {
  const rows = Object.entries(map).map(([alias, entry]) =>
    el("div.map-row", {}, [
      el("span.a", { text: alias }),
      el("span.arr", { text: "←" }),
      el("span.b", { text: entry.modelId || entry.originalDraftId }),
    ]));

  return el("div.stack", {}, [
    ...rows,
    el("p.caption", {
      text: "These aliases replace model names in the reviewer's prompt, which is where authorship bias would enter. The stored artifacts still record who wrote what — the mapping above lives in private/ and is what makes an anonymised review auditable afterwards.",
    }),
  ]);
}

function renderReviews(reviews) {
  const criteria = ["accuracy", "completeness", "reasoning", "clarity", "constructiveness"];
  const headers = ["Reviewer", "Draft", ...criteria.map((c) => c.slice(0, 4)), "Overall", "Conf"];
  const rows = reviews.map((review) => {
    const byName = Object.fromEntries((review.criteria || []).map((c) => [c.name, c.score]));
    return [
      review.reviewerId, review.draftId,
      ...criteria.map((c) => byName[c] ?? "—"),
      review.overallScore, review.confidence,
    ];
  });
  return table(headers, rows);
}

function renderScores(summary) {
  const scores = [...(summary.scores || [])].sort((a, b) => b.weightedTotal - a.weightedTotal);
  const rows = scores.map((score, index) => [
    index + 1, score.draftId, score.weightedTotal, score.label,
  ]);
  return el("div.stack", {}, [
    table(["Rank", "Draft", "Weighted total", "Label"], rows),
    el("dl.kv", {}, [
      el("dt", { text: "variance" }), el("dd.mono", { text: String(summary.variance) }),
      el("dt", { text: "winner" }), el("dd.mono", { text: summary.winningDraftId || "—" }),
      el("dt", { text: "escalated" }), el("dd.mono", { text: String(summary.escalated) }),
    ]),
  ]);
}

function renderValidation(validation) {
  const criteria = Object.entries(validation.criteria || {});
  return el("div.stack", {}, [
    el("div.row-pills", {}, [
      pill(validation.approved ? "ok" : "crit", validation.approved ? "approved" : "rejected"),
      pill("mute", `${(validation.issues || []).length} issues`, { led: false }),
      validation.requiresHumanReview ? pill("warn", "human review required") : null,
    ]),
    criteria.length ? table(["Criterion", "Verdict"], criteria) : null,
    el("p.caption", { text: `Validator: ${validation.validatorId}. Whether that is independent of the chair is shown on the trust strip above.` }),
  ]);
}

/** Render one artifact according to what it is. */
export function renderArtifact(path, data) {
  const header = el("div.src", { text: `▸ ${path}` });

  let body;
  try {
    if (path.endsWith("drafts-generation.json") || path.endsWith("anonymized-drafts.json")) {
      body = renderDrafts(data);
    } else if (path.endsWith("anonymization-map.json")) {
      body = renderAliasMap(data);
    } else if (path.includes("reviews")) {
      body = renderReviews(data);
    } else if (path.includes("scores-")) {
      body = renderScores(data);
    } else if (path.endsWith("validation.json")) {
      body = renderValidation(data);
    } else if (path.endsWith(".md") || typeof data === "string") {
      body = el("pre.artifact-text", { text: typeof data === "string" ? data : JSON.stringify(data, null, 2) });
    } else {
      body = el("pre.artifact-text", { text: JSON.stringify(data, null, 2) });
    }
  } catch (error) {
    // A rendering failure must not hide the evidence; fall back to the raw body.
    body = el("pre.artifact-text", { text: JSON.stringify(data, null, 2) });
  }

  return el("div.artifact", {}, [header, body]);
}
