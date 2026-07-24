-- Chat events, and the counter that orders a whole chat's stream.
--
-- §6.3 has no chat_event table: chat events lived only in ChatEventBroker's
-- in-memory map. Without one the stream would be half-durable — a chat and its
-- turns would survive a restart while the event log describing them would not,
-- so a reconnect cursor would point into a history that no longer existed.
-- Symmetric with council_event by design.

CREATE TABLE IF NOT EXISTS chat_event (
  id          VARCHAR(64) NOT NULL PRIMARY KEY,
  chat_id     VARCHAR(64) NOT NULL,
  occurred_at BIGINT      NOT NULL,
  chat_seq    BIGINT      NOT NULL,
  type        VARCHAR(48) NOT NULL,
  document    CLOB        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_event_chat ON chat_event (chat_id, chat_seq);

-- The chat's sequence counter, kept on the chat itself.
--
-- It has to be durable, unlike the per-council-session counter: a council run
-- never spans a restart, but a chat does. A counter that restarted at 1 when the
-- process came back would hand new events positions already on disk, and a
-- cursor at one of those positions would replay events the client had seen while
-- skipping ones it had not.
--
-- DEFAULT 0 so the first allocation is 1, matching the in-memory allocator, and
-- so chats written before this migration start counting from a position no
-- stored event can already hold.
ALTER TABLE chat_session ADD COLUMN next_seq BIGINT DEFAULT 0 NOT NULL;
