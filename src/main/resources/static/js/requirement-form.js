// requirement-form.js — the extracted requirement, as controls a user can fix.
//
// Step 2 of the wizard exists because extraction is a guess. Whatever a model
// understood is shown here as editable choices before anything is synthesised,
// so the user approves an intent rather than discovering it in the result.
//
// Every control is a closed choice, mirroring CouncilRequirement. There is
// deliberately nowhere to type a model id, a provider, or a stage: the record
// this produces cannot carry one, and a form offering a field the record drops
// would be promising something the server refuses.

import { el, replace } from "./dom.js";

const CHOICES = {
  privacy: [
    ["LOCAL_ONLY", "Local only", "Nothing leaves this machine."],
    ["PREFER_LOCAL", "Prefer local", "Cloud models only to fill a seat or add a family."],
    ["CLOUD_OK", "Cloud is fine", "Any configured provider may be used."],
  ],
  latency: [
    ["FAST", "Fast", "Caps the council size and shortens debate."],
    ["MODERATE", "Moderate", "No particular urgency."],
    ["PATIENT", "Patient", "Whatever it takes to be careful."],
  ],
  cost: [
    ["FREE_ONLY", "Free only", "Local models only. An unpriced cloud model is not a free one."],
    ["LOW", "Low", "Some spend is fine; local still preferred."],
    ["UNCONSTRAINED", "Unconstrained", "Cost is not a consideration."],
  ],
  rigor: [
    ["QUICK", "Quick", "Draft and synthesise. No review, no debate."],
    ["BALANCED", "Balanced", "Anonymised review, scoring, and validation."],
    ["RIGOROUS", "Rigorous", "Adds debate, revision, and a second scoring pass."],
  ],
};

const DOMAINS = ["CODE", "WRITING", "ANALYSIS", "RESEARCH", "GENERAL"];

/**
 * Render the requirement form.
 *
 * @param container element to fill
 * @param requirement the current requirement
 * @param notes per-field notes from extraction, shown next to what they concern
 * @param onChange called with the updated requirement on every edit
 */
export function renderRequirementForm(container, requirement, notes, onChange) {
  const current = { ...requirement };
  const update = (key, value) => {
    current[key] = value;
    onChange({ ...current });
    render();
  };

  function render() {
    const rows = [];

    for (const [key, options] of Object.entries(CHOICES)) {
      rows.push(el("div.rq-row", {}, [
        el("div.rq-label", { text: label(key) }),
        el("div.seg", {}, options.map(([value, text]) =>
          el("button", {
            type: "button",
            "aria-pressed": String(current[key] === value),
            onClick: () => update(key, value),
            text,
          }))),
        el("div.rq-hint", { text: hintFor(options, current[key]) }),
      ]));
    }

    rows.push(el("div.rq-row", {}, [
      el("div.rq-label", { text: "Council size" }),
      el("div.seg", {}, [1, 2, 3, 4, 5, 6, 7, 8].map((size) =>
        el("button", {
          type: "button",
          "aria-pressed": String(current.councilSize === size),
          onClick: () => update("councilSize", size),
          text: String(size),
        }))),
      el("div.rq-hint", {
        text: "How many models draft an answer. More members cost more and take "
            + "longer; fewer than two means peer review has nobody to disagree with.",
      }),
    ]));

    rows.push(el("div.rq-row", {}, [
      el("div.rq-label", { text: "Subject" }),
      el("div.rq-chips", {}, DOMAINS.map((domain) =>
        el("button.chip", {
          type: "button",
          "aria-pressed": String((current.domains || []).includes(domain)),
          onClick: () => update("domains", toggle(current.domains || [], domain)),
          text: domain.toLowerCase(),
        }))),
      // Said plainly rather than implied. A control that silently changes
      // nothing is worse than one that admits it: the user would assume the
      // council was tuned for their subject and it was not.
      el("div.rq-hint", {
        text: "Recorded and shown in the profile name. It does not change which "
            + "models are chosen — this application holds no per-model capability "
            + "data, so picking models by subject would be a guess dressed as a reason.",
      }),
    ]));

    rows.push(el("div.rq-row", {}, [
      el("div.rq-label", { text: "Adversarial" }),
      el("div.seg", {}, [[true, "Emphasised"], [false, "Balanced"]].map(([value, text]) =>
        el("button", {
          type: "button",
          "aria-pressed": String(Boolean(current.adversarialEmphasis) === value),
          onClick: () => update("adversarialEmphasis", value),
          text,
        }))),
      el("div.rq-hint", {
        text: "Weights the council towards critics. Applies to models this "
            + "configuration defines; a built-in model keeps the debate persona it ships with.",
      }),
    ]));

    if (notes && notes.length) {
      rows.unshift(el("div.banner.b-warn", {}, [
        el("span.bt", { text: "Some of what the model said could not be read" }),
        ...notes.map((note) => el("span.bd", { text: note })),
      ]));
    }

    replace(container, rows);
  }

  render();
}

function toggle(values, value) {
  return values.includes(value) ? values.filter((v) => v !== value) : [...values, value];
}

function hintFor(options, value) {
  const found = options.find(([key]) => key === value);
  return found ? found[2] : "";
}

function label(key) {
  return { privacy: "Privacy", latency: "Speed", cost: "Cost", rigor: "Rigour" }[key] || key;
}
