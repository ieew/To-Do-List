# 任务时间与视图范围模型（参考）

> 本文是「开始时间 / 今天 / 全部 / 三视图联动」的唯一权威说明。下一次调整任务过滤、分组、计数、日历或统计时，先读本文对应章节，再按文末「改动清单」逐项检查。
>
> 实现日期：2026-11-09。对应前端：`windows/src/lib/store.svelte.ts`、`windows/src/lib/utils.ts`。

## 一、结论速览

| 概念 | 规则 |
|---|---|
| 是否已经开始 | `startAt` 有值且 `startAt <= 当前时间`；`startAt` 为空或非法视为已开始（兼容旧数据） |
| 截止时间的作用 | 只是 deadline：行内文案、逾期高亮、日历定位；**不参与是否显示、不参与分组、不决定今天** |
| 智能清单「今天」 | 所有**已开始**的任务（跨清单），未开始的任务不出现 |
| 虚拟清单「全部」 | 所有任务；侧栏计数只算未完成，列表内附「已完成」分组 |
| 任务分组 | 「今天」（已开始）/「即将到来」（未开始）/「已完成」；只看开始时间 |
| 三个视图 | 「今天（任务列表）/ 日历 / 统计」统一读取 `store.viewTasks`，跟随侧栏当前选择 |
| 自动刷新 | `store.nowMs` 每 60 秒更新一次，开始时间到点后最多约 1 分钟自动进入「今天」 |

## 二、字段与 op

| 前端字段 | op 字段 | 创建时默认值 | 说明 |
|---|---|---|---|
| `startAt` | `start_at` | Rust `create_task` 用 `Local::now().to_rfc3339()` | 详情抽屉与快速新建均可编辑；`null` 的旧任务按已开始处理 |
| `dueAt` | `due_at` | `null`（快速新建默认当前 + 7 天） | 截止时间，仅用于展示/逾期/日历 |
| `remindAt` | `remind_at` | `null` | 提醒时间 |

- `CreateTaskInput`（`windows/src-tauri/src/commands.rs`）包含 `start_at`；前端 `CreateTaskPayload` 对应 `startAt`。
- `update_task_field` 通过 `map_field` 把 `startAt → start_at`，其余字段同理。
- 历史动作文案：`start_at → 修改了开始时间`；撤销走通用反向 op，无需特殊处理。
- 投影 DTO：`core_domain::Task.start_at`，前端 `Task.startAt`。

## 三、核心判定与代码位置

| 职责 | 位置 |
|---|---|
| 是否已开始 | `windows/src/lib/utils.ts` → `hasStarted(task, base)` |
| 视图时钟 | `store.svelte.ts` → `nowMs`（`$state`，60s 定时器） |
| 侧栏范围 | `store.svelte.ts` → `scopedTasks` |
| 联动集合 | `store.svelte.ts` → `viewTasks`（日历 / 统计 / 进度共用） |
| 分组 | `store.svelte.ts` → `groups` |
| 计数 | `store.svelte.ts` → `countForList` |
| 便利贴集合 | `store.svelte.ts` → `stickyTasks` |
| 虚拟清单 id | `store.svelte.ts` → `ALL_LIST_ID = 'all'`、`get allListId()` |
| 工作区刷新放行 | `store.svelte.ts` → `applyProjection`（`activeListId === 'all'` 时不清回「今天」） |

`hasStarted` 的精确语义：

```ts
// startAt 为空或非法 → true（旧数据视为已开始）
// 否则 startAt <= base
hasStarted(task, new Date(store.nowMs))
```

## 四、清单、计数与分组

### 4.1 选择范围

| 侧栏选择 | `scopedTasks` | 是否叠加开始时间过滤 |
|---|---|---|
| 今天（智能清单） | 所有任务（不按 `listId` 过滤） | 是（`viewTasks`） |
| 全部（虚拟清单） | 所有任务 | 否 |
| 普通清单 | `listId === activeListId` | 否 |
| 标签 | `tagIds` 包含标签 | 否（标签范围按普通清单逻辑处理） |

> 标签筛选会把 `activeListId` 置为「今天」，但 `viewTasks` 在 `activeTagId` 有值时**不**叠加开始时间过滤，避免标签页任务凭空消失。

### 4.2 侧栏计数

| 清单 | 计数口径 |
|---|---|
| 今天 | 已开始的未完成任务数 |
| 全部 | 所有未完成任务数 |
| 普通清单 | 该清单全部任务数（含已完成、含未开始） |
| 标签 | 该标签全部任务数 |

### 4.3 任务分组（所有范围统一）

```
今天      = 未完成 && 已开始
即将到来  = 未完成 && 未开始
已完成    = 已完成
```

- 分组只看开始时间，**不使用 `dueAt`**。
- 「今天」范围下过滤后只剩已开始任务，因此通常只出现「今天」；已完成的已开始任务会进入「已完成」分组。
- 普通清单 / 标签 / 全部会出现「即将到来」；开始时间到点后任务自动移入「今天」。
- 搜索是全局搜索，不受当前范围与开始时间影响。

## 五、视图联动

| 视图 / 组件 | 数据源 | 说明 |
|---|---|---|
| 任务列表 `TaskList` | `store.groups` | 分组见 4.3 |
| 进度卡 `ProgressCard` | `store.progress` → `viewTasks` | 标题随范围：今日进度 / 全部进度 / 清单进度 / 标签进度 |
| 日历 `CalendarView` | `store.viewTasks` | 按 `dueAt` 定位到本周；「逾期」也基于该范围 |
| 统计 `StatsView` | `store.viewTasks` | 完成率、已完成、逾期、AI、优先级、清单分布全部按当前范围计算 |
| 便利贴 `StickyMode` / `FloatingSticky` | `store.stickyTasks` | 今天 = 已开始任务（含已完成，便于勾选）；全部 = 全部任务；普通清单按 `listId` |

- 切换侧栏清单 / 标签会同时影响以上所有视图；`setActiveList` 会把视图切回「今天（任务列表）」。
- 日历仍以截止时间排布，但**只展示当前范围内的任务**；开始时间只决定任务是否进入「今天」范围。

## 六、交互与文案

- 任务行：开始时间在未来且未完成时，元信息行显示「HH:mm 开始」弱化标签，提示其暂不进入今天。
- 快速新建 / 详情抽屉：提供「开始」日期时间字段；详情抽屉保存时生成 `start_at` 历史 op。
- 空态文案按范围区分：
  - 今天：今天没有进行中的任务
  - 全部：还没有未完成的任务
  - 普通清单：这个清单还没有任务
  - 标签：这个标签还没有任务
- 便利贴标题使用 `store.activeListName`；范围为「今天」或「全部」时，新建任务回落到第一个非智能清单。

## 七、边界与已知行为

1. `startAt` 为空或非法（旧数据、脏数据）→ 视为已开始，避免历史任务从「今天」消失。
2. 「全部」是纯前端虚拟清单：不写入 `lists` 投影、不写 `op_log`、不参与 AI 清单匹配；刷新工作区时必须放行 `activeListId === 'all'`。
3. 开始时间到点不会立即刷新：`nowMs` 每 60 秒跳动一次，最长约 1 分钟延迟。需要更实时可改小间隔或按最近开始时间精确调度。
4. 已完成任务的 `startAt` 若仍在未来，不会出现在「今天」范围（`viewTasks` 先按开始时间过滤），但会出现在「全部」。
5. 日历的周起始为周一；`now` 在组件初始化时确定，跨天需重新进入视图或刷新。
6. 普通清单计数包含已完成与未开始任务，与「今天 / 全部」的未完成计数口径不同，属有意设计。

## 八、改动清单（下次修改必查）

### 前端

- `windows/src/lib/utils.ts`：`hasStarted`、`formatStartShort`、`formatDueShort`。
- `windows/src/lib/types.ts`：`Task.startAt`、`TaskField.startAt`、`TaskGroup.key`。
- `windows/src/lib/ipc.ts`：`CreateTaskPayload.startAt`。
- `windows/src/lib/store.svelte.ts`：
  - `ALL_LIST_ID` / `allListId` / `nowMs` / 60s 定时器
  - `fieldValue` / `assignField`（新增字段时）
  - `scopedTasks` / `viewTasks` / `groups` / `progress` / `hasScopedTasks`
  - `countForList` / `todayTasks` / `stickyTasks` / `activeListName` / `headerTitle`
  - `setActiveList` / `applyProjection`
  - `saveDraft` 字段列表 / `fieldLabel` / `displayValue`
- `windows/src/components/`：
  - `Sidebar.svelte`（「全部」入口与选中态）
  - `TaskList.svelte`（空态文案）
  - `ProgressCard.svelte`（标题）
  - `TaskRow.svelte`（未开始标记）
  - `DetailDrawer.svelte` / `QuickAddModal.svelte`（开始时间输入）
  - `CalendarView.svelte` / `StatsView.svelte`（统一用 `viewTasks`）
  - `StickyMode.svelte` / `FloatingSticky.svelte`（范围、标题、新建回落）

### Rust

- `windows/src-tauri/src/core_domain.rs`：`Task.start_at`、`action_of` 的 `start_at` 文案、投影读取。
- `windows/src-tauri/src/commands.rs`：`CreateTaskInput.start_at`、`map_field`、`create_task` 默认值。

### 文档

- 本文 `docs/task-time-and-scope-model.md`
- `docs/ui-implementation-spec.md`（布局、任务行、抽屉、侧栏、空态、固定文案）
- `docs/ui界面文档体系盘点.md`（镜像章节）
- `docs/ui-delivery-checklist.md`（交互、数据、验收、常见坑）
- `docs/windows-frontend-spec.md`（状态分层、IPC）
- `docs/sticky-note-spec.md`（便利贴范围）
- `docs/ai-local-integration.md`（AI 创建任务的开始时间）
- `plans/todo-app-plan.md`（task 字段清单）
- `windows/README.md`（已实现内容）

## 九、验收用例

1. 开始时间 1 小时前、截止 5 天后 → 立即出现在「今天」。
2. 开始时间明天、截止今天 → 不在「今天」；在「全部」/普通清单的「即将到来」。
3. 开始时间 2 秒后 → 创建时在「即将到来」，视图时钟跨过后自动进入「今天」（≤ 约 60 秒）。
4. 完成任务 → 「今天」与「全部」计数各减 1，任务进入「已完成」分组。
5. 依次切换 今天 / 全部 / 普通清单 / 标签 → 日历的本周数量与逾期数、统计的完成率等数字同步变化。
6. 旧任务（`startAt` 为空）→ 视为已开始，仍在「今天」。
7. 标签范围 → 按开始时间分「今天 / 即将到来」，但不会因未开始而被隐藏。
8. 便利贴下拉切换今天 / 全部 / 普通清单 → 标题、列表、进度标签同步变化；新建任务不写入智能清单。
