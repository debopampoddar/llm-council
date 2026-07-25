// proposal.js — the reminder that an unapplied council is waiting.
//
// "Save for later" is only honest if later actually arrives. Without a visible
// reminder wherever configuration is shown, come back later means forget
// forever, and the saved work is a file nobody opens again.
//
// Also the first-run pointer: an installation with no configuration and no
// proposal has no way of knowing the wizard exists. That is rendered here too,
// because both are the same question — is there something you should look at
// before running a council?

import { el, replace } from "./dom.js";

/**
 * Render the configuration notice, if there is one.
 *
 * @param container element to fill; emptied when there is nothing to say
 * @param proposal  the response from GET /advisor/proposal, or null
 * @param hasUserConfiguration whether an overlay already exists
 */
export function renderProposalNotice(container, proposal, hasUserConfiguration) {
  if (proposal && proposal.present) {
    replace(container, unappliedProposal(proposal));
    return;
  }
  if (!hasUserConfiguration) {
    replace(container, firstRun());
    return;
  }
  replace(container, []);
}

function unappliedProposal(proposal) {
  const saved = formatDate(proposal.savedAt);
  const broken = proposal.validation && !proposal.validation.valid;

  return el(`div.banner.${broken ? "b-crit" : "b-info"}`, {}, [
    el("span.bt", {
      text: saved
        ? `You have an unapplied council from ${saved}`
        : "You have an unapplied council",
    }),
    el("span.bd", {
      text: "Saved from the setup wizard and never applied. Nothing about it is in effect.",
    }),
    // Broken and stale are different claims and are shown as different things.
    // A proposal whose models no longer resolve cannot be applied at all; one
    // that merely no longer reflects the machine can, and might still be what
    // the user wants.
    broken
      ? el("span.bd", {
          text: "It no longer works against your current configuration: "
              + proposal.validation.issues
                        .filter((issue) => issue.severity === "ERROR")
                        .map((issue) => issue.message)
                        .join(" "),
        })
      : null,
    !broken && proposal.resynthesisDiffers
      ? el("span.bd", { text: proposal.resynthesisNote })
      : null,
    el("a.btn.btn-sm", { href: "/setup.html", text: "Review it" }),
  ]);
}

function firstRun() {
  return el("div.banner.b-info", {}, [
    el("span.bt", { text: "Running on the shipped configuration" }),
    el("span.bd", {
      text: "Describe the council you want in plain language and the setup wizard will build "
          + "one from the models this machine can actually run.",
    }),
    el("a.btn.btn-sm", { href: "/setup.html", text: "Set up a council" }),
  ]);
}

function formatDate(iso) {
  if (!iso) return null;
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) return null;
  return new Date(parsed).toLocaleDateString(undefined,
                                             { year: "numeric", month: "short", day: "numeric" });
}
