// chat.js — chat list, composer, and the turn stream.
//
// Turn states map to ChatTurnStatus. RUNNING shows the live timeline inline,
// PARTIAL shows the answer with its failure reason, FAILED and REJECTED show
// the reason and a retry. The run inspector is not a separate pane: evidence
// expands under the turn it belongs to, so a signal never sits detached from
// the answer it qualifies.

import { el, replace, pill } from "./dom.js";
import { renderHealth, classify, independencePill } from "./health.js";

const DEPTHS = ["QUICK", "BALANCED", "RIGOROUS"];

/** Whether a profile is flagged test-only in the catalog. */
export function isTestOnly(catalog, profileId) {
  const profile = (catalog?.profiles || []).find((p) => p.id === profileId);
  return Boolean(profile?.testOnly);
}

/**
 * Render the sidebar chat list.
 *
 * @param liveStatus the open chat's current status, which is fresher than the
 *                   listing — the listing is refetched after the fact, so
 *                   relying on it alone leaves a window where a chat that just
 *                   started a run still looks idle
 */
export function renderChatList(container, chats, activeChatId, handlers, liveStatus) {
  if (!chats.length) {
    replace(container, el("p.chat-empty", { text: "No chats yet." }));
    return;
  }
  const items = chats.map((chat) => {
    const title = chat.firstUserMessage || "Untitled chat";
    const status = chat.chatId === activeChatId && liveStatus ? liveStatus : chat.status;
    const running = status === "RUNNING";

    const open = el("button.chat-open", {
      type: "button",
      "aria-current": String(chat.chatId === activeChatId),
      onClick: () => handlers.onSelect(chat.chatId),
    }, [
      el("span.t", { text: title }),
      el("span.s", { text: `${chat.profileId} · ${chat.depthMode} · ${chat.turnCount} turn${chat.turnCount === 1 ? "" : "s"}` }),
    ]);

    // The server returns 409 while a turn is running, because the run would
    // finish and write back to a chat that no longer exists. Disabling the
    // control says that up front instead of surfacing the conflict as an error.
    const remove = el("button.chat-delete", {
      type: "button",
      title: running ? "This chat has a run in progress" : "Delete chat",
      "aria-label": running ? `Cannot delete ${title} while it is running` : `Delete ${title}`,
      disabled: running ? "disabled" : null,
      onClick: (event) => {
        event.stopPropagation();
        handlers.onDelete(chat.chatId, title);
      },
    }, ["×"]);

    return el(`div.chat-item${chat.chatId === activeChatId ? ".is-active" : ""}`, {}, [open, remove]);
  });
  replace(container, items);
}

/**
 * Render the profile/depth controls shown in the top bar.
 *
 * <p>A chat fixes its profile and depth at creation, so once one is selected
 * these become a read-only label rather than a control that silently does
 * nothing.
 */
export function renderTopbarConfig(container, state, handlers) {
  if (state.activeChat) {
    replace(container, el("div.field", {}, [
      pill("accent", state.activeChat.profileId, { led: false }),
      pill("mute", state.activeChat.depthMode, { led: false }),
      isTestOnly(state.catalog, state.activeChat.profileId) ? pill("warn", "test only") : null,
      state.independence ? independencePill(state.independence) : null,
    ]));
    return;
  }

  const profiles = state.catalog?.profiles || [];
  const select = el("select", {
    id: "profile-select",
    onChange: (event) => handlers.onProfileChange(event.target.value),
  }, profiles.map((profile) =>
    el("option", {
      value: profile.id,
      selected: profile.id === state.profileId ? "selected" : null,
    }, [profile.testOnly ? `${profile.id} · test only` : profile.id])));

  const depth = el("div.seg", {}, DEPTHS.map((mode) =>
    el("button", {
      type: "button",
      "aria-pressed": String(mode === state.depthMode),
      onClick: () => handlers.onDepthChange(mode),
    }, [mode])));

  replace(container, el("div.field", {}, [
    el("label", { for: "profile-select", text: "Profile" }),
    select,
    depth,
    state.independence ? independencePill(state.independence) : null,
  ]));
}

// The composer re-renders whenever health or run state changes, which happens
// while the user is mid-sentence. The textarea is therefore created once and
// reused, so a re-render never eats focus or half-typed text.
let composerInput = null;

/** Render the composer, including the preflight gate. */
export function renderComposer(container, state, handlers) {
  const sendable = state.health ? classify(state.health).sendable : false;
  const busy = state.activeChat?.status === "RUNNING";

  if (!composerInput) {
    composerInput = el("textarea", { class: "composer-box", rows: "2" });
    composerInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        submit();
      }
    });
  }
  const input = composerInput;
  input.placeholder = busy ? "The council is still working…" : "Ask the council…";
  input.disabled = Boolean(busy);

  const button = el("button.btn.btn-primary", {
    type: "button",
    disabled: !sendable || busy ? "disabled" : null,
    onClick: () => submit(),
  }, [busy ? "Running…" : "Send"]);

  function submit() {
    const text = input.value.trim();
    if (!text || !sendable || busy) return;
    input.value = "";
    handlers.onSend(text);
  }

  // A test-only profile returns deterministic fabricated text. Saying so before
  // the send button is the difference between a fixture and an answer someone
  // might act on.
  const testOnly = isTestOnly(state.catalog, state.profileId)
    ? el("div.preflight", {}, [el("div.banner.b-warn", {}, [
        el("span.bt", { text: `${state.profileId} is a test-only profile` }),
        el("span.bd", { text: "It exercises the full protocol offline and returns fabricated text. Nothing it produces is a real council answer." }),
      ])])
    : null;

  replace(container, el("div.composer", {}, [
    testOnly,
    renderHealth(state.health, state.independence),
    el("div.composer-in", {}, [input, button]),
  ]));
}

/** A user message bubble. */
export function renderUserMessage(text) {
  return el("div.msg-user", { text });
}

/** Turn-level status banner for the non-COMPLETED cases. */
export function renderTurnStatus(turn, handlers) {
  if (turn.status === "RUNNING") return null;
  if (turn.status === "COMPLETED") return null;

  if (turn.status === "PARTIAL") {
    return el("div.banner.b-warn", {}, [
      el("span.bt", { text: "Partial answer — the council did not finish cleanly" }),
      el("span.bd", { text: turn.failureReason || "No reason reported." }),
    ]);
  }

  const retry = el("button.btn.btn-sm", {
    type: "button",
    onClick: () => handlers.onRetry(turn.userMessage),
  }, ["Retry"]);

  return el("div.banner.b-crit", {}, [
    el("span.bt", { text: turn.status === "REJECTED" ? "Run rejected" : "Run failed" }),
    el("span.bd", { text: turn.failureReason || "No reason reported." }),
    retry,
  ]);
}
