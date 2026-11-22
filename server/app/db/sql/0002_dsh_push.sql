-- dsh-kit 推送通道：设备、待投消息、逐设备确认。
-- 旁路数据，不参与 op_log 投影；seq 独立自增，与 op_log.seq 无关。

CREATE TABLE dsh_devices (
  device_id     TEXT PRIMARY KEY,
  user_id       TEXT NOT NULL REFERENCES users(user_id),
  name          TEXT NOT NULL DEFAULT '',
  platform      TEXT NOT NULL DEFAULT '',
  last_seen_at  INTEGER,
  created_at    INTEGER NOT NULL,
  revoked       INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_dsh_dev_user ON dsh_devices(user_id, revoked);

CREATE TABLE dsh_messages (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  msg_id      TEXT NOT NULL UNIQUE,
  user_id     TEXT NOT NULL,
  device_id   TEXT,
  kind        TEXT NOT NULL,
  payload     TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL
);

CREATE INDEX idx_dsh_msg_pending ON dsh_messages(user_id, seq);

CREATE INDEX idx_dsh_msg_expiry ON dsh_messages(expires_at);

CREATE TABLE dsh_acks (
  seq        INTEGER NOT NULL REFERENCES dsh_messages(seq),
  device_id  TEXT NOT NULL,
  acked_at   INTEGER NOT NULL,
  PRIMARY KEY (seq, device_id)
);
