// setup.js — the requirement advisor wizard.
//
// Describe a council in plain language, review what was understood, see what
// this machine can run, look at the proposed configuration and what it would
// change, then either write it or save it for later.
//
// Two rules shape the code below and are worth stating before reading it.
//
// 1. The description is never sent to a non-local model without an explicit
//    acknowledgement. There is exactly one call to advisorApi.extract in this
//    file and it is guarded; the server enforces the same rule independently,
//    because a control implemented only in a web page is not a control.
// 2. Extraction failing must never cost the user their typing. The textarea is
//    the source of truth for the description and is never cleared — not on
//    failure, not on falling back to the form, not on going back a step.
//
// Rendering builds DOM nodes, never markup strings. A model's output and the
// user's own description both reach this page, and neither may be parsed as
// markup. SetupWizardContractTest asserts the token for that API appears
// nowhere in these modules, which is why it is not spelled out here either.

import { el, replace } from "./dom.js";
import { advisorApi, AdvisorError } from "./advisor-api.js";
import { renderRequirementForm } from "./requirement-form.js";

const STEPS = ["Describe", "Review", "Environment", "Proposal", "Confirm"];

const DEFAULT_REQUIREMENT = {
  privacy: "PREFER_LOCAL",
  latency: "MODERATE",
  cost: "LOW",
  rigor: "BALANCED",
  councilSize: 3,
  domains: ["GENERAL"],
  adversarialEmphasis: false,
};

const state = {
  step: 1,
  environment: null,
  // The description lives here for the life of the page. Nothing clears it.
  text: "",
  modelId: null,
  acknowledged: false,
  requirement: { ...DEFAULT_REQUIREMENT },
  notes: [],
  modelRationale: null,
  extractionMessage: null,
  synthesis: null,
  shadowDefault: false,
  confirming: false,
  saved: null,
  applied: null,
  busy: false,
  error: null,
};

const dom = {
  steps: document.getElementById("wizard-steps"),
  body: document.getElementById("wizard-body"),
  footer: document.getElementById("wizard-footer"),
  banner: document.getElementById("wizard-banner"),
};

// ── Boot ──────────────────────────────────────────────────────────────

async function start() {
  render();
  try {
    state.environment = await advisorApi.environment();
    state.modelId = state.environment.defaultExtractionModelId;
    if (!state.environment.extractionModels.length) {
      // No model can read a description. Say why and open on the form rather
      // than refusing to run: the form needs no model at all.
      state.step = 2;
      state.extractionMessage =
        "No model on this machine can read a description right now, so start from the form. "
        + "Everything below still works without one.";
    }
  } catch (error) {
    state.error = describe(error);
  }
  render();
}

// ── Steps ─────────────────────────────────────────────────────────────

function render() {
  replace(dom.steps, STEPS.map((label, index) =>
    el("button.wz-step", {
      type: "button",
      "aria-current": String(state.step === index + 1),
      disabled: index + 1 > furthestReachableStep() ? "disabled" : null,
      onClick: () => go(index + 1),
    }, [el("span.wz-num", { text: String(index + 1) }), label])));

  replace(dom.banner, state.error
    ? el("div.banner.b-crit", {}, [
        el("span.bt", { text: "Something went wrong" }),
        el("span.bd", { text: state.error }),
      ])
    : []);

  const steps = {
    1: renderDescribe,
    2: renderReview,
    3: renderEnvironment,
    4: renderProposal,
    5: renderConfirm,
  };
  replace(dom.body, steps[state.step]());
  replace(dom.footer, renderFooter());
}

function furthestReachableStep() {
  if (state.synthesis && state.synthesis.profileId) return 5;
  if (state.environment) return 4;
  return 2;
}

function go(step) {
  state.step = step;
  state.confirming = false;
  render();
}

// ── Step 1: describe ──────────────────────────────────────────────────

function renderDescribe() {
  const models = state.environment ? state.environment.extractionModels : [];
  const chosen = models.find((model) => model.id === state.modelId) || null;
  const needsAcknowledgement = Boolean(chosen && !chosen.local);

  const textarea = el("textarea.wz-text", {
    rows: "6",
    placeholder: "For example: a careful council for reviewing my own code. "
               + "Nothing should leave this laptop, and I would rather it argued with itself "
               + "than agreed too quickly.",
    onInput: (event) => { state.text = event.target.value; },
  });
  textarea.value = state.text;

  // A <select> built from the environment, not a text field. The id submitted
  // is always one the server offered, so no description can redirect
  // extraction at a model the user was never shown.
  const modelPicker = el("select.wz-select", {
    id: "extraction-model",
    onChange: (event) => {
      state.modelId = event.target.value;
      state.acknowledged = false;
      render();
    },
  }, models.map((model) =>
    el("option", {
      value: model.id,
      selected: model.id === state.modelId ? "selected" : null,
      text: `${model.id} — ${model.local ? "on this machine" : model.provider}`,
    })));

  return [
    el("h1.wz-title", { text: "Describe the council you want" }),
    el("p.wz-lede", {
      text: "In your own words. A model reads this into a small set of choices, which you "
          + "review on the next screen before anything is configured. You can skip straight "
          + "to those choices instead.",
    }),
    state.extractionMessage
      ? el("div.banner.b-warn", {}, [
          el("span.bt", { text: "Extraction did not work" }),
          el("span.bd", { text: state.extractionMessage }),
          el("span.bd", { text: "Your description is still here, unchanged." }),
        ])
      : null,
    textarea,
    models.length
      ? el("div.wz-model", {}, [
          el("label", { for: "extraction-model", text: "Read it with" }),
          modelPicker,
        ])
      : el("p.wz-hint", {
          text: "No model is available to read a description. Use the form instead.",
        }),
    needsAcknowledgement ? renderCloudAcknowledgement(chosen) : null,
    el("div.wz-actions", {}, [
      models.length
        ? el("button.btn.btn-primary", {
            type: "button",
            disabled: (state.busy || !state.text.trim()
                       || (needsAcknowledgement && !state.acknowledged)) ? "disabled" : null,
            onClick: extractAfterAcknowledgement,
            text: state.busy ? "Reading…" : "Read my description",
          })
        : null,
      el("button.btn", {
        type: "button",
        onClick: () => go(2),
        text: "Skip to the choices",
      }),
    ]),
  ];
}

function renderCloudAcknowledgement(model) {
  return el("div.banner.b-warn", {}, [
    el("span.bt", { text: `This would send your description to ${model.provider}` }),
    el("span.bd", {
      text: `'${model.id}' does not run on this machine. Reading your description with it means `
          + `the text you typed above leaves this computer and goes to ${model.provider}.`,
    }),
    el("label.wz-check", {}, [
      el("input", {
        type: "checkbox",
        checked: state.acknowledged ? "checked" : null,
        onChange: (event) => { state.acknowledged = event.target.checked; render(); },
      }),
      `Send my description to ${model.provider}`,
    ]),
    el("span.bd", {
      text: "Or choose a model that runs on this machine — nothing you type reaches a "
          + "third party that way.",
    }),
  ]);
}

/**
 * The only place this page reads a description with a model.
 *
 * Guarded here and again on the server. The check below is what makes the
 * checkbox mean something in this page; the server's is what makes it mean
 * something at all, since this endpoint is reachable without this page.
 */
async function extractAfterAcknowledgement() {
  const models = state.environment ? state.environment.extractionModels : [];
  const chosen = models.find((model) => model.id === state.modelId);
  const acknowledged = Boolean(chosen && chosen.local) || state.acknowledged;
  if (!chosen || !acknowledged) {
    state.extractionMessage = "Choose a model, and confirm the send if it is not a local one.";
    render();
    return;
  }

  state.busy = true;
  state.error = null;
  render();
  try {
    const outcome = await advisorApi.extract(state.text, chosen.id, acknowledged);
    state.requirement = outcome.requirement;
    state.notes = outcome.notes || [];
    state.modelRationale = outcome.modelRationale;
    // On failure the requirement is the defaults and the form takes over. The
    // description is untouched either way: it lives in state.text and nothing
    // above writes to it.
    state.extractionMessage = outcome.fallbackToForm ? outcome.failureReason : null;
    state.step = 2;
  } catch (error) {
    state.extractionMessage = describe(error);
  } finally {
    state.busy = false;
    render();
  }
}

// ── Step 2: review ────────────────────────────────────────────────────

function renderReview() {
  const form = el("div.wz-form");
  renderRequirementForm(form, state.requirement, state.notes, (updated) => {
    state.requirement = updated;
    // Anything synthesised from the previous answer no longer describes this one.
    state.synthesis = null;
  });

  return [
    el("h1.wz-title", { text: "Check what was understood" }),
    el("p.wz-lede", {
      text: "Nothing has been configured yet. These choices are what the council is built "
          + "from, so correct anything that is wrong before going on.",
    }),
    state.extractionMessage
      ? el("div.banner.b-warn", {}, [
          el("span.bt", { text: "These are the defaults, not a reading of your description" }),
          el("span.bd", { text: state.extractionMessage }),
        ])
      : null,
    state.modelRationale
      ? el("div.banner.b-info", {}, [
          el("span.bt", { text: "The model's own summary of what it read" }),
          el("span.bd", { text: state.modelRationale }),
        ])
      : null,
    form,
  ];
}

// ── Step 3: environment ───────────────────────────────────────────────

function renderEnvironment() {
  const environment = state.environment;
  if (!environment) return el("p.wz-hint", { text: "Checking what this machine can run…" });

  return [
    el("h1.wz-title", { text: "What this machine can run" }),
    el("p.wz-lede", {
      text: "Only models that are installed and whose provider is configured can be seated. "
          + "Nothing else is ever proposed, which is the main reason this produces a council "
          + "that actually runs.",
    }),
    ...(environment.remediation || []).map((step) =>
      el("div.banner.b-warn", {}, [
        el("span.bt", { text: "Worth fixing first" }),
        el("span.bd.mono", { text: step }),
      ])),
    el("h2.wz-h2", { text: "Installed locally" }),
    environment.installedLocalModels.length
      ? el("ul.wz-list", {}, environment.installedLocalModels.map((tag) =>
          el("li", {}, [el("code", { text: tag })])))
      : el("p.wz-hint", { text: "Nothing. Start Ollama and pull a model." }),
    el("h2.wz-h2", { text: "Providers" }),
    el("div.cards", {}, environment.providers.map((provider) =>
      el("div.card", {}, [
        el("div.ch", {}, [
          el("span.cid", { text: provider.provider }),
          el(`span.pill.p-${providerTier(provider)}`, {}, [
            el("span.led"), provider.availability.toLowerCase(),
          ]),
        ]),
        el("div.ct", { text: providerNote(provider) }),
      ]))),
  ];
}

function providerTier(provider) {
  if (provider.availability === "MOCK") return "qual";
  return provider.availability === "LIVE" ? "ok" : "mute";
}

/**
 * What a provider's state actually means for somebody building a council.
 *
 * Three cases, not two. A mock provider is not an unconfigured one: it needs
 * nothing set and is always callable, and what it is not is real — telling
 * somebody it is "not configured" sends them looking for the credential that
 * would fix it, and finding one would be the actual failure.
 *
 * The local provider is a fourth: its client is built unconditionally, so LIVE
 * here means a client exists rather than that the runtime is up. The installed
 * list above is the signal that answers that, and this says so rather than
 * letting "LIVE" next to "nothing installed" read as a contradiction.
 */
function providerNote(provider) {
  if (provider.availability === "MOCK") {
    return "Fabricated output, for exercising the pipeline without a model runtime. Nothing to "
         + "configure, and the advisor never seats a mock model on a real council.";
  }
  if (provider.local) {
    return "Runs on this machine, so nothing sent to it leaves this computer. Whether the "
         + "runtime is actually up is answered by the installed list above, not by this — the "
         + "local client is built whether or not anything is listening.";
  }
  return provider.availability === "LIVE"
    ? "Configured and callable. Anything sent to it leaves this machine."
    : "Not configured. Providers are activated by environment variables; this application never "
      + "reads credentials from configuration.";
}

// ── Step 4: proposal ──────────────────────────────────────────────────

async function synthesise() {
  state.busy = true;
  state.error = null;
  render();
  try {
    state.synthesis = await advisorApi.synthesize(state.requirement, state.shadowDefault);
  } catch (error) {
    state.error = describe(error);
  } finally {
    state.busy = false;
    render();
  }
}

function renderProposal() {
  if (!state.synthesis) {
    return [
      el("h1.wz-title", { text: "The council this produces" }),
      el("p.wz-lede", {
        text: "Built from your choices and from what this machine can run. Nothing is written "
            + "until you say so on the next screen.",
      }),
      el("button.btn.btn-primary", {
        type: "button",
        disabled: state.busy ? "disabled" : null,
        onClick: synthesise,
        text: state.busy ? "Working…" : "Build the council",
      }),
    ];
  }

  const synthesis = state.synthesis;
  const errors = (synthesis.issues || []).filter((issue) => issue.severity === "ERROR");
  const warnings = (synthesis.issues || []).filter((issue) => issue.severity === "WARNING");

  return [
    el("h1.wz-title", { text: "The council this produces" }),
    ...errors.map((issue) => el("div.banner.b-crit", {}, [
      el("span.bt", { text: "No council could be built" }),
      el("span.bd", { text: issue.message }),
      issue.remediation ? el("span.bd.mono", { text: issue.remediation }) : null,
    ])),
    ...warnings.map((issue) => el("div.banner.b-warn", {}, [
      el("span.bt", { text: "Worth knowing" }),
      el("span.bd", { text: issue.message }),
      issue.remediation ? el("span.bd", { text: issue.remediation }) : null,
    ])),
    synthesis.profileId ? renderCouncil(synthesis) : null,
    synthesis.profileId ? el("h2.wz-h2", { text: "Why" }) : null,
    synthesis.profileId
      ? el("ul.wz-list", {}, (synthesis.rationale || []).map((line) => el("li", { text: line })))
      : null,
    synthesis.profileId ? renderChanges(synthesis.preview) : null,
    synthesis.profileId
      ? el("label.wz-check", {}, [
          el("input", {
            type: "checkbox",
            checked: state.shadowDefault ? "checked" : null,
            onChange: (event) => {
              state.shadowDefault = event.target.checked;
              state.synthesis = null;
              render();
            },
          }),
          "Also make this the profile used when a request does not name one",
        ])
      : null,
  ];
}

function renderCouncil(synthesis) {
  const policies = synthesis.document.policies || {};
  return el("div.cards", {}, Object.entries(policies)
    .filter(([id]) => id.startsWith("advisor-"))
    .map(([id, policy]) => el("div.card", {}, [
      el("div.ch", {}, [
        el("span.cid", { text: id.replace("advisor-", "") }),
        el("span.pill.p-accent", {}, [el("span.led"), policy.protocolId]),
      ]),
      el("div.ct", { text: `Members: ${policy.memberModelIds.join(", ")}` }),
      el("div.ct", { text: `Chair: ${policy.chairModelId}` }),
      el("div.ct", {
        text: policy.validatorModelId
          ? `Validator: ${policy.validatorModelId}`
          : "No validator — this depth has no validation stage.",
      }),
      el("div.ct", {
        text: `Quorum: ${policy.minimumSuccessfulDrafts} draft(s), `
            + `${policy.minimumReviewsPerDraft} review(s) per draft.`,
      }),
    ])));
}

function renderChanges(preview) {
  const changes = (preview && preview.changes) || [];
  if (!changes.length) {
    return el("p.wz-hint", { text: "This adds nothing that is not already configured." });
  }
  return [
    el("h2.wz-h2", { text: "What changes" }),
    el("ul.wz-list", {}, changes.map((change) =>
      el("li", {}, [
        el("code", { text: `${change.type}:${change.id}` }),
        ` ${change.change.toLowerCase()} — ${change.detail}`,
      ]))),
  ];
}

// ── Step 5: confirm ───────────────────────────────────────────────────

function renderConfirm() {
  if (state.applied) {
    return [
      el("h1.wz-title", { text: "Written" }),
      el("div.banner.b-ok", {}, [
        el("span.bt", { text: `Saved to ${state.applied.path}` }),
        el("span.bd", {
          text: "It takes effect at the next restart — the running configuration is pinned at "
              + "startup so that a council in progress cannot change under it.",
        }),
        state.applied.backupPath
          ? el("span.bd.mono", { text: `Previous version kept at ${state.applied.backupPath}` })
          : null,
      ]),
      el("a.btn", { href: "/", text: "Back to the council" }),
    ];
  }

  if (state.saved) {
    return [
      el("h1.wz-title", { text: "Saved for later" }),
      el("div.banner.b-ok", {}, [
        el("span.bt", { text: "Nothing has been applied" }),
        el("span.bd", {
          text: `The council is at ${state.saved.location}. It is not configuration and is `
              + "never read at startup. You will see a reminder on the main screen until you "
              + "apply or discard it.",
        }),
        el("span.bd", {
          text: "It is checked again when you come back, in case the models it names are no "
              + "longer installed.",
        }),
      ]),
      el("a.btn", { href: "/", text: "Back to the council" }),
    ];
  }

  const removed = removalsOf(state.synthesis);

  if (state.confirming) {
    return [
      el("h1.wz-title", { text: "Write this configuration?" }),
      removed.length
        ? el("div.banner.b-crit", {}, [
            el("span.bt", { text: "This would remove configuration you have now" }),
            ...removed.map((change) =>
              el("span.bd.mono", { text: `${change.type}:${change.id} — ${change.detail}` })),
          ])
        : el("div.banner.b-ok", {}, [
            el("span.bt", { text: "Nothing you already have is removed" }),
            el("span.bd", {
              text: "Everything currently in your configuration is carried through unchanged. "
                  + "The advisor only adds, and replaces its own previous output.",
            }),
          ]),
      el("p.wz-lede", {
        text: "Your configuration file is replaced. The previous version is kept alongside it.",
      }),
      el("div.wz-actions", {}, [
        el("button.btn.btn-primary", {
          type: "button",
          disabled: state.busy ? "disabled" : null,
          onClick: applyAfterShowingWhatIsLost,
          text: state.busy ? "Writing…" : "Yes, write it",
        }),
        el("button.btn", {
          type: "button",
          onClick: () => { state.confirming = false; render(); },
          text: "Go back",
        }),
      ]),
    ];
  }

  return [
    el("h1.wz-title", { text: "Apply now, or keep it for later" }),
    el("p.wz-lede", {
      text: "Applying writes your configuration file; it takes effect at the next restart. "
          + "Saving for later writes a separate file that is never read at startup, so you can "
          + "come back to it.",
    }),
    el("div.wz-actions", {}, [
      el("button.btn.btn-primary", {
        type: "button",
        onClick: () => { state.confirming = true; render(); },
        text: "Add to my configuration",
      }),
      el("button.btn", {
        type: "button",
        disabled: state.busy ? "disabled" : null,
        onClick: saveForLater,
        text: "Save for later",
      }),
    ]),
  ];
}

function removalsOf(synthesis) {
  const changes = (synthesis && synthesis.preview && synthesis.preview.changes) || [];
  return changes.filter((change) => change.change === "REMOVED");
}

/**
 * The only place this page writes configuration.
 *
 * Reachable only from the confirming panel above, which renders the removal
 * list first. The configuration write path replaces the whole overlay file, so
 * a user has to be shown what they would lose before it happens — the kept
 * previous version is a backstop, not a substitute for telling them.
 */
async function applyAfterShowingWhatIsLost() {
  const removed = removalsOf(state.synthesis);
  if (!state.confirming) {
    // Unreachable from the UI; here so that the guarantee is in the code
    // rather than in the arrangement of the buttons.
    state.error = `Not confirmed. ${removed.length} entities would be removed.`;
    render();
    return;
  }

  state.busy = true;
  state.error = null;
  render();
  try {
    const result = await advisorApi.applyConfiguration(state.synthesis.document);
    if (result.written) {
      state.applied = result;
    } else {
      state.error = "The configuration was refused and nothing was written: "
                  + (result.validation.issues || []).map((issue) => issue.message).join(" ");
    }
  } catch (error) {
    state.error = describe(error);
  } finally {
    state.busy = false;
    state.confirming = false;
    render();
  }
}

async function saveForLater() {
  state.busy = true;
  state.error = null;
  render();
  try {
    state.saved = await advisorApi.saveProposal(state.requirement, state.shadowDefault);
  } catch (error) {
    state.error = describe(error);
  } finally {
    state.busy = false;
    render();
  }
}

// ── Footer ────────────────────────────────────────────────────────────

function renderFooter() {
  if (state.applied || state.saved) return [];
  const back = state.step > 1
    ? el("button.btn", { type: "button", onClick: () => go(state.step - 1), text: "Back" })
    : null;
  const forward = state.step < 5
    ? el("button.btn.btn-primary", {
        type: "button",
        disabled: state.step + 1 > furthestReachableStep() ? "disabled" : null,
        onClick: () => go(state.step + 1),
        text: "Next",
      })
    : null;
  return [back, el("span.spacer"), forward];
}

function describe(error) {
  if (error instanceof AdvisorError) {
    return error.remediation ? `${error.message} ${error.remediation}` : error.message;
  }
  return String(error);
}

start();
