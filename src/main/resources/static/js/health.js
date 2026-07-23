// health.js — the pre-send gate.
//
// This converts the most common failure mode — a model that was never pulled —
// from a confusing QUORUM_NOT_MET several minutes into a run, into an obvious
// message before sending.
//
// Three states, not two. The obvious binary is runnable/not-runnable, but the
// API returns a third case: `oci` reports runnable:true with every model at
// NOT_CHECKED and its warnings array populated, because provider health is
// deferred to runtime credentials. Painting that green would promise a
// preflight that never happened — on the profile that costs real money when it
// fails. It gets its own amber tier.

import { el, pill } from "./dom.js";

/**
 * Classify a ProfileHealthResponse.
 *
 * @param health the response body
 * @returns {{tier: string, label: string, summary: string, sendable: boolean}}
 */
export function classify(health) {
  const models = health.models || [];
  const warnings = health.warnings || [];
  const available = models.filter((m) => m.available).length;

  if (!health.runnable) {
    const blocked = models.length - available;
    return {
      tier: "crit",
      label: "not runnable",
      summary: `${blocked} of ${models.length} models unreachable`,
      sendable: false,
    };
  }
  if (warnings.length > 0) {
    return {
      tier: "warn",
      label: "unverified",
      summary: `${models.length} models configured — none were actually checked`,
      sendable: true,
    };
  }
  return {
    tier: "ok",
    label: "ready",
    summary: `${available} of ${models.length} models available`,
    sendable: true,
  };
}

// What to actually do about it. The plan assumed knownProviderModels would
// supply an "installed: llama3.1:8b, ..." hint, but that list comes back empty
// precisely when Ollama is down — the state that most needs the hint. So fall
// back to the connection fix, and only name installed models when the daemon
// answered.
function remediation(health, state) {
  const models = health.models || [];
  const failing = models.filter((m) => !m.available);

  if (state.tier === "crit") {
    const refused = failing.some((m) => (m.detail || "").includes("Connection refused"));
    const ollama = failing.some((m) => m.provider === "ollama");
    if (refused && ollama) {
      return "Ollama is not answering. Start it with `ollama serve`, then pull the models this profile needs.";
    }
    const known = failing.flatMap((m) => m.knownProviderModels || []);
    if (known.length) {
      return `The provider is reachable but is missing these models. Installed: ${known.join(", ")}.`;
    }
    const first = failing[0];
    return first ? `${first.modelId}: ${first.detail || first.status}` : "No model in this policy is reachable.";
  }

  if (state.tier === "warn") {
    return "Credentials and endpoint are validated only once the run starts, so a misconfigured key fails mid-run — after billable calls.";
  }
  return null;
}

/**
 * Render the preflight panel.
 *
 * @param health the ProfileHealthResponse, or null while loading
 * @param independence the validation independence tier for this profile/depth
 */
export function renderHealth(health, independence) {
  if (!health) {
    return el("div.preflight", {}, [
      el("div.pf-top", {}, [pill("mute", "checking…", { led: false })]),
    ]);
  }

  const state = classify(health);
  const fix = remediation(health, state);
  const models = health.models || [];

  const rows = models.map((model) =>
    el(`div.pf-model${model.available ? "" : ".bad"}`, {}, [
      el("span.id", { text: model.modelId }),
      el("span.pm", { text: model.providerModelId }),
      el("span.d", { text: model.detail || model.status }),
    ]));

  return el("div.preflight", {}, [
    el("div.pf-top", {}, [
      pill(state.tier, state.label),
      el("span.pf-msg", { text: `${state.summary} · policy ${health.policyId}` }),
      independence ? independencePill(independence) : null,
    ]),
    el("div.pf-models", {}, rows),
    fix ? el("div.pf-fix", { text: fix }) : null,
  ]);
}

/**
 * A pill for the validation independence tier.
 *
 * <p>Shown before sending, not only on the result: whether the validator is
 * independent of the chair is a property of the configuration, so it is
 * knowable up front. Putting it beside the depth picker makes rigor a choice
 * rather than a disclosure after the fact.
 */
export function independencePill(tier) {
  const map = {
    INDEPENDENT: ["ok", "independent"],
    CORRELATED: ["warn", "correlated validator"],
    SELF_VALIDATION: ["qual", "self-validated"],
    NOT_APPLICABLE: ["mute", "no validation"],
  };
  const [tone, label] = map[tier] || ["mute", String(tier || "unknown").toLowerCase()];
  return pill(tone, label);
}

/** Resolve profile + depth to that policy's independence tier via the catalog. */
export function independenceFor(catalog, profileId, depthMode) {
  if (!catalog) return null;
  const profile = (catalog.profiles || []).find((p) => p.id === profileId);
  if (!profile) return null;
  const policyId = (profile.policyIdsByDepth || {})[depthMode];
  const policy = (catalog.policies || []).find((p) => p.id === policyId);
  return policy ? policy.validationIndependence : null;
}
