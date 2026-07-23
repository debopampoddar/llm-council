// sse.js — EventSource lifecycle for /api/council/chats/{id}/events.
//
// The stream multiplexes three event names: `snapshot` (a ChatResponse sent on
// connect), `chat` (ChatEvent) and `council` (CouncilEvent).
//
// Known server limitation, matching CLAUDE.md: the endpoint replays its full
// history on every connect and honours no cursor. A reconnect therefore
// redelivers every event the client has already seen, so deduping is not an
// optimisation here — without it a reconnect mid-run would double every stage
// in the timeline. Frames now carry the event's own id, so dedupe keys on that.
//
// EventSource reconnects on its own at an interval we do not control. We want
// backoff, so errors close the stream and schedule the retry ourselves.

const INITIAL_RETRY_MS = 1000;
const MAX_RETRY_MS = 30000;

/**
 * Subscribe to one chat's event stream.
 *
 * @param chatId   the chat to follow
 * @param handlers {onSnapshot, onChatEvent, onCouncilEvent, onStatus}
 * @returns {{close: function}} closes the stream and cancels any pending retry
 */
export function subscribe(chatId, handlers) {
  const seen = new Set();
  let source = null;
  let retryMs = INITIAL_RETRY_MS;
  let retryTimer = null;
  let closed = false;

  const status = (state, detail) => handlers.onStatus && handlers.onStatus(state, detail);

  // Returns false when this event has already been delivered on an earlier
  // connection. The snapshot has no id and is always processed: it is a full
  // state replacement, so reprocessing it is correct rather than duplicative.
  function firstTime(event) {
    if (!event.lastEventId) return true;
    if (seen.has(event.lastEventId)) return false;
    seen.add(event.lastEventId);
    return true;
  }

  function parse(event) {
    try {
      return JSON.parse(event.data);
    } catch {
      return null;
    }
  }

  function connect() {
    if (closed) return;
    source = new EventSource(`/api/council/chats/${chatId}/events`);

    source.addEventListener("open", () => {
      retryMs = INITIAL_RETRY_MS;
      status("open");
    });

    source.addEventListener("snapshot", (event) => {
      const data = parse(event);
      if (data && handlers.onSnapshot) handlers.onSnapshot(data);
    });

    source.addEventListener("chat", (event) => {
      if (!firstTime(event)) return;
      const data = parse(event);
      if (data && handlers.onChatEvent) handlers.onChatEvent(data);
    });

    source.addEventListener("council", (event) => {
      if (!firstTime(event)) return;
      const data = parse(event);
      if (data && handlers.onCouncilEvent) handlers.onCouncilEvent(data);
    });

    source.addEventListener("error", () => {
      if (closed) return;
      source.close();
      status("retrying", retryMs);
      retryTimer = setTimeout(connect, retryMs);
      retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
    });
  }

  connect();

  return {
    close() {
      closed = true;
      if (retryTimer) clearTimeout(retryTimer);
      if (source) source.close();
      status("closed");
    },
  };
}
