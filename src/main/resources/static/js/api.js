// api.js — the single place that talks to /api/council/** and maps failures.
//
// Every council failure the server reports has a category (PROVIDER_UNAVAILABLE,
// QUORUM_NOT_MET, ...) and the UI's job is to say what that means rather than
// print a status code. Error shapes are mapped here once so no caller has to
// guess whether it received a plain-text handler message or Spring's JSON error
// body.

const BASE = "/api/council";

/** A failed API call, carrying whatever the server was able to say about it. */
export class ApiError extends Error {
  constructor(status, message, detail) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail || null;
  }
}

// The exception handlers return plain text; validation failures return Spring's
// JSON error body. Accept both rather than assuming either.
async function readError(response) {
  const body = await response.text();
  if (!body) {
    return new ApiError(response.status, `${response.status} ${response.statusText}`);
  }
  try {
    const parsed = JSON.parse(body);
    const message = parsed.message || parsed.error || body;
    return new ApiError(response.status, message, parsed);
  } catch {
    return new ApiError(response.status, body.trim());
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
    // fetch only rejects when the request never completed — the service is down
    // or the page is offline. Say that, rather than reporting it as a 0 status.
    throw new ApiError(0, "Cannot reach the council service on this host.", String(cause));
  }

  if (response.status === 204) return null;
  if (!response.ok) throw await readError(response);

  const type = response.headers.get("content-type") || "";
  return type.includes("json") ? response.json() : response.text();
}

export const api = {
  // ── Catalog and preflight
  catalog: (include) => request("GET", `/catalog?include=${encodeURIComponent(include)}`),
  profileHealth: (profileId, depthMode) =>
    request("GET", `/profiles/${encodeURIComponent(profileId)}/health?depthMode=${depthMode}`),

  // ── Chats
  listChats: () => request("GET", "/chats"),
  createChat: (profileId, depthMode) => request("POST", "/chats", { profileId, depthMode }),
  getChat: (chatId) => request("GET", `/chats/${chatId}`),
  deleteChat: (chatId) => request("DELETE", `/chats/${chatId}`),
  sendMessage: (chatId, message) => request("POST", `/chats/${chatId}/messages`, { message }),

  // ── Runs
  // The result carries the trust signals; it is absent until the run finishes,
  // so a 404 here means "still going", not "broken".
  runResult: (sessionId) => request("GET", `/sessions/${sessionId}/result`),
  listArtifacts: (sessionId) => request("GET", `/sessions/${sessionId}/artifacts`),
  readArtifact: (sessionId, path) => request("GET", `/sessions/${sessionId}/artifacts/${path}`),
};
