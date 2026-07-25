-- Attribute a council event to the chat whose turn produced it.
--
-- Both columns are nullable, and that is the point. A council session can exist
-- with no chat at all — the direct POST /sessions then /run path, streamed by
-- GET /sessions/{id}/events — so those events carry `seq` and nothing else.
-- Making these NOT NULL would force a sentinel chat id onto every direct run and
-- put it in the same query results as a real one.
--
-- Two sequences rather than one because they answer different questions. `seq`
-- orders one council session's own timeline. `chat_seq` orders everything in a
-- chat: its own events and the council events of every turn in it. The SSE
-- cursor reads the second, since that stream multiplexes both sources.

ALTER TABLE council_event ADD COLUMN chat_id VARCHAR(64);
ALTER TABLE council_event ADD COLUMN chat_seq BIGINT;

-- The reconnect cursor's query: one chat, everything after position N.
CREATE INDEX IF NOT EXISTS idx_event_chat ON council_event (chat_id, chat_seq);
