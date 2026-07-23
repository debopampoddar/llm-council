// main.js — app entry point.
//
// Step 1 scaffolding: creates a chat on the mock profile, subscribes to its
// event stream, and prints the raw council events. The point at this stage is
// to prove the plumbing — static serving with no Java, the SSE lifecycle, and
// dedupe across a reconnect. The chat list, health gate, timeline and trust
// strip land on top of this in the steps that follow.

import { api, ApiError } from "./api.js";
import { subscribe } from "./sse.js";
import { el, replace } from "./dom.js";

const streamInner = document.getElementById("stream-inner");
const connBadge = document.getElementById("conn");
const connLabel = document.getElementById("conn-label");

let stream = null;
let events = [];

function setConnection(state, detail) {
  connBadge.dataset.state = state === "retrying" ? "retrying" : state;
  const labels = {
    open: "live",
    retrying: `reconnecting in ${Math.round((detail || 0) / 1000)}s`,
    closed: "not connected",
  };
  connLabel.textContent = labels[state] || state;
}

function renderEvents() {
  if (!events.length) {
    replace(streamInner, el("div.empty", {}, [
      el("h2", { text: "Waiting for the council" }),
      el("p", { text: "Events appear here as stages run." }),
    ]));
    return;
  }
  const rows = events.map((event) =>
    el("div.row", {}, [
      el("span.st", { text: event.stage || "—" }),
      el("span.ty", { text: event.type }),
      el("span.pl", { text: JSON.stringify(event.payload || {}) }),
    ]));
  replace(streamInner, el("div.eventlog", {}, rows));
}

function showError(error) {
  const message = error instanceof ApiError ? error.message : String(error);
  replace(streamInner, el("div.banner.b-crit", {}, [
    el("span.bt", { text: "Could not start the council" }),
    el("span.bd", { text: message }),
  ]));
}

async function startChat() {
  try {
    events = [];
    renderEvents();

    const chat = await api.createChat("mock", "RIGOROUS");
    if (stream) stream.close();

    stream = subscribe(chat.chatId, {
      onStatus: setConnection,
      onCouncilEvent: (event) => {
        events.push(event);
        renderEvents();
      },
    });

    await api.sendMessage(chat.chatId, "Should we migrate our monolith to microservices?");
  } catch (error) {
    showError(error);
  }
}

document.getElementById("new-chat").addEventListener("click", startChat);

document.getElementById("sidebar-toggle").addEventListener("click", () => {
  const sidebar = document.getElementById("sidebar");
  sidebar.dataset.open = sidebar.dataset.open === "true" ? "false" : "true";
});
