// timeline.js — the council stage timeline.
//
// Two things here are not obvious from the API.
//
// 1. Rows are keyed on payload.stageIndex, not on the stage name. The rigorous
//    protocol runs SCORE twice; keying by name would collapse the two passes
//    into one row and lose the second.
//
// 2. A stage that does nothing still reports STAGE_COMPLETED. The skip is a
//    separate domain event published inside the stage — DEBATE emits
//    STAGE_STARTED, DEBATE_SKIPPED, STAGE_COMPLETED, in that order. Driving the
//    timeline off STAGE_SKIPPED alone (which only fires once the context has
//    already gone terminal) paints DEBATE green, so a council that never
//    debated looks exactly like one that debated and agreed. That is the
//    misreading this whole product exists to prevent, so any *_SKIPPED event
//    inside a stage overrides its completion and its reason is shown verbatim.
//
// The suffix match is deliberate rather than an enumerated list: new no-op
// events have been added to this codebase over time, and a stage silently
// turning green is exactly the failure that would not be noticed.

import { el, replace, pill, elapsedMs, formatDuration } from "./dom.js";

const isNoop = (type) => type.endsWith("_SKIPPED");

/**
 * Fold a council event stream into ordered stage rows.
 *
 * @param events council events, in arrival order
 * @returns {{rows: Array, protocolId: string, status: string}}
 */
export function buildTimeline(events) {
  const rows = [];
  let protocolId = null;
  let status = "running";

  const rowAt = (index, name) => {
    if (!rows[index]) {
      rows[index] = {
        index, name, status: "pending",
        startedAt: null, completedAt: null,
        noopReason: null, errors: [], detail: [],
      };
    }
    if (name) rows[index].name = name;
    return rows[index];
  };

  for (const event of events) {
    const { stage, type, payload = {}, occurredAt, modelId } = event;

    if (type === "PROTOCOL_STARTED") {
      protocolId = payload.protocolId || null;
      // The full ordered stage list arrives up front, so the timeline can show
      // where a run is inside an 11-stage protocol rather than growing from
      // nothing as events land.
      (payload.stages || []).forEach((name, index) => rowAt(index, name));
      continue;
    }
    if (type === "PROTOCOL_COMPLETED") { status = "completed"; continue; }
    if (type === "PROTOCOL_FAILED") { status = "failed"; continue; }

    const index = payload.stageIndex;
    if (type === "STAGE_STARTED" && Number.isInteger(index)) {
      const row = rowAt(index, stage);
      row.status = "running";
      row.startedAt = occurredAt;
      continue;
    }

    // Everything below belongs to whichever stage is currently open. Only
    // STAGE_STARTED carries stageIndex, so track the latest running row.
    const current = [...rows].reverse().find((r) => r && r.status !== "pending");
    if (!current) continue;

    if (type === "STAGE_COMPLETED") {
      current.completedAt = occurredAt;
      // A no-op already claimed this row; completion must not overwrite it.
      current.status = current.status === "noop" ? "noop" : "done";
    } else if (type === "STAGE_FAILED") {
      current.completedAt = occurredAt;
      current.status = "failed";
    } else if (isNoop(type)) {
      current.status = "noop";
      current.noopReason = payload.reason || "no reason reported";
      current.detail.push({ type, payload });
    } else if (type === "MODEL_CALL_FAILED") {
      current.errors.push({ modelId, detail: payload.message || payload.reason || type });
      current.detail.push({ type, payload, modelId });
    } else {
      current.detail.push({ type, payload, modelId });
    }
  }

  return { rows: rows.filter(Boolean), protocolId, status };
}

// One-line summary per stage, from whichever detail events it published.
function summarise(row) {
  if (row.status === "pending") return "not started";
  if (row.status === "noop") return `did not run — ${row.noopReason}`;

  const find = (type) => row.detail.find((d) => d.type === type);
  const count = (type) => row.detail.filter((d) => d.type === type).length;

  const score = find("SCORE_COMPLETED");
  if (score) {
    return `${score.payload.strategy} · variance ${score.payload.variance} · label ${score.payload.label}`;
  }
  const validation = find("VALIDATION_PASSED") || find("VALIDATION_FAILED");
  if (validation) {
    const verdict = validation.type === "VALIDATION_PASSED" ? "approved" : "rejected";
    return `${verdict} · confidence ${validation.payload.confidence}`;
  }
  const synthesis = find("SYNTHESIS_COMPLETED");
  if (synthesis) return `${synthesis.payload.chars} characters synthesised`;
  const exported = find("EXPORT_COMPLETED");
  if (exported) {
    return `${exported.payload.artifactCount} artifacts · ${exported.payload.draftCount} drafts · ${exported.payload.reviewCount} reviews`;
  }
  const reviews = row.detail
    .filter((d) => d.type === "REVIEW_COMPLETED" || d.type === "POST_DEBATE_REVIEW_COMPLETED")
    .reduce((total, d) => total + (d.payload.reviewCount || 0), 0);
  if (reviews) return `${reviews} reviews`;

  const calls = count("MODEL_CALL_COMPLETED");
  if (calls) return `${calls} model call${calls === 1 ? "" : "s"}`;
  if (row.status === "running") return "running…";
  return "completed";
}

const NODE_CLASS = {
  pending: "tl-node",
  running: "tl-node run",
  done: "tl-node done",
  noop: "tl-node noop",
  failed: "tl-node fail",
};

/** Render the timeline into `container`. */
export function renderTimeline(container, events, options = {}) {
  const { rows, protocolId, status } = buildTimeline(events);
  if (!rows.length) {
    replace(container, el("p.tl-waiting", { text: "Waiting for the first stage…" }));
    return;
  }

  const nodes = rows.map((row, position) => {
    const duration = elapsedMs(row.startedAt, row.completedAt);
    const open = options.expanded?.has(row.index) || false;
    const panelId = `stage-panel-${row.index}`;

    const head = el("button.tl-head", {
      type: "button",
      "aria-expanded": String(open),
      "aria-controls": panelId,
      onClick: () => options.onToggle && options.onToggle(row.index),
    }, [
      el("span.caret", { text: "▶" }),
      el("span.name", { text: row.name }),
      el("span.sub", { text: summarise(row) }),
    ]);

    const panel = el("div.tl-panel", { id: panelId, hidden: open ? null : "hidden" },
      options.renderPanel ? options.renderPanel(row) : renderDefaultPanel(row));

    return el(`div.tl-row${row.status === "noop" ? ".is-noop" : ""}`, {}, [
      el("div.tl-gut", {}, [
        el(`span.${NODE_CLASS[row.status].replace(/ /g, ".")}`),
        position === rows.length - 1 ? null : el("span.tl-line"),
      ]),
      el("div.tl-body", {}, [
        head,
        panel,
        ...row.errors.map((error) =>
          el("div.tl-error", {}, [
            el("span.m", { text: error.modelId || "model" }),
            el("span", { text: error.detail }),
          ])),
      ]),
      el("div.tl-time", { text: duration === null ? "" : formatDuration(duration) }),
    ]);
  });

  const done = rows.filter((r) => r.status !== "pending").length;
  const header = el("div.tl-head-row", {}, [
    pill(status === "failed" ? "crit" : status === "completed" ? "ok" : "accent",
         status === "running" ? `stage ${done} of ${rows.length}` : status),
    protocolId ? el("span.tl-proto", { text: `protocol ${protocolId}` }) : null,
  ]);

  replace(container, [header, el("div.tl", {}, nodes)]);
}

// Until artifact drill-down lands, show the stage's own events. This is already
// more than the plan asked for at this point: the reason a stage did nothing is
// in these payloads, and it is the thing a reader most needs.
function renderDefaultPanel(row) {
  if (!row.detail.length) return el("p.tl-empty", { text: "No detail events for this stage." });
  return row.detail.map((entry) =>
    el("div.tl-detail", {}, [
      el("span.k", { text: entry.type }),
      entry.modelId ? el("span.m", { text: entry.modelId }) : null,
      el("span.v", { text: JSON.stringify(entry.payload) }),
    ]));
}
