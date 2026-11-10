CREATE TABLE users (
  user_id        TEXT PRIMARY KEY,
  username       TEXT NOT NULL UNIQUE COLLATE NOCASE,
  email          TEXT UNIQUE COLLATE NOCASE,
  display_name   TEXT NOT NULL,
  password_hash  TEXT NOT NULL,
  created_at     INTEGER NOT NULL
);

CREATE TABLE refresh_tokens (
  token_id    TEXT PRIMARY KEY,
  family_id   TEXT NOT NULL,
  user_id     TEXT NOT NULL REFERENCES users(user_id),
  device_id   TEXT,
  token_hash  TEXT NOT NULL UNIQUE,
  issued_at   INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  used_at     INTEGER,
  revoked_at  INTEGER,
  replaced_by TEXT
);

CREATE INDEX idx_refresh_family ON refresh_tokens(user_id, family_id);

CREATE TABLE workspaces (
  workspace_id TEXT PRIMARY KEY,
  name         TEXT NOT NULL DEFAULT '',
  deleted      INTEGER NOT NULL DEFAULT 0,
  created_at   INTEGER NOT NULL
);

CREATE TABLE memberships (
  workspace_id TEXT NOT NULL,
  user_id      TEXT NOT NULL,
  role         TEXT NOT NULL,
  removed      INTEGER NOT NULL DEFAULT 0,
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (workspace_id, user_id)
);

CREATE TABLE op_log (
  seq              INTEGER PRIMARY KEY AUTOINCREMENT,
  op_id            TEXT NOT NULL UNIQUE,
  workspace_id     TEXT NOT NULL,
  entity_type      TEXT NOT NULL,
  entity_id        TEXT NOT NULL,
  actor_id         TEXT NOT NULL,
  device_id        TEXT NOT NULL,
  operation        TEXT NOT NULL,
  field            TEXT NOT NULL,
  value_json       TEXT NOT NULL,
  physical_ms      INTEGER NOT NULL,
  counter          INTEGER NOT NULL,
  node_id          TEXT NOT NULL,
  source           TEXT NOT NULL,
  source_meta_json TEXT,
  received_at      INTEGER NOT NULL
);

CREATE INDEX idx_op_ws_seq ON op_log(workspace_id, seq);
CREATE INDEX idx_op_entity ON op_log(
  entity_type, entity_id, physical_ms, counter, node_id, op_id
);
CREATE INDEX idx_op_ws_entity ON op_log(workspace_id, entity_type, entity_id);

CREATE TABLE entities (
  entity_type  TEXT NOT NULL,
  entity_id    TEXT NOT NULL,
  workspace_id TEXT NOT NULL,
  created_seq  INTEGER NOT NULL,
  PRIMARY KEY (entity_type, entity_id)
);

CREATE INDEX idx_entities_ws ON entities(workspace_id, entity_type);

CREATE TABLE snapshots (
  snapshot_id     TEXT PRIMARY KEY,
  workspace_id    TEXT NOT NULL,
  seq             INTEGER NOT NULL,
  projection_json TEXT NOT NULL,
  created_at      INTEGER NOT NULL
);

CREATE INDEX idx_snapshots_ws_seq ON snapshots(workspace_id, seq);

CREATE TABLE server_hlc (
  id          INTEGER PRIMARY KEY CHECK (id = 1),
  physical_ms INTEGER NOT NULL,
  counter     INTEGER NOT NULL,
  node_id     TEXT NOT NULL
);
