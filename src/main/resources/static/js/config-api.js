// config-api.js — strict calls used by the advanced configuration workbench.

const BASE = "/api/council/config";

export class ConfigApiError extends Error {
  constructor(status, message, body = null, retryAfterSeconds = null) {
    super(message);
    this.name = "ConfigApiError";
    this.status = status;
    this.body = body;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

async function errorFrom(response) {
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  const issue = body && body.issues && body.issues[0];
  const message = issue?.message || body?.message || text.trim()
    || `${response.status} ${response.statusText}`;
  return new ConfigApiError(response.status, message, body,
    response.headers.get("Retry-After"));
}

async function request(path, options = {}) {
  let response;
  try {
    response = await fetch(BASE + path, options);
  } catch (cause) {
    throw new ConfigApiError(0, "Cannot reach the council service on this host.", String(cause));
  }
  if (!response.ok) throw await errorFrom(response);
  return response;
}

async function json(method, path, body) {
  const response = await request(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return response.json();
}

export const configApi = {
  schema: async () => (await request("/schema")).json(),
  exportText: async () => (await request("/export")).text(),
  importYaml: async (source) => (await request("/import", {
    method: "POST",
    headers: { "Content-Type": "application/yaml" },
    body: source,
  })).json(),
  validate: (document) => json("POST", "/validate", document),
  preview: (document) => json("POST", "/preview", document),
  save: (document) => json("PUT", "/draft", document),
  probe: (provider, providerModelId, acknowledgeCloudCall) =>
    json("POST", "/models/probe", { provider, providerModelId, acknowledgeCloudCall }),
};
