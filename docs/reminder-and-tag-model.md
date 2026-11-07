# 提醒与标签建模规格

> 已裁定：Windows 端采用 Tauri 2 + Rust 后端 + 前端界面，撤销早期 WinUI 3 方案。

## 一、提醒落地口径

### 数据模型

- `remind_at` 挂在 `task` 实体上，`operation="set"`，UTC 毫秒，字段级 LWW。
- `last_fired_at`、`snooze_until` 仅存客户端本地表，不进入 `op_log`，不参与同步。
- MVP 只支持单次绝对时间提醒。重复规则作为后续迭代，升级为独立 `reminder` 实体。

### Android 调度

- 声明 `USE_EXACT_ALARM`（普通权限，安装即授予），优先使用 `setAlarmClock()`。
- 每条提醒使用唯一 data URI 的 `PendingIntent`，避免互相覆盖。
- 处理 `BOOT_COMPLETED`：从本地 SQLite 读取未来 `remind_at` 并重建 Alarm。
- 调度前检查 `canScheduleExactAlarms()`，不可用时降级为非精确提醒并提示用户。

### Windows 调度

- 应用最小化到系统托盘，Rust 侧运行轻量计时循环，到点通过 `tauri-plugin-notifications` 弹出。
- 如用户需要「关机期间不丢提醒」，叠加 Windows App SDK 后台任务 + `TimeTrigger` 作为兜底（交付窗口 5 分钟）。
- 不依赖服务器推送任何提醒触发。

### 错过提醒补发

- 客户端每次增量拉取并应用 op 后，对投影中 `remind_at <= now` 且 `last_fired_at` 为空的活跃任务，
  生成本地补发提醒。纯客户端逻辑，服务器不介入。

## 二、标签落地口径

### 实体与关联分离

- `tag` 是独立实体：`name` / `color` 走 LWW，`__deleted` 墓碑走 LWW。
- `task` 与 `tag` 的关联走 `operation="add"` / `"remove"`，`field="__tag"`，`value="<tag_id>"`，OR-Set，add-wins。
- 禁止在 `task` 表上放 `tags` 数组列，使用 `tag` 表和 `task_tag` 关联表。

### 删除与恢复

- `tag` 删除 = `__deleted=true` 墓碑，不级联删除 `task_tag` 关联 op。
- 投影时过滤指向已删除 `tag` 的关联；`tag` 恢复后关联自然重新生效。
- `remove` 操作必须携带 `tag_id` 和移除时的 HLC。

### 投影

- 任务的当前标签集合 = `OR-Set(__tag)` 状态 JOIN `tag` 实体状态，过滤 `tag.__deleted=true`。
- 投影函数 `resolveTags(taskId, tagOps, tagStates)` 必须是确定性纯函数，与 op 到达顺序无关。

### 测试向量必须覆盖

- 并发 `add` 不同标签（都保留）。
- 并发 `add` / `remove` 同一标签（add-wins）。
- 标签删除与任务关联并发（关联 op 保留，投影过滤）。
- 标签恢复后历史关联重新生效。
- `remind_at` 并发修改（LWW）。
- `remind_at` 修改与任务删除并发（`deleted` 与 `remind_at` 独立裁决）。
