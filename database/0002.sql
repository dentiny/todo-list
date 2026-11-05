-- Revision used to reject stale edits to shared tasks. Local tasks keep revision 1.
ALTER TABLE tasks ADD COLUMN sync_version INTEGER NOT NULL DEFAULT 1;
