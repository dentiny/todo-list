-- Daymark schema v2: two application tables.
-- Initial development schema; recreate development databases when this schema changes.

CREATE TABLE tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    root_id TEXT NOT NULL,
    title TEXT NOT NULL,
    priority TEXT NOT NULL CHECK(priority IN ('P0','P1','P2')),
    status TEXT NOT NULL CHECK(status IN ('OPEN','DONE','STALE','MOVED')),
    day TEXT NOT NULL,
    origin_day TEXT NOT NULL,
    moved_to TEXT,
    review_dismissed INTEGER NOT NULL DEFAULT 0
);

-- Look up a day's tasks.
CREATE INDEX tasks_day ON tasks(day);
-- One active snapshot per logical task.
CREATE UNIQUE INDEX one_open_per_root ON tasks(root_id) WHERE status = 'OPEN';

CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    start TEXT NOT NULL, -- Local date/time anchor for recurrence, e.g. 2026-10-20T09:30.
    repeat TEXT NOT NULL CHECK(repeat IN ('ONCE','DAILY','WEEKLY','MONTHLY','YEARLY')),
    version INTEGER NOT NULL DEFAULT 1,
    next_24h_at INTEGER, -- Pending notification trigger: seconds since Unix epoch, or NULL.
    next_30m_at INTEGER, -- Pending notification trigger: seconds since Unix epoch, or NULL.
    notified_24h TEXT,   -- Latest processed local occurrence, not an append-only delivery log.
    notified_30m TEXT    -- Each lead progresses independently for recurring events.
);

-- Display events in anchor order.
CREATE INDEX events_start ON events(start);
-- Find due notifications. Clearing a pending timestamp removes it from the index.
CREATE INDEX events_next_24h ON events(next_24h_at) WHERE next_24h_at IS NOT NULL;
CREATE INDEX events_next_30m ON events(next_30m_at) WHERE next_30m_at IS NOT NULL;
