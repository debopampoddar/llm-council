-- Council events, one JSON document per row.
--
-- Same shape as V1: the record lives in `document`, and the scalar columns
-- exist only for filtering and ordering. The plan's sketch named this column
-- `payload` and carried only the event's payload map, rebuilding the record
-- from the columns; storing the whole document instead keeps the pattern the
-- other two tables already use and means a new field on CouncilEvent needs no
-- migration.
--
-- `seq` is the per-session monotonic counter assigned on append. It is the
-- ordering key, not occurred_at: under virtual-thread fan-out a council emits
-- many events inside one millisecond, so a timestamp leaves ties that would be
-- broken differently on each read.

CREATE TABLE IF NOT EXISTS council_event (
  id          VARCHAR(64) NOT NULL PRIMARY KEY,
  session_id  VARCHAR(64) NOT NULL,
  occurred_at BIGINT      NOT NULL,
  seq         BIGINT      NOT NULL,
  stage       VARCHAR(32),
  type        VARCHAR(48) NOT NULL,
  model_id    VARCHAR(64),
  document    CLOB        NOT NULL
);

-- Every read of this table is "one session, in order" or "one session, after
-- position N", so the index carries both columns.
CREATE INDEX IF NOT EXISTS idx_event_session ON council_event (session_id, seq);
