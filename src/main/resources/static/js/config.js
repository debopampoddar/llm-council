// config.js — advanced configuration workbench.
//
// The page treats the YAML text as the source of truth. A parsed document is
// usable for save only while the exact text that produced it remains unchanged.
// Rendering uses DOM nodes throughout; configuration text and server failures
// are never interpreted as markup.

import { el, replace } from "./dom.js";
import { configApi, ConfigApiError } from "./config-api.js";

const MODEL_EXAMPLE = `version: 1
models:
  - id: my-openai-model
    provider: openai
    providerModelId: gpt-4.1-mini
    defaultOutputTokens: 2048
    temperature: 0.2
    timeoutSeconds: 120
    role: MEMBER
    councilRole: PROPOSER
    modelFamily: openai
`;

const state = {
  schema: null,
  document: null,
  validatedSource: null,
  validation: null,
  preview: null,
  confirming: false,
  busy: false,
};

const dom = {
  source: document.getElementById("config-source"),
  editorState: document.getElementById("editor-state"),
  validationPill: document.getElementById("validation-pill"),
  validationResult: document.getElementById("validation-result"),
  changeResult: document.getElementById("change-result"),
  saveResult: document.getElementById("save-result"),
  banner: document.getElementById("config-banner"),
  validate: document.getElementById("validate-config"),
  loadCurrent: document.getElementById("load-current"),
  insertExample: document.getElementById("insert-example"),
  importFile: document.getElementById("import-file"),
  probeProvider: document.getElementById("probe-provider"),
  probeModelId: document.getElementById("probe-model-id"),
  probeAck: document.getElementById("probe-ack"),
  probeAckRow: document.getElementById("probe-ack-row"),
  runProbe: document.getElementById("run-probe"),
  probeResult: document.getElementById("probe-result"),
  schemaResult: document.getElementById("schema-result"),
};

dom.source.addEventListener("input", invalidate);
dom.validate.addEventListener("click", validateAndPreview);
dom.loadCurrent.addEventListener("click", loadCurrent);
dom.insertExample.addEventListener("click", () => setSource(MODEL_EXAMPLE, "Unsaved model example"));
dom.importFile.addEventListener("change", importFile);
dom.probeProvider.addEventListener("change", updateProbeAcknowledgement);
dom.probeAck.addEventListener("change", updateProbeAcknowledgement);
dom.runProbe.addEventListener("click", runProbe);

start();

async function start() {
  try {
    const [schema, source] = await Promise.all([configApi.schema(), configApi.exportText()]);
    state.schema = schema;
    setSource(source, "Current overlay loaded");
    renderSchema();
    replace(dom.probeProvider, schema.providers.map((provider) =>
      el("option", { value: provider, text: provider })));
    updateProbeAcknowledgement();
  } catch (error) {
    showBanner("Could not load configuration", describe(error), "crit");
    dom.editorState.textContent = "Load failed";
  }
}

async function loadCurrent() {
  setBusy(true);
  try {
    setSource(await configApi.exportText(), "Current overlay reloaded");
    clearBanner();
  } catch (error) {
    showBanner("Could not reload the overlay", describe(error), "crit");
  } finally {
    setBusy(false);
  }
}

async function importFile(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;
  try {
    setSource(await file.text(), `Imported ${file.name}; nothing has been saved`);
    clearBanner();
  } catch (error) {
    showBanner("Could not read the selected file", describe(error), "crit");
  } finally {
    event.target.value = "";
  }
}

function setSource(source, label) {
  dom.source.value = source || "version: 1\n";
  invalidate();
  dom.editorState.textContent = label;
}

function invalidate() {
  state.document = null;
  state.validatedSource = null;
  state.validation = null;
  state.preview = null;
  state.confirming = false;
  dom.editorState.textContent = "Edited; validation required";
  setPill("mute", "not checked");
  replace(dom.validationResult,
    el("p.wz-hint", { text: "Run validation to check this exact revision." }));
  replace(dom.changeResult, el("p.wz-hint", { text: "No preview for this revision." }));
  renderSave();
}

async function validateAndPreview() {
  const source = dom.source.value;
  setBusy(true);
  clearBanner();
  try {
    const imported = await configApi.importYaml(source);
    const [validation, preview] = await Promise.all([
      configApi.validate(imported.document),
      configApi.preview(imported.document),
    ]);
    state.document = imported.document;
    state.validatedSource = source;
    state.validation = validation;
    state.preview = preview;
    state.confirming = false;
    renderValidation();
    renderChanges();
    renderSave();
    dom.editorState.textContent = validation.valid
      ? "This exact revision passed validation"
      : "This revision has blocking errors";
  } catch (error) {
    const report = error instanceof ConfigApiError ? error.body : null;
    state.validation = report && Array.isArray(report.issues) ? report : null;
    setPill("crit", "unreadable");
    renderValidation();
    replace(dom.changeResult, el("p.wz-hint", { text: "Preview is unavailable until the YAML parses." }));
    renderSave();
    showBanner("Configuration could not be read", describe(error), "crit");
  } finally {
    setBusy(false);
  }
}

function renderValidation() {
  const report = state.validation;
  if (!report) {
    replace(dom.validationResult, el("p.wz-hint", { text: "No validation report is available." }));
    return;
  }
  setPill(report.valid ? (report.integrityReduced ? "qual" : "ok") : "crit",
    report.valid ? (report.integrityReduced ? "valid, reduced" : "valid") : `${report.errorCount} errors`);

  const summary = el("div.cfg-summary", {}, [
    el("span", { text: `${report.errorCount} errors` }),
    el("span", { text: `${report.warningCount} warnings` }),
    report.integrityReduced ? el("span.cfg-qualified", { text: "integrity reduced" }) : null,
  ]);
  const issues = report.issues && report.issues.length
    ? el("div.cfg-issues", {}, report.issues.map(renderIssue))
    : el("p.cfg-clean", { text: "No issues found. The document is structurally and semantically valid." });
  replace(dom.validationResult, [summary, issues]);
}

function renderIssue(issue) {
  const path = [issue.entityKey, issue.field].filter(Boolean).join(".") || "document";
  return el(`div.cfg-issue.cfg-${String(issue.severity).toLowerCase()}`, {}, [
    el("div.cfg-issue-head", {}, [
      el("span", { text: issue.severity }),
      el("code", { text: path }),
    ]),
    el("p", { text: issue.message }),
    issue.remediation ? el("p.cfg-remediation", { text: issue.remediation }) : null,
  ]);
}

function renderChanges() {
  const preview = state.preview;
  if (!preview) return;
  const changes = preview.changes || [];
  const changeList = changes.length
    ? el("div.cfg-changes", {}, changes.map((change) =>
        el("div.cfg-change", {}, [
          el(`span.pill.p-${changeTier(change.change)}`, { text: change.change }),
          el("code", { text: `${change.type}:${change.id}` }),
          change.detail ? el("span", { text: change.detail }) : null,
        ])))
    : el("p.cfg-clean", { text: "No catalog entities would change." });
  const profiles = el("div.cfg-profile-list", {}, [
    el("strong", { text: "Selectable profiles after restart" }),
    ...(preview.profiles || []).map((profile) =>
      el("span", { text: profile.displayName ? `${profile.id} — ${profile.displayName}` : profile.id })),
  ]);
  replace(dom.changeResult, [changeList, profiles]);
}

function renderSave(saved = null) {
  if (saved) {
    replace(dom.saveResult, el("div.banner.b-ok", {}, [
      el("span.bt", { text: "Overlay saved" }),
      el("span.bd", { text: saved.backupPath
        ? `Previous file backed up to ${saved.backupPath}.`
        : `Written to ${saved.path}.` }),
      el("span.bd", { text: "Restart the application to load the new catalog." }),
    ]));
    return;
  }

  const eligible = Boolean(state.validation?.valid
    && state.document && state.validatedSource === dom.source.value);
  if (!state.confirming) {
    replace(dom.saveResult, [
      el("p.wz-hint", { text: eligible
        ? "Validation passed. Review the change list before continuing."
        : "Validate this revision successfully before saving." }),
      el("button.btn", {
        type: "button",
        disabled: eligible ? null : "disabled",
        onClick: () => { state.confirming = true; renderSave(); },
        text: "Review save",
      }),
    ]);
    return;
  }

  replace(dom.saveResult, el("div.banner.b-warn", {}, [
    el("span.bt", { text: "Replace the user overlay?" }),
    el("span.bd", { text: "The current council-user.yml is replaced atomically; an existing file is kept as a backup." }),
    el("span.bd", { text: "The running catalog does not change until restart." }),
    el("div.wz-actions", {}, [
      el("button.btn.btn-primary", { type: "button", onClick: confirmSave, text: "Write council-user.yml" }),
      el("button.btn", { type: "button", onClick: () => { state.confirming = false; renderSave(); }, text: "Cancel" }),
    ]),
  ]));
}

async function confirmSave() {
  if (!state.confirming || !state.validation?.valid
      || state.validatedSource !== dom.source.value || !state.document) {
    invalidate();
    showBanner("Save cancelled", "The editor changed after validation. Validate the current revision again.", "warn");
    return;
  }
  setBusy(true);
  try {
    const saved = await configApi.save(state.document);
    if (!saved.written) {
      state.validation = saved.validation;
      state.confirming = false;
      renderValidation();
      renderSave();
      showBanner("Save refused", "Validation no longer passes. Review the reported issues.", "crit");
      return;
    }
    state.confirming = false;
    renderSave(saved);
    showBanner("Restart required", "The file is safe on disk; restart to make this catalog active.", "ok");
  } catch (error) {
    showBanner("Could not save the overlay", describe(error), "crit");
  } finally {
    setBusy(false);
  }
}

async function runProbe() {
  const provider = dom.probeProvider.value;
  const providerModelId = dom.probeModelId.value.trim();
  if (!providerModelId) {
    replace(dom.probeResult, el("div.banner.b-warn", {}, [
      el("span.bt", { text: "Provider model id is required" }),
      el("span.bd", { text: "Enter the exact model name used by the provider." }),
    ]));
    return;
  }
  dom.runProbe.disabled = true;
  dom.runProbe.textContent = "Probing…";
  replace(dom.probeResult, el("p.wz-hint", { text: "One bounded model call is in progress…" }));
  try {
    const result = await configApi.probe(provider, providerModelId, dom.probeAck.checked);
    replace(dom.probeResult, el(`div.banner.b-${result.reachable ? "ok" : "crit"}`, {}, [
      el("span.bt", { text: result.reachable ? "Model responded" : "Probe failed" }),
      el("span.bd", { text: `${result.status}: ${result.detail}` }),
      el("span.bd.mono", { text: `provider=${result.provider} model=${result.providerModelId} latency=${result.latencyMs ?? "—"}ms` }),
    ]));
  } catch (error) {
    const retry = error instanceof ConfigApiError && error.retryAfterSeconds
      ? ` Retry after ${error.retryAfterSeconds} seconds.` : "";
    replace(dom.probeResult, el("div.banner.b-crit", {}, [
      el("span.bt", { text: "Probe refused or failed" }),
      el("span.bd", { text: describe(error) + retry }),
    ]));
  } finally {
    dom.runProbe.textContent = "Run live probe";
    updateProbeAcknowledgement();
  }
}

function updateProbeAcknowledgement() {
  const cloud = dom.probeProvider.value !== "ollama";
  dom.probeAckRow.hidden = !cloud;
  if (!cloud) dom.probeAck.checked = false;
  dom.runProbe.disabled = cloud && !dom.probeAck.checked;
}

function renderSchema() {
  const schema = state.schema;
  if (!schema) return;
  const entityCards = schema.entities.map((entity) =>
    el("div.cfg-rule-card", {}, [
      el("code", { text: entity.name }),
      el("p", { text: entity.description }),
      el("span", { text: `${entity.fields.length} fields` }),
    ]));
  const boundary = el("div.cfg-rule-card.cfg-boundary", {}, [
    el("strong", { text: "Deliberate boundaries" }),
    ...schema.locked.map((rule) => el("details", {}, [
      el("summary", { text: rule.name }),
      el("p", { text: rule.reason }),
    ])),
  ]);
  replace(dom.schemaResult, [...entityCards, boundary]);
}

function setBusy(busy) {
  state.busy = busy;
  dom.validate.disabled = busy;
  dom.loadCurrent.disabled = busy;
  dom.insertExample.disabled = busy;
  dom.validate.textContent = busy ? "Working…" : "Validate and preview";
}

function setPill(tier, text) {
  dom.validationPill.className = `pill p-${tier}`;
  replace(dom.validationPill, [el("span.led"), text]);
}

function showBanner(title, detail, tier) {
  replace(dom.banner, el(`div.banner.b-${tier}`, {}, [
    el("span.bt", { text: title }),
    el("span.bd", { text: detail }),
  ]));
}

function clearBanner() { replace(dom.banner, []); }

function changeTier(change) {
  if (change === "REMOVED") return "crit";
  return change === "OVERRIDDEN" ? "warn" : "accent";
}

function describe(error) {
  if (error instanceof ConfigApiError) return error.message;
  return error instanceof Error ? error.message : String(error);
}
