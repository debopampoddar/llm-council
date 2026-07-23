// main.js — app state and orchestration.
//
// Flow: boot loads the catalog and the chat list, then profile + depth drive a
// preflight call. Sending creates the chat if there isn't one, opens the event
// stream before the first message so no council event falls into the gap, then
// posts. The POST response already carries the RUNNING turn and its
// councilSessionId — the key everything session-scoped is fetched with — so
// state updates immediately rather than waiting for the stream to say so.

import { api, ApiError } from "./api.js";
import { subscribe } from "./sse.js";
import { el, replace } from "./dom.js";
import { independenceFor } from "./health.js";
import { renderTimeline, buildTimeline } from "./timeline.js";
import {
  renderAbsentSignals,
  renderDissent,
  renderExclusions,
  renderSycophancy,
  renderTrustStrip,
  renderWarnings,
} from "./trust.js";
import {
  isTestOnly,
  renderChatList,
  renderComposer,
  renderTopbarConfig,
  renderTurnStatus,
  renderUserMessage,
} from "./chat.js";

const dom = {
  chatList: document.getElementById("chat-list"),
  topbarConfig: document.getElementById("topbar-config"),
  streamInner: document.getElementById("stream-inner"),
  composerSlot: document.getElementById("composer-slot"),
  conn: document.getElementById("conn"),
  connLabel: document.getElementById("conn-label"),
};

const state = {
  catalog: null,
  chats: [],
  activeChatId: null,
  activeChat: null,
  profileId: "mock",
  depthMode: "RIGOROUS",
  health: null,
  independence: null,
  error: null,
  // Council events accumulate per council session, keyed by the councilSessionId
  // that arrives on the turn — the only bridge from chat-scope to session-scope.
  councilEvents: new Map(),
  expandedStages: new Map(),
  // CouncilRunResponse per council session — the trust signals, fetched once a
  // turn reaches a terminal state.
  runResults: new Map(),
};

let stream = null;

// ── Connection indicator

function setConnection(status, detail) {
  dom.conn.dataset.state = status;
  const labels = {
    open: "live",
    retrying: `reconnecting in ${Math.round((detail || 0) / 1000)}s`,
    closed: "not connected",
  };
  dom.connLabel.textContent = labels[status] || status;
}

// ── Rendering

function render() {
  renderChatList(dom.chatList, state.chats, state.activeChatId, { onSelect: selectChat });
  renderTopbarConfig(dom.topbarConfig, state, {
    onProfileChange: (profileId) => { state.profileId = profileId; refreshHealth(); },
    onDepthChange: (depthMode) => { state.depthMode = depthMode; refreshHealth(); },
  });
  renderComposer(dom.composerSlot, state, { onSend: send });
  renderStream();
}

function renderStream() {
  if (state.error) {
    replace(dom.streamInner, el("div.banner.b-crit", {}, [
      el("span.bt", { text: "Something went wrong" }),
      el("span.bd", { text: state.error }),
    ]));
    return;
  }

  const turns = state.activeChat?.turns || [];
  if (!turns.length) {
    replace(dom.streamInner, el("div.empty", {}, [
      el("h2", { text: state.activeChat ? "No messages yet" : "No chat selected" }),
      el("p", {
        text: state.activeChat
          ? "Ask the council a question below."
          : "Pick a profile and ask a question. The mock profile runs the full rigorous protocol offline, with no model runtime.",
      }),
    ]));
    return;
  }

  replace(dom.streamInner, turns.map(renderTurn));
}

function renderTurn(turn) {
  const parts = [renderUserMessage(turn.userMessage)];

  const events = state.councilEvents.get(turn.councilSessionId) || [];
  if (events.length) {
    // The timeline lives under its own turn rather than in a detached pane, so
    // the evidence never sits apart from the answer it qualifies.
    const container = el("div.timeline");
    renderTimeline(container, events, {
      expanded: expandedFor(turn.councilSessionId),
      onToggle: (stageIndex) => {
        const open = expandedFor(turn.councilSessionId);
        if (open.has(stageIndex)) open.delete(stageIndex);
        else open.add(stageIndex);
        render();
      },
    });
    parts.push(container);
  } else if (turn.status === "RUNNING") {
    parts.push(el("div.banner.b-info", {}, [
      el("span.bt", { text: "Council starting…" }),
      el("span.bd", { text: "Stages appear as they run." }),
    ]));
  }

  const status = renderTurnStatus(turn, { onRetry: send });
  if (status) parts.push(status);

  if (turn.assistantAnswer) {
    parts.push(renderAnswer(turn));
  }

  return el("div.turn", {}, parts);
}

/**
 * The answer, with everything that qualifies it above the prose.
 *
 * <p>Order is the argument here. Below the recommendation these become
 * footnotes to a conclusion the reader has already accepted.
 */
function renderAnswer(turn) {
  const result = state.runResults.get(turn.councilSessionId);
  const events = state.councilEvents.get(turn.councilSessionId) || [];
  const { rows } = buildTimeline(events);
  const skipped = rows.filter((row) => row.status === "noop");
  const debateRow = rows.find((row) => row.name === "DEBATE");

  // Cautious by default: only claim sycophancy was measured when a debate
  // stage is known to have run. Under-claiming is the safe direction.
  const debateRan = Boolean(debateRow && debateRow.status === "done");

  if (!result) {
    // The result is fetched separately, so render the answer rather than
    // withholding it — but say plainly that the trust signals are not in yet.
    return el("div.answer", {}, [
      el("div.trust", {}, [
        el("span.lead", { text: "Trust" }),
        el("span.pf-msg", { text: "loading run result…" }),
      ]),
      el("div.ans-body", {}, [el("div.prose", { text: turn.assistantAnswer })]),
    ]);
  }

  const preserveDissent = preserveDissentFor(result.protocolId);

  return el("div.answer", {}, [
    renderTrustStrip(result, {
      debateRan,
      testOnly: isTestOnly(state.catalog, result.profileId),
    }),
    el("div.ans-body", {}, [
      renderSycophancy(result),
      renderExclusions(result),
      ...(renderWarnings(result) || []),
      el("div.prose", { text: turn.assistantAnswer }),
      renderDissent(turn.assistantAnswer, preserveDissent),
      renderAbsentSignals(skipped),
    ]),
  ]);
}

/** Whether the protocol behind a run had dissent preservation switched on. */
function preserveDissentFor(protocolId) {
  const protocol = (state.catalog?.protocols || []).find((p) => p.id === protocolId);
  if (!protocol) return null;
  const options = protocol.stageOptions?.SYNTHESIZE;
  if (!options || options["preserve-dissent"] === undefined) return null;
  return Boolean(options["preserve-dissent"]);
}

/** Fetch the trust signals for any finished turn that does not have them yet. */
async function ensureResults() {
  const turns = state.activeChat?.turns || [];
  const pending = turns.filter((turn) =>
    turn.councilSessionId
    && turn.status !== "RUNNING"
    && !state.runResults.has(turn.councilSessionId));

  for (const turn of pending) {
    try {
      state.runResults.set(turn.councilSessionId, await api.runResult(turn.councilSessionId));
    } catch {
      // A 404 means the run produced no result — leave it absent rather than
      // inventing one, and the answer renders without a trust strip.
    }
  }
  if (pending.length) renderStream();
}

function expandedFor(sessionId) {
  if (!state.expandedStages.has(sessionId)) state.expandedStages.set(sessionId, new Set());
  return state.expandedStages.get(sessionId);
}

// ── Data

async function refreshHealth() {
  state.independence = independenceFor(state.catalog, state.profileId, state.depthMode);
  state.health = null;
  render();
  try {
    state.health = await api.profileHealth(state.profileId, state.depthMode);
  } catch (error) {
    state.health = { profileId: state.profileId, runnable: false, models: [], warnings: [],
                     policyId: "unknown", detail: describe(error) };
  }
  render();
}

async function refreshChats() {
  try {
    state.chats = await api.listChats();
  } catch (error) {
    state.error = describe(error);
  }
}

async function selectChat(chatId) {
  try {
    state.activeChatId = chatId;
    state.activeChat = await api.getChat(chatId);
    state.profileId = state.activeChat.profileId;
    state.depthMode = state.activeChat.depthMode;
    state.independence = independenceFor(state.catalog, state.profileId, state.depthMode);
    openStream(chatId);
    render();
    await ensureResults();
    await refreshHealth();
  } catch (error) {
    state.error = describe(error);
    render();
  }
}

function openStream(chatId) {
  if (stream) stream.close();
  stream = subscribe(chatId, {
    onStatus: setConnection,
    onSnapshot: (chat) => {
      state.activeChat = chat;
      render();
    },
    onCouncilEvent: (event) => {
      const list = state.councilEvents.get(event.sessionId) || [];
      list.push(event);
      state.councilEvents.set(event.sessionId, list);
      renderStream();
    },
    onChatEvent: () => {
      // Turn transitions arrive as chat events; the snapshot carries the bodies,
      // so refresh the chat rather than patching turn state field by field.
      api.getChat(chatId).then((chat) => {
        state.activeChat = chat;
        render();
        ensureResults();
        refreshChats().then(render);
      }).catch(() => {});
    },
  });
}

async function send(text) {
  state.error = null;
  try {
    if (!state.activeChatId) {
      const chat = await api.createChat(state.profileId, state.depthMode);
      state.activeChatId = chat.chatId;
      state.activeChat = chat;
      // Subscribe before the first message so no council event lands in the gap.
      openStream(chat.chatId);
    }
    render();
    state.activeChat = await api.sendMessage(state.activeChatId, text);
    render();
    await refreshChats();
    render();
  } catch (error) {
    state.error = describe(error);
    render();
  }
}

function newChat() {
  if (stream) stream.close();
  stream = null;
  state.activeChatId = null;
  state.activeChat = null;
  state.error = null;
  setConnection("closed");
  render();
  refreshHealth();
}

function describe(error) {
  return error instanceof ApiError ? error.message : String(error);
}

// ── Boot

document.getElementById("new-chat").addEventListener("click", newChat);
document.getElementById("sidebar-toggle").addEventListener("click", () => {
  const sidebar = document.getElementById("sidebar");
  sidebar.dataset.open = sidebar.dataset.open === "true" ? "false" : "true";
});

(async function boot() {
  try {
    state.catalog = await api.catalog("profiles,policies,protocols");
    const ids = (state.catalog.profiles || []).map((p) => p.id);
    if (!ids.includes(state.profileId)) state.profileId = ids[0] || "mock";
  } catch (error) {
    state.error = describe(error);
  }
  await refreshChats();
  render();
  await refreshHealth();
})();
