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

### Windows 调度（已实现）

- 关闭主窗口默认隐藏到**系统托盘**（可在设置中关闭），Rust 进程与提醒循环常驻；托盘菜单提供「显示主窗口 / 打开便利贴 / 立即同步 / 退出」。
- 提醒循环每 **30 秒** tick 一次，并用 `Notify` 在本地写入、设置变更、稍后提醒时立即唤醒；睡眠唤醒与修改系统时间由 tick 自然覆盖。
- 生效时刻 = `remind_at - reminder_lead`，与 `snooze_until` 取较晚者；每条 `remind_at` 只触发一次。
- `last_fired_at` 保存**已触发过的 `remind_at` 值**（不是触发时刻），所以修改提前量不会重复提醒。
- 修改或删除任务时会清空该任务的本地提醒态；修改 `remind_at` 也会重置触发记录，避免旧记录压制新提醒。
- 通知使用 `tauri-plugin-notification`；点击通知目前只用于系统提示，稍后提醒通过任务右键菜单（10 分钟 / 1 小时）完成。
- 免打扰（`dnd_start` / `dnd_end`，支持跨天）期间不发通知，但也不丢弃：写入 `reminder_deferred`，等时段结束后合并补弹一次。
- 通知开关关闭时只更新本地触发记录，不弹系统通知。
- 客户端本地调度保留（应用在跑时由客户端自己提醒）。
- **服务端提醒（可选，默认开）**：见「一之二」，应用完全退出后也能由服务器推送到手机。

### 本地表

```sql
task_local(task_id PRIMARY KEY, last_fired_at INTEGER, snooze_until INTEGER)
reminder_deferred(task_id, remind_at, deferred_at, PRIMARY KEY(task_id, remind_at))
```

两张表都不进 `op_log`、不参与同步；换设备不会带过去。

### 错过提醒补发

- 客户端每轮调度（启动、写入、同步后、每 30 秒）都会扫描投影：`remind_at <= now` 且未触发过的活跃任务会补发提醒。
- 补发窗口为 **60 分钟**；超出窗口只更新本地触发记录，不再弹通知（避免开机后一次性轰炸）。
- 客户端仍是纯本地逻辑；服务端只做「另一条腿」的补位（见「一之二」）。

## 一之二、服务端提醒推送（dsh 通道，2026-11 新增）

> 这是后来加的：用户要求「待办提醒也能推到手机」，所以服务器从「完全不介入」变成
> 「可选的第二通道」。客户端本地调度不变，两者互不依赖，可能重复提醒一次（可接受）。

- **账号体系不另起一套**：提醒只认待办自己的 `users` / `memberships`。没有 dsh 账号，
  也没有第二套登录；推送设备必须先在该账号下用待办 JWT 注册。
- **数据来源**：直接读 `op_log` 里 `task` 的 `remind_at` / `status` / `__deleted` / `title`
  的 LWW 最新值（不引入第二份任务表）。
- **触发时刻** = `remind_at - TODO_SERVER_DSH_REMINDER_LEAD_S`（默认 0，准点）。
- **补发窗口** = `TODO_SERVER_DSH_REMINDER_WINDOW_S`（默认 3600 秒）：停机久了只补最近一小时。
- **只推一次**：`dsh_reminder_fires` 记 `(workspace_id, task_id, remind_at, user_id)`；
  改 `remind_at` 视为新记录，会再提醒（与客户端 `last_fired_at` 语义一致）。
- **收件人**：该工作区未移除的成员（每人一条，落到各自设备）。
- **消息形态**：`kind="notify"`、`payload.type="reminder"`、等级默认 6（悬浮+震动），
  `payload` 带 `task_id` / `workspace_id` / `remind_at`。
- **不做的事**：不读客户端本地的 `reminder_lead` / `dnd` / `snooze`（这些不进 op_log），
  所以服务端按准点（或统一提前量）提醒；免打扰还得客户端自己压一层。

## 二、标签落地口径

### 实体与关联分离

- `tag` 是独立实体：`name` / `color` 走 LWW，`__deleted` 墓碑走 LWW。
- `task` 与 `tag` 的关联走 `operation="add"` / `"remove"`，`field="__tag"`，`value="<tag_id>"`，OR-Set，add-wins。
- 禁止在 `task` 表上放 `tags` 数组列，使用 `tag` 表和 `task_tag` 关联表。

### 预设标签

- 每个工作区创建时必须铺 5 个预设标签：工作 / 生活 / 学习 / 重要 / 本周，id 为 `<prefix>-tag-<key>`；默认工作区 `prefix="me"`，新工作区 `prefix=workspace_id`。
- `show_in_sidebar`：仅「重要」「本周」为 `true`，直接出现在侧栏标签区；其余 3 个标签通过任务详情「＋ 标签」下拉使用。
- 预设定义必须与 Windows `storage::seed_workspace_presets` 及前端 `presetTags()` 保持一致，禁止在多个位置重复写名称与色值字面量。
- 新建工作区的预设标签与预设清单必须在同一事务内随工作区一起写入。

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
