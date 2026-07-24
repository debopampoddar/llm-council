-- Sessions and chats as JSON documents in a row.
--
-- The record itself lives in `document`. Scalar columns exist only for
-- filtering and ordering, which is why adding a field to CouncilSession or
-- ChatTurn needs no migration at all — the domain types are Java records, they
-- round-trip through Jackson, and there is no ORM to keep in step with them.
--
-- Timestamps are BIGINT epoch milliseconds rather than TIMESTAMP. The two
-- engines this runs on disagree about what a TIMESTAMP column is — SQLite has
-- no such type and stores whatever the driver hands it — so ORDER BY and the
-- retention sweep's age comparison would sort correctly on one engine and
-- approximately on the other. An integer sorts and compares identically
-- everywhere, and it is Instant.toEpochMilli() at both ends.
--
-- One script serves both engines because the DDL is written to their
-- intersection. Vendor-specific migration directories are available if that
-- ever stops being true.

CREATE TABLE IF NOT EXISTS council_session (
  id          VARCHAR(64)  NOT NULL PRIMARY KEY,
  profile_id  VARCHAR(64)  NOT NULL,
  status      VARCHAR(32)  NOT NULL,
  created_at  BIGINT       NOT NULL,
  updated_at  BIGINT       NOT NULL,
  document    CLOB         NOT NULL
);

-- Listings read most-recent-first; the retention sweep reads oldest-first off
-- the same index.
CREATE INDEX IF NOT EXISTS idx_session_updated ON council_session (updated_at);
-- The interrupted-run sweeper asks for every RUNNING session at boot.
CREATE INDEX IF NOT EXISTS idx_session_status ON council_session (status);

CREATE TABLE IF NOT EXISTS chat_session (
  id          VARCHAR(64) NOT NULL PRIMARY KEY,
  profile_id  VARCHAR(64) NOT NULL,
  created_at  BIGINT      NOT NULL,
  updated_at  BIGINT      NOT NULL,
  document    CLOB        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_updated ON chat_session (updated_at);
