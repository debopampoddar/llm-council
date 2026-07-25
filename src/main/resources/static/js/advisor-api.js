// advisor-api.js — the setup wizard's calls to /api/council/advisor/**.
//
// Kept apart from api.js because the two have different failure shapes. A
// council run fails with a category (QUORUM_NOT_MET, PROVIDER_UNAVAILABLE);
// the advisor refuses with a message and a remediation, and the remediation is
// the point — every refusal here is something the user can act on.
//
// Note what is absent: there is no call that writes configuration. Applying
// goes through the configuration write path that already exists, so there is
// one place that touches the overlay file.

const BASE = "/api/council";

/** A refused advisor request, carrying what to do instead. */
export class AdvisorError extends Error {
  constructor(status, message, remediation) {
    super(message);
    this.name = "AdvisorError";
    this.status = status;
    this.remediation = remediation || null;
  }
}

async function readError(response) {
  const body = await response.text();
  if (!body) return new AdvisorError(response.status, `${response.status} ${response.statusText}`);
  try {
    const parsed = JSON.parse(body);
    // Two shapes: the advisor's own {message, remediation}, and the
    // configuration validator's {issues:[...]} when a document is refused.
    if (parsed.issues && parsed.issues.length) {
      const first = parsed.issues[0];
      return new AdvisorError(response.status, first.message, first.remediation);
    }
    return new AdvisorError(response.status, parsed.message || body, parsed.remediation);
  } catch {
    return new AdvisorError(response.status, body.trim());
  }
}

async function request(method, path, body) {
  const options = { method, headers: {} };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(BASE + path, options);
  } catch (cause) {
    throw new AdvisorError(0, "Cannot reach the council service on this host.", String(cause));
  }

  if (response.status === 204) return null;
  if (!response.ok) throw await readError(response);
  return response.json();
}

export const advisorApi = {
  /** What this machine can run. Probes the local runtime on every call. */
  environment: () => request("GET", "/advisor/environment"),

  /**
   * Ask a model to read a description.
   *
   * `modelId` must be an id from environment().extractionModels — the server
   * checks it against that same list, so this is a convenience rather than the
   * control. `acknowledgeCloudProvider` is required before any non-local model
   * is used and is likewise enforced server-side.
   */
  extract: (text, modelId, acknowledgeCloudProvider) =>
    request("POST", "/advisor/extract", { text, modelId, acknowledgeCloudProvider }),

  /** Turn a requirement into configuration. Writes nothing. */
  synthesize: (requirement, shadowDefault) =>
    request("POST", "/advisor/synthesize", { requirement, shadowDefault }),

  /** The council somebody saved without applying it, re-checked on read. */
  proposal: () => request("GET", "/advisor/proposal"),

  /** Save a council for later. Takes intent, never a document. */
  saveProposal: (requirement, shadowDefault) =>
    request("PUT", "/advisor/proposal", { requirement, shadowDefault }),

  /** Throw away the saved council. */
  discardProposal: () => request("DELETE", "/advisor/proposal"),

  /**
   * Write the configuration.
   *
   * The existing configuration write path, not an advisor-specific one. It
   * replaces the whole overlay file, which is why the wizard shows the removal
   * list before calling this — and why the advisor's own output carries every
   * entity the user already had.
   */
  applyConfiguration: (document) => request("PUT", "/config/draft", document),
};
