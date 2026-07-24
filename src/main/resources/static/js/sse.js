// sse.js — EventSource lifecycle for /api/council/chats/{id}/events.
//
// The stream multiplexes three event names: `snapshot` (a ChatResponse sent on
// connect), `chat` (ChatEvent) and `council` (CouncilEvent).
//
// Frame ids are positions in the chat's own sequence, shared by all three
// sources, so a single integer locates a point in the whole stream. We send the
// last one back on reconnect and the server replays only what followed.
//
// The cursor goes in the query string, not in Last-Event-ID. The header is only
// sent by EventSource's own automatic reconnect, and we do not use that: it
// retries at an interval we cannot control, so errors close the stream and we
// schedule the retry ourselves with backoff. A freshly constructed EventSource
// sends no header. The server reads both.
//
// Dedupe stays as a backstop rather than the mechanism. A first connection
// replays full history source by source, which is deliberately not in sequence
// order — it is the lossless path, and it can include events that never got a
// position — so the seen set cannot be replaced by a high-water mark. It is
// bounded instead: on a long run it would otherwise grow one entry per event
// for as long as the page stayed open.

const INITIAL_RETRY_MS = 1000;
const MAX_RETRY_MS = 30000;

// Enough to cover a full-history replay of a long chat, and small enough that
// the set never becomes the page's largest object.
const MAX_SEEN_IDS = 5000;

/**
 * Subscribe to one chat's event stream.
 *
 * @param chatId   the chat to follow
 * @param handlers {onSnapshot, onChatEvent, onCouncilEvent, onStatus}
 * @returns {{close: function}} closes the stream and cancels any pending retry
 */
export function subscribe(chatId, handlers) {
  const seen = new Set();
  let lastSeq = 0;
  let source = null;
  let retryMs = INITIAL_RETRY_MS;
  let retryTimer = null;
  let closed = false;

  const status = (state, detail) => handlers.onStatus && handlers.onStatus(state, detail);

  // Returns false when this event has already been delivered on an earlier
  // connection. Frames without an id are always processed: the snapshot is a
  // full state replacement, and an event the server could not place in the
  // sequence carries no id rather than a zero — treating that as position 0
  // would reset the cursor and replay the whole stream on the next reconnect.
  function firstTime(event) {
    if (!event.lastEventId) return true;
    if (seen.has(event.lastEventId)) return false;
    remember(event.lastEventId);
    return true;
  }

  // Keeps the newest ids and drops the oldest half when the set fills. Safe
  // because the server only ever replays positions above the cursor, so an id
  // low enough to be dropped is one that cannot arrive again.
  function remember(id) {
    seen.add(id);
    const seq = Number(id);
    if (Number.isFinite(seq) && seq > lastSeq) lastSeq = seq;
    if (seen.size > MAX_SEEN_IDS) {
      const newest = [...seen]
        .map(Number)
        .filter(Number.isFinite)
        .sort((a, b) => b - a)
        .slice(0, MAX_SEEN_IDS / 2);
      seen.clear();
      newest.forEach((value) => seen.add(String(value)));
    }
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
    const resume = lastSeq > 0 ? `?lastEventId=${lastSeq}` : "";
    source = new EventSource(`/api/council/chats/${chatId}/events${resume}`);

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
