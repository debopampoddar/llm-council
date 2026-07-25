// providers.js — which providers this installation can actually call.
//
// Read-only, and deliberately so. There is no field here to type a key into,
// because the application never reads a credential from configuration: keys live
// in the environment, and the overlay file is refused outright if one appears in
// it. A panel that offered an input would be promising something the rest of the
// system exists to refuse.
//
// Availability is not probed here either. It comes from the catalog, which
// infers it from which client was built for each model at startup, so this view
// is structurally incapable of carrying a key even by accident.

import { el, replace, pill } from "./dom.js";

/**
 * What to do about a provider that is not configured.
 *
 * The one sentence a user needs, and the only place it is written. Providers
 * with no environment variable — Ollama, which needs a running daemon rather
 * than a key — get the reason the catalog reported instead, because telling
 * someone to set a variable that does not exist is worse than saying nothing.
 *
 * @param provider a ProviderStatus from GET /catalog?include=providers
 * @returns the instruction to show, or null when there is nothing to instruct
 */
export function inactiveInstruction(provider) {
  if (provider.provider === "mock") return MOCK_NOTE;
  if (!provider.requiredEnvVar) return provider.reason || null;
  return `Set ${provider.requiredEnvVar} in your environment or .env file and restart. `
       + `This application never stores API keys.`;
}

// The mock provider is not an unconfigured one. It needs nothing set and is
// always callable; what it is not is real. Reporting it as "not configured"
// would invite a user to go looking for the credential that would fix it, and
// finding one would be the actual failure.
const MOCK_NOTE = "Fabricated output, for testing the pipeline without a model runtime. "
                + "Nothing to configure, and no real profile ever falls back to it.";

/**
 * Render the provider table.
 *
 * @param container element to fill
 * @param providers ProviderStatus entries from the catalog
 */
export function renderProviders(container, providers) {
  if (!providers) {
    replace(container, el("p.tl-empty", { text: "Loading providers…" }));
    return;
  }
  if (!providers.length) {
    replace(container, el("p.tl-empty", { text: "No providers are referenced by any profile." }));
    return;
  }

  replace(container, [
    el("p.run-note", {
      text: "Providers are activated by environment variables, never by this application. "
          + "A provider that is not active is not hidden — its models stay in the catalog and "
          + "fail with an actionable message rather than quietly producing fabricated output.",
    }),
    el("div.prov-table", {}, providers.map(renderProvider)),
  ]);
}

function renderProvider(provider) {
  const mock = provider.provider === "mock";
  const instruction = provider.active ? null : inactiveInstruction(provider);

  return el("div.prov-row", {}, [
    el("div.prov-head", {}, [
      el("span.prov-name", { text: provider.provider }),
      mock
        ? pill("qual", "test only")
        : pill(provider.active ? "ok" : "mute", provider.active ? "active" : "not configured"),
    ]),
    provider.requiredEnvVar ? el("code.prov-var", { text: provider.requiredEnvVar }) : null,
    instruction ? el("p.prov-note", { text: instruction }) : null,
    renderInstalled(provider),
  ]);
}

/**
 * Locally installed Ollama models, when the catalog reported any.
 *
 * Worth showing because the commonest local failure is a model configured but
 * never pulled, which looks identical to a broken provider from the outside.
 */
function renderInstalled(provider) {
  const installed = provider.discoveredModels || [];
  if (!installed.length) return null;
  return el("p.prov-note", { text: `Installed locally: ${installed.join(", ")}` });
}
