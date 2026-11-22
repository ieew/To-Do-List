-- 服务端提醒触发记录：同一条 (工作区, 任务, remind_at) 只推一次。
-- 与 dsh_messages 分开：消息会过期清理，这张表要长期记得「这条提醒已经发过」。

CREATE TABLE dsh_reminder_fires (
  workspace_id TEXT NOT NULL,
  task_id      TEXT NOT NULL,
  remind_at    INTEGER NOT NULL,
  user_id      TEXT NOT NULL,
  fired_at     INTEGER NOT NULL,
  PRIMARY KEY (workspace_id, task_id, remind_at, user_id)
);

CREATE INDEX idx_dsh_reminder_fires_at ON dsh_reminder_fires(fired_at);
