# 服务端通信协议（v1）

> 状态：待确认（确认后据此实现 `server/`）
> 权威约束：[`docs/conventions.md`](./conventions.md)。若本文件与 `conventions.md` 冲突，以 `conventions.md` 为准并先提出修订。
> 关联文档：[`plans/todo-app-plan.md`](../plans/todo-app-plan.md) 第 2/4 节、[`docs/collaboration-model.md`](./collaboration-model.md)、[`docs/subtask-model.md`](./subtask-model.md)、[`docs/reminder-and-tag-model.md`](./reminder-and-tag-model.md)、[`docs/ai-local-integration.md`](./ai-local-integration.md)。

## 一、范围与版本

- 本协议只描述 **客户端 ⇄ 服务端** 的 HTTP 接口与数据格式，不描述客户端本地 IPC。
- 服务端不感知 AI：`source` 与 `source_meta` 只做审计，**不参与裁决**，服务端不得据 `source="ai"` 放宽或收紧权限。
- 版本：路径前缀 `/v1`；`GET /v1/health` 返回 `protocol_version = "1.0"`。
- 破坏性变更必须开 `/v2`，禁止在同版本内改字段语义。
- 服务端只提供**轮询同步**，不提供 WebSocket / 推送通道。提醒触发由客户端本地调度，服务端不参与。

## 二、传输约定

### 2.1 基础

| 项目 | 约定 |
| --- | --- |
| Base URL | 客户端设置项 `server_url`，默认 `http://127.0.0.1:8000`，接口路径统一追加 `/v1/...` |
| 编码 | 请求与响应均为 UTF-8 JSON（`application/json`），备份导出为 `application/zip` |
| 鉴权 | `Authorization: Bearer <access_token>`（除 `register` / `login` / `refresh` / `health` 外全部必填） |
| 字段命名 | 英文 `snake_case`（见 `conventions.md` 第五节） |
| 时间 | 业务时间字段（`start_at` / `due_at` / `remind_at` / `__created`）用 **RFC 3339 带时区偏移**字符串；`at_ms` / `received_at` 等协议元数据用 Unix 毫秒（UTC） |
| 批量上限 | 单次 `push` 最多 500 条 op；请求体上限 1 MiB（超出 `413 payload_too_large`） |
| 分页上限 | `pull` / `timeline` 的 `limit` 取 1–1000，缺省 200/100 |

### 2.2 鉴权头

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

访问令牌有效期 30 分钟（可配置）。访问令牌过期返回 `401 token_expired`，客户端应先用刷新令牌换新，再重放请求；刷新令牌有效期 30 天，**每次刷新都会轮换**，旧令牌立即失效，重复使用旧刷新令牌视为泄露并按令牌族整体吊销。

### 2.3 统一错误信封

```json
{
  "error": {
    "code": "workspace_access_revoked",
    "message": "权限已变更，此操作未生效",
    "retryable": false,
    "details": null,
    "server_hlc": { "physical_ms": 1762528000000, "counter": 7, "node_id": "srv" }
  }
}
```

- `code`：英文稳定标识，客户端按 `code` 分支，禁止解析 `message`。
- `message`：中文可读文案，可直接展示。
- `retryable`：`true` 表示客户端可重试（网络、429、5xx、`token_expired`）；`false` 表示业务终态，不得自动重试。
- `details`：可选，字段级错误数组 `[{ "field": "ops[3].value", "reason": "..." }]`。
- `server_hlc`：仅时钟与权限类错误携带，用于客户端校正本地 HLC 与展示「已按服务器时间校正」。

### 2.4 通用状态码

| HTTP | 含义 |
| --- | --- |
| 200 / 201 / 204 | 成功 |
| 400 | 请求体或 op 信封不合法（整批未写入） |
| 401 | 未认证 / 令牌过期 / 凭据错误 |
| 403 | 已认证但无权限（含 `workspace_access_revoked`） |
| 404 | 资源不存在 |
| 409 | 状态冲突（重名、最后一个所有者等） |
| 413 | 请求体过大 |
| 429 | 限流（响应带 `Retry-After` 秒数） |
| 500 | 服务端内部错误（可重试） |
| 501 | 协议已定义但本期未实现 |

## 三、op 信封

### 3.1 线上格式（wire）

op 是同步的**唯一载体**，与服务端存储一一对应。字段与 [`plans/todo-app-plan.md`](../plans/todo-app-plan.md) 2.1 一致，命名改为 `snake_case`。

```json
{
  "op_id": "6f1c2f0e-8f0f-4b47-9a1f-2d3f2b4c5d6e",
  "workspace_id": "ws-1a2b3c4d",
  "entity_type": "task",
  "entity_id": "task-9f8e7d6c",
  "actor_id": "u-1a2b3c4d",
  "device_id": "win-7f3a1c2d",
  "operation": "set",
  "field": "title",
  "value": "买牛奶",
  "hlc": {
    "physical_ms": 1762528000000,
    "counter": 3,
    "node_id": "win-7f3a1c2d"
  },
  "source": "human",
  "source_meta": { "model": null, "prompt_hash": null }
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `op_id` | string(1–64) | 是 | 全局唯一。重复投递按幂等处理，不报错 |
| `workspace_id` | string(1–64) | 是 | 隔离键；必须是已存在且调用者为其成员的工作区 |
| `entity_type` | enum | 是 | `workspace` / `list` / `tag` / `task` / `membership` / `user` |
| `entity_id` | string(1–128) | 是 | 实体 id；`membership` 为 `<workspace_id>:<user_id>` |
| `actor_id` | string(1–64) | 是 | 必须等于访问令牌的 `user_id`，否则 `403 actor_mismatch` |
| `device_id` | string(1–64) | 是 | 设备标识，仅审计与排障 |
| `operation` | enum | 是 | `set` / `add` / `remove` |
| `field` | string(1–128) | 是 | 字段名，见第五节值域表 |
| `value` | JSON | 是 | 字段值；`add` / `remove` 时为集合元素 id 字符串 |
| `hlc` | object | 是 | `{physical_ms:int64, counter:uint64, node_id:string(1–64)}` |
| `source` | enum | 是 | `human` / `ai` / `import` / `system`（`system` 保留给服务端种子与系统生成） |
| `source_meta` | object | 否 | `{model: string|null, prompt_hash: string|null}`，仅审计 |

### 3.2 裁决顺序（全序）

字段单元格与集合元素均由以下四元组比较，**全序且确定性**：

```
(hlc.physical_ms, hlc.counter, hlc.node_id, op_id)
```

- 字段：`set` 取四元组最大的 op（LWW）。
- 集合：`add` / `remove` 分别记录各自最大四元组；`add` 的四元组 ≥ `remove` 的四元组时元素存活（add-wins）。
- 子任务字段复用 `__subtask:<subtask_id>:<field>` 命名，不引入新合并逻辑。

### 3.3 与 Windows 端 `Op` 结构的映射

Windows 的 `Op`（`windows/src-tauri/src/core_domain.rs`）是 IPC 内部类型：HLC 扁平、整体 `camelCase`、没有 `source_meta`。同步层负责双向映射，**不修改客户端领域结构**：

| Windows `Op` 字段 | wire 字段 |
| --- | --- |
| `op_id` | `op_id` |
| `workspace_id` | `workspace_id` |
| `entity_type` | `entity_type` |
| `entity_id` | `entity_id` |
| `actor_id` | `actor_id` |
| `device_id` | `device_id` |
| `operation` | `operation` |
| `field` | `field` |
| `value` | `value` |
| `physical_ms` | `hlc.physical_ms` |
| `counter` | `hlc.counter` |
| `node_id` | `hlc.node_id` |
| `source` | `source` |
| `model` | `source_meta.model` |
| — | `source_meta.prompt_hash`（客户端无此概念，固定 `null`） |
| — | `seq`（仅服务端返回，见 4.2） |

JSON 键名从 `camelCase` 改为 `snake_case`；值不做任何改写。

### 3.4 服务端返回的 op（pull 用）

在 wire 信封基础上追加两个只读字段：

```json
{
  "...": "同 3.1",
  "seq": 132,
  "received_at": 1762528012345
}
```

- `seq`：服务端分配的全局单调递增交付序号（`int64`）。**只表达交付顺序**，因果与冲突一律由 HLC 决定。
- `received_at`：服务端落库时间（Unix 毫秒），仅排障用。

## 四、鉴权接口

### 4.1 `POST /v1/auth/register`

注册并**在同一事务内**由服务端写入默认工作区与预设数据（与 Windows `storage::seed_if_empty` 完全等价）：

- `workspace.<ws_id>.__created` / `name`
- `membership.<ws_id>:<user_id>.__role = owner`
- `user.<user_id>.name` / `initial` / `color` / `email`
- 4 个清单：`<ws_id>-today` / `-work` / `-life` / `-study`
- 5 个标签：`<ws_id>-tag-work` / `-life` / `-study` / `-important` / `-week`
- 以上 op 的 `actor_id = user_id`、`node_id = "srv"`、`source = "system"`

请求：

```json
{
  "username": "alice",
  "password": "correct horse battery",
  "email": "alice@example.com",
  "display_name": "爱丽丝",
  "device_id": "win-7f3a1c2d",
  "workspace_name": "我的空间"
}
```

| 字段 | 规则 |
| --- | --- |
| `username` | 3–32 字符，`[A-Za-z0-9_-]`，大小写不敏感唯一 |
| `password` | 8–128 字符 |
| `email` | 可选；填写时必须合法且大小写不敏感唯一；空字符串视为未填 |
| `display_name` | 可选，1–24 字符，缺省取 `username` |
| `device_id` | 可选，1–64 字符；用于绑定刷新令牌 |
| `workspace_name` | 可选，`trim` 后 1–24 字符，缺省「我的空间」 |

响应 `201`：

```json
{
  "user": {
    "user_id": "u-1a2b3c4d",
    "username": "alice",
    "display_name": "爱丽丝",
    "email": "alice@example.com",
    "created_at": "2026-11-09T10:00:00+08:00"
  },
  "default_workspace_id": "ws-1a2b3c4d",
  "tokens": {
    "token_type": "Bearer",
    "access_token": "...",
    "access_expires_at": 1762530000000,
    "refresh_token": "...",
    "refresh_expires_at": 1765122000000
  }
}
```

错误：`409 username_taken` / `409 email_taken` / `400 invalid_username` / `400 weak_password` / `400 invalid_email`。

### 4.2 `POST /v1/auth/login`

请求 `{ "username": "alice", "password": "...", "device_id": "win-7f3a1c2d" }`。

响应 `200`：

```json
{
  "user": { "user_id": "u-1a2b3c4d", "username": "alice", "display_name": "爱丽丝", "email": "alice@example.com", "created_at": "..." },
  "workspaces": [
    { "workspace_id": "ws-1a2b3c4d", "name": "我的空间", "role": "owner" }
  ],
  "tokens": { "token_type": "Bearer", "access_token": "...", "access_expires_at": 0, "refresh_token": "...", "refresh_expires_at": 0 }
}
```

错误：`401 invalid_credentials`（用户名不存在与密码错误不区分）。

### 4.3 `POST /v1/auth/refresh`

请求 `{ "refresh_token": "...", "device_id": "win-7f3a1c2d" }` → 响应 `200` `{ "tokens": { ... } }`（轮换后的新令牌对）。

错误：`401 invalid_refresh_token`（不存在、已吊销、已过期、重复使用）。

### 4.4 `POST /v1/auth/logout`

请求 `{ "refresh_token": "..." }` → `204`。幂等：未知令牌同样返回 204。

### 4.5 `GET /v1/auth/me`

响应 `200` `{ "user": { ... } }`。

### 4.6 `GET /v1/workspaces`

响应 `200`：

```json
{
  "workspaces": [
    { "workspace_id": "ws-1a2b3c4d", "name": "我的空间", "role": "owner", "member_count": 1, "created_at": "2026-11-09T10:00:00+08:00" }
  ]
}
```

排序：按 `name` 升序。`role` 为调用者在该工作区的当前角色（不返回已移除的成员关系）。

## 五、同步接口

### 5.1 `POST /v1/sync/push`

请求：

```json
{
  "device_id": "win-7f3a1c2d",
  "ops": [ { "...": "wire 信封，见 3.1" } ]
}
```

#### 5.1.1 处理流程（逐条，按数组顺序）

1. **信封校验**：结构、必填、类型、长度、枚举、体积。任一条不合格 → 整请求 `400 invalid_op_envelope`，**不写入任何 op**。
2. **幂等**：`op_id` 已存在 → `status = "duplicate"`，返回原 `seq`，不写入。
3. **身份**：`actor_id` ≠ 令牌 `user_id` → `rejected / actor_mismatch`。
4. **时钟**：`hlc.physical_ms > server_now + 300_000`（5 分钟）→ `rejected / hlc_too_far_ahead`（带 `server_hlc`）；`physical_ms < 0` → `rejected / invalid_value`。允许历史时间（不做过去方向拒绝）。
5. **工作区与绑定**：`workspace_id` 必须存在；实体首次出现必须绑定到该工作区且首条 op 必须是 `__created`（`membership` 首条必须是 `__role`；`user` 为全局实体，豁免绑定，见 6.3）；已绑定实体不得换工作区。
6. **字段与值域**：见第六节；未知字段 → `rejected / unknown_field`。
7. **授权**：按 op 的 HLC 时刻调用者在该工作区的角色判定（见第八节）→ `rejected / workspace_access_revoked`（无成员关系或被移除）或 `rejected / forbidden`（角色不足）。
8. **写入**：分配 `seq`、落 `op_log`、更新 `entities` / `memberships` 缓存。

#### 5.1.2 响应

部分成功同样返回 `200`：

```json
{
  "server_hlc": { "physical_ms": 1762528100000, "counter": 2, "node_id": "srv" },
  "server_seq": 132,
  "results": [
    { "op_id": "a1...", "status": "accepted", "seq": 131 },
    { "op_id": "a2...", "status": "duplicate", "seq": 120 },
    {
      "op_id": "a3...",
      "status": "rejected",
      "error": {
        "code": "workspace_access_revoked",
        "message": "权限已变更，此操作未生效",
        "retryable": false,
        "server_hlc": { "physical_ms": 1762528100000, "counter": 2, "node_id": "srv" }
      }
    }
  ]
}
```

- `status ∈ {accepted, duplicate, rejected}`，与请求 `ops` 一一对应、顺序一致。
- 若**全部** op 都因权限被拒且无一条 accepted/duplicate → 返回 `403`，响应体为「错误信封 + `results`」的组合：顶层 `error` 取首个拒绝码（通常 `workspace_access_revoked`），`results` 仍逐条列出（`collaboration-model.md` 第四节的 403 口径）。
- 客户端必须把 `rejected` 标记为终态 `REJECTED`：保留在本地供导出、不自动重试、UI 提示权限已变更。
- `server_hlc` 用于客户端校正本地 HLC：`local_hlc.physical_ms = max(local_hlc.physical_ms, server_hlc.physical_ms)`，`counter` 按 HLC 规则推进。

### 5.2 `GET /v1/sync/pull`

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `workspace_id` | 否 | 指定工作区；缺省返回调用者全部工作区的 op |
| `since_seq` | 否 | 游标，缺省 0；返回 `seq > since_seq` 的 op |
| `limit` | 否 | 1–1000，缺省 200 |

响应 `200`：

```json
{
  "ops": [ { "...": "wire 信封 + seq + received_at，按 seq 升序" } ],
  "next_seq": 132,
  "has_more": false,
  "server_seq": 132,
  "server_hlc": { "physical_ms": 1762528100000, "counter": 2, "node_id": "srv" }
}
```

- `next_seq`：已返回的最后一条 `seq`；无数据时等于请求的 `since_seq`。客户端只能持久化已完整应用的 `next_seq`。
- `has_more = true` 时客户端应立即继续拉取，直到 `false` 或达到本轮时间预算。
- 一次 `pull` 可能返回多于 `limit` 条（同一工作区收尾补齐），客户端不得假设精确条数。
- 非成员访问指定工作区 → `403 workspace_access_revoked`；工作区不存在 → `404 workspace_not_found`。
- 权限变更 op（`membership.*`）与业务 op 走同一管道、同一游标交付；客户端据本地投影刷新成员列表。

### 5.3 同步时序

```mermaid
sequenceDiagram
    participant C as 客户端同步循环
    participant S as 服务端 API
    participant W as Writer Worker
    C->>S: POST /v1/sync/push（pending_ops）
    S->>W: 逐条校验 + 事务写入
    W-->>S: accepted/duplicate/rejected + server_hlc
    S-->>C: results
    C->>C: REJECTED 终态；成功项出队
    C->>S: GET /v1/sync/pull?since_seq=N
    S-->>C: ops + next_seq + has_more
    C->>C: reducer 合并 → projection-updated
```

### 5.4 时钟漂移

- 服务端阈值：`physical_ms` 超前服务端时间 5 分钟以上拒绝，错误码 `hlc_too_far_ahead`，携带 `server_hlc`。
- 客户端收到 `server_hlc` 后按 `conventions.md` 的 HLC 规则推进本地时钟，不改写已生成的 op。
- 历史方向（`physical_ms` 远小于服务端时间）**不拒绝**：离线设备补传旧 op 是正常场景。

## 六、字段与值域

### 6.1 实体字段全集

| `entity_type` | 允许的 `field` | `operation` |
| --- | --- | --- |
| `workspace` | `__created` `name` `__deleted` | `set` |
| `list` | `__created` `workspace_id` `name` `color` `order` `__deleted` | `set` |
| `tag` | `__created` `workspace_id` `name` `bg` `fg` `show_in_sidebar` `__deleted` | `set` |
| `task` | `__created` `__deleted` `title` `notes` `start_at` `due_at` `remind_at` `priority` `status` `list_id` `order` `__undo_of` | `set` |
| `task` | `__tag`（值 = `tag_id`） | `add` / `remove` |
| `task` | `__subtask`（值 = `subtask_id`） | `add` / `remove` |
| `task` | `__subtask:<subtask_id>:text` / `:done` / `:order` | `set` |
| `membership` | `__role` `__removed` | `set` |
| `user` | `name` `initial` `color` `email` | `set` |

`workspace_id` 字段（list/tag）是冗余索引字段，值必须等于 op 的 `workspace_id`，否则 `rejected / invalid_value`。

### 6.2 值域

| 字段 | 值域 |
| --- | --- |
| `__created` | RFC 3339 字符串 |
| `workspace.name` / `list.name` / `tag.name` / `user.name` | 字符串，`trim` 后 1–24（workspace）/ 1–60（list、tag、user）字符 |
| `task.title` | 字符串，`trim` 后 1–500 字符 |
| `task.notes` | 字符串，0–20000 字符 |
| `start_at` / `due_at` / `remind_at` | RFC 3339 字符串或 `null` |
| `priority` | `high` / `mid` / `low` |
| `status` | `todo` / `done` |
| `list_id` | 字符串 ≤64；`add`/`set` 时目标清单必须已存在于同一工作区（`rejected / unknown_list`） |
| `order` / `__subtask:<id>:order` | 有限数字，绝对值 ≤ 1e9 |
| `color` / `bg` / `fg` | 字符串 ≤32（`#RRGGBB` 或 `transparent`） |
| `show_in_sidebar` / `__deleted` / `__removed` / `__subtask:<id>:done` | 布尔 |
| `__tag` / `__subtask` 元素值 | 字符串 ≤64；未知标签/子任务不拒绝，投影自然过滤 |
| `__subtask:<id>:text` | 字符串 0–2000 字符 |
| `__undo_of` | 字符串 ≤64（目标 `op_id`，仅审计撤销关系） |
| `membership.__role` | `owner` / `editor` / `viewer` |
| `user.initial` | 1–2 字符 |
| `user.email` | ≤254 字符；可空字符串 |

`entity_type` / `operation` / `source` 的枚举值在信封校验阶段判定，非法值整请求 `400 invalid_op_envelope`；字段不在表内 → 逐 op `unknown_field`；`operation` 与字段不匹配（如对 `__tag` 用 `set`）→ 逐 op `invalid_operation`。

### 6.3 实体与工作区绑定

- `workspace`：`entity_id` 必须等于 `workspace_id`；首条 op 必须是 `__created`。
- `membership`：`entity_id` 必须是 `<workspace_id>:<user_id>`；首条 op 必须是 `__role`。
- `list` / `tag` / `task`：首次出现必须绑定到 op 的 `workspace_id`，且首条 op 必须是 `__created`；此后同一实体不得出现在其他工作区（`rejected / entity_workspace_mismatch`）。
- `user`：全局实体，不做工作区绑定；`user.<id>.*` 的 op 随投递它的 `workspace_id` 交付，重复内容由 LWW 收敛。

## 七、工作区与成员接口

写接口本质是**服务端代为生成 op**（`node_id = "srv"`、`source = "human"`、`actor_id = 调用者`），与客户端推送的 op 走同一条 `op_log` 管道，因此离线端重连后能拉到权限变更。

### 7.1 `GET /v1/workspace/members?workspace_id=`

响应 `200`（任意成员可读）：

```json
{
  "workspace_id": "ws-1a2b3c4d",
  "members": [
    { "user_id": "u-1a2b3c4d", "username": "alice", "display_name": "爱丽丝", "name": "爱丽丝", "initial": "爱", "color": "#3B82F6", "email": "alice@example.com", "role": "owner", "joined_at": "2026-11-09T10:00:00+08:00" }
  ]
}
```

排序：`owner` → `editor` → `viewer`，同角色按加入时间升序。

### 7.2 `POST /v1/workspace/invite`

仅 `owner`。请求：

```json
{ "workspace_id": "ws-1a2b3c4d", "account": "bob@example.com", "role": "editor" }
```

- `account`：用户名或邮箱，大小写不敏感；被邀请者必须已注册（`404 account_not_found`）。
- 同一事务内生成：目标用户的 `user.name/initial/color/email` op（若工作区内尚无其资料）+ `membership.__role` op。
- 响应 `200` `{ "user_id": "u-...", "role": "editor" }`。
- 已经是成员：仅更新角色（等价于 7.3），不重复写 user 资料。

### 7.3 `PATCH /v1/workspace/role`

仅 `owner`。请求 `{ "workspace_id": "...", "user_id": "...", "role": "viewer" }` → `200` `{ "user_id": "...", "role": "viewer" }`。

- `409 last_owner`：会把最后一个 `owner` 降级时拒绝。
- `404 member_not_found`。

### 7.4 `POST /v1/workspace/remove`

仅 `owner`。请求 `{ "workspace_id": "...", "user_id": "..." }` → `204`（写 `membership.__removed = true`）。

- `409 last_owner`：不能移除最后一个 `owner`。
- `409 cannot_remove_self`。

### 7.5 工作区的创建 / 重命名 / 删除

不在本协议提供独立接口：工作区由客户端通过普通 op 创建（`workspace.__created` + `membership.__role` + 预设清单/标签，见 `plans/workspace-presets-and-lifecycle.md` 3.3），重命名写 `workspace.name`，删除写 `workspace.__deleted = true` 墓碑，全部经 `/v1/sync/push`。

## 八、授权矩阵

### 8.1 判定入口

服务端 Writer Worker 在追加每条 op 前，按以下顺序判定（与 `collaboration-model.md` 第三节一致）：

1. 由实体绑定反查 `workspace_id`，必须与 op 的 `workspace_id` 一致；
2. 取该 op 的 HLC 时刻（含 `op_id` 平局）该 `user_id` 的成员角色；
3. 按矩阵判定字段写入是否允许。

「HLC 时刻的角色」= 在 `(physical_ms, counter, node_id, op_id) ≤ op 的四元组` 范围内，取该成员最新 `__role` 与最新 `__removed`；`__removed` 晚于或等于 `__role` 时视为已移除。

### 8.2 矩阵

| 实体 / 字段 | owner | editor | viewer |
| --- | --- | --- | --- |
| `task.*`（含 `__tag`、`__subtask`、`__undo_of`、`__deleted`） | ✓ | ✓ | ✗ |
| `list.name` / `list.color` / `list.order` | ✓ | ✓ | ✗ |
| `list.__created` / `list.workspace_id` / `list.__deleted` | ✓ | ✗ | ✗ |
| `tag.name` / `tag.bg` / `tag.fg` / `tag.show_in_sidebar` | ✓ | ✓ | ✗ |
| `tag.__created` / `tag.workspace_id` / `tag.__deleted` | ✓ | ✗ | ✗ |
| `workspace.name` / `workspace.__deleted` | ✓ | ✗ | ✗ |
| `membership.*` | ✓ | ✗ | ✗ |
| `user.*` 目标为自己 | ✓ | ✓ | ✓ |
| `user.*` 目标为其他成员 | ✓ | ✗ | ✗ |

- `viewer` 的任何写 op 都返回 `rejected / forbidden`。
- 无成员关系或已移除 → `rejected / workspace_access_revoked`（终态，客户端不得自动重试）。
- 客户端 UI 的按钮隐藏只是体验优化，不是安全边界。
- 读接口只按成员关系过滤，不做写权限判断。

## 九、历史接口

历史与恢复都在服务端复用与客户端同构的 reducer（见 `server-design.md` 第八节），保证 `timeline` / `snapshot` 与客户端投影一致。

### 9.1 `GET /v1/history/timeline`

查询参数：`workspace_id`（必填）、`task_id`（可选）、`before_seq`（可选，取 `< before_seq` 的条目）、`limit`（1–1000，缺省 100）。

响应 `200`：

```json
{
  "entries": [
    {
      "op_id": "6f1c2f0e-...",
      "seq": 131,
      "entity_type": "task",
      "entity_id": "task-9f8e7d6c",
      "task_id": "task-9f8e7d6c",
      "task_title": "买牛奶",
      "field": "title",
      "operation": "set",
      "old_value": "买牛奶 ",
      "new_value": "买牛奶",
      "actor_id": "u-1a2b3c4d",
      "actor_name": "爱丽丝",
      "source": "human",
      "model": null,
      "at_ms": 1762528000000,
      "time_label": "3 分钟前",
      "action": "修改了标题",
      "undoable": true,
      "undone": false,
      "system": false
    }
  ],
  "next_before_seq": 131,
  "has_more": false
}
```

- 排序：`at_ms` 降序，同毫秒按 `op_id` 降序（与客户端 `build_history` 一致）。
- `undone`：存在更高 HLC 的 `task.__undo_of = <op_id>` 时为 `true`。
- `undoable`：`operation = set` 且字段不是 `__created` / `__deleted` / `__undo_of` 且 `source != "system"`。
- 权限：任意成员；`viewer` 可读。

### 9.2 `POST /v1/history/snapshot`

请求 `{ "workspace_id": "...", "seq": 132 }`（`seq` 可选，缺省取当前 `server_seq` 内该工作区最新 op）。响应 `201`：

```json
{
  "snapshot_id": "snap-1a2b3c4d",
  "workspace_id": "ws-1a2b3c4d",
  "seq": 132,
  "created_at": 1762528100000,
  "counts": { "lists": 4, "tags": 5, "members": 1, "tasks": 12 }
}
```

- 权限：任意成员。
- 服务端在每工作区累计 500 条新 op 后自动生成快照（`SNAPSHOT_EVERY_OPS=0` 可关闭）。

### 9.3 `GET /v1/history/snapshot?workspace_id=&seq=`

返回 `seq` 之后（含）最近一份快照：

```json
{
  "snapshot_id": "snap-1a2b3c4d",
  "workspace_id": "ws-1a2b3c4d",
  "seq": 132,
  "created_at": 1762528100000,
  "projection": {
    "lists": [],
    "tags": [],
    "members": [],
    "tasks": []
  }
}
```

`projection` 字段结构与客户端投影一致（snake_case）：`lists[{id,name,color,order,smart}]`、`tags[{id,name,bg,fg,show_in_sidebar}]`、`members[{user_id,name,initial,color,role,email,joined_at}]`、`tasks[{id,title,notes,start_at,due_at,remind_at,priority,status,list_id,tag_ids,subtasks[{id,text,done,order}],source,created_at,completed_at,order,model}]`。

> `tasks[].tag_ids` 与客户端投影一致：直接输出 OR-Set 存活元素，不按标签实体的 `__deleted` 过滤；已删除标签由前端结合 `tags` 列表隐藏。

`404 snapshot_not_found`：该序号之前没有快照。

### 9.4 `POST /v1/history/restore`

请求 `{ "workspace_id": "...", "snapshot_id": "snap-...", "dry_run": false }`。

响应 `200`：

```json
{
  "snapshot_id": "snap-1a2b3c4d",
  "restored_to_seq": 132,
  "dry_run": false,
  "op_count": 6,
  "changes": [
    { "entity_type": "task", "entity_id": "task-9f8e7d6c", "operation": "set", "field": "title", "old_value": "买牛奶", "new_value": "买牛奶（旧）" }
  ]
}
```

- 仅 `owner` / `editor`。
- 语义：把快照中存在的实体恢复到快照时刻的字段值，通过**追加反向 op** 实现，绝不截断或修改 `op_log`。
- 边界（必须原文写进实现与文档）：
  - 不删除快照之后新建的实体；
  - 不恢复 `membership` / `workspace` / `user`（安全边界）；
  - 不恢复 `__created`，不删除任务（`__deleted` 只在快照中该实体存在且当前为已删除时清除）；
  - 集合（`__tag` / `__subtask`）按快照成员差集生成 `add` / `remove`；
  - 所有生成 op 的 `hlc.node_id = "srv"`，`counter` 递增，`actor_id = 调用者`，`source = "human"`；
  - 单次恢复生成 op 数上限 5000，超出 `409 restore_too_large`（应改用更近的快照）。
- 审计：服务端在响应 `changes` 中逐条回列生成意图，便于 UI 预览（`dry_run = true` 时不写库）。

## 十、备份接口

### 10.1 `GET /v1/backup/export?workspace_id=`

- 权限：任意成员。
- 响应：`application/zip`，`Content-Disposition: attachment; filename="todo-backup-<workspace_id>-<yyyymmddHHMMSS>.zip"`。
- 压缩包内容：

| 文件 | 内容 |
| --- | --- |
| `manifest.json` | `{protocol_version, generated_at, exporter_user_id, workspace_id, counts, excluded: ["password_hash","refresh_token","jwt_secret"]}` |
| `workspace/ops.jsonl` | 该工作区全部 op（wire 信封 + `seq` + `received_at`），按 `seq` 升序，每行一条 |
| `workspace/projection.json` | 导出时该工作区的投影快照（同 9.3 结构） |

- 生成前执行 `PRAGMA wal_checkpoint(TRUNCATE)`，保证逻辑导出与文件内容一致。
- **绝不包含**：口令哈希、刷新令牌、JWT 密钥、其他用户密码相关字段、其他工作区数据、客户端本地偏好。

### 10.2 `POST /v1/admin/backup`

- 鉴权：`X-Admin-Token: <ADMIN_TOKEN>`（环境变量，未配置时该接口返回 `404`）。
- 行为：WAL checkpoint 后复制数据库文件到 `BACKUP_DIR`，并生成同名 `.jsonl` 逻辑导出（全库 op）。
- 响应 `201` `{ "backup_path": "...", "jsonl_path": "...", "size_bytes": 123456, "created_at": 1762528100000 }`。

## 十一、健康检查

`GET /v1/health`（无需鉴权）：

```json
{
  "status": "ok",
  "protocol_version": "1.0",
  "db_seq": 132,
  "server_hlc": { "physical_ms": 1762528100000, "counter": 2, "node_id": "srv" },
  "writer_queue_depth": 0
}
```

数据库不可写或迁移未完成时返回 `503 { "status": "degraded", ... }`。

## 十二、错误码总表

| `code` | HTTP | retryable | 中文 message |
| --- | --- | --- | --- |
| `invalid_request` | 400 | false | 请求参数不合法 |
| `invalid_op_envelope` | 400 | false | 同步数据格式不正确 |
| `invalid_username` | 400 | false | 用户名需为 3–32 位字母、数字、下划线或短横线 |
| `unknown_entity_type` | 400 | false | 未知的实体类型 |
| `weak_password` | 400 | false | 密码长度需为 8–128 位 |
| `invalid_email` | 400 | false | 邮箱格式不正确 |
| `unknown_field` | 200（逐 op） | false | 未知字段 |
| `invalid_operation` | 200（逐 op） | false | 该字段不支持此操作 |
| `invalid_value` | 200（逐 op） | false | 字段值不合法 |
| `unknown_list` | 200（逐 op） | false | 目标清单不存在 |
| `unknown_user` | 200（逐 op） | false | 目标用户不存在 |
| `entity_not_created` | 200（逐 op） | false | 实体尚未创建 |
| `entity_workspace_mismatch` | 200（逐 op） | false | 实体不属于该工作区 |
| `actor_mismatch` | 200（逐 op） | false | 操作者与登录身份不一致 |
| `hlc_too_far_ahead` | 200（逐 op） | false | 本机时间偏差较大，已按服务器时间校正 |
| `workspace_access_revoked` | 200/403 | false | 权限已变更，此操作未生效 |
| `forbidden` | 200/403 | false | 当前角色无权执行此操作 |
| `unauthorized` | 401 | false | 请先登录 |
| `token_expired` | 401 | true | 登录已过期，正在刷新 |
| `invalid_credentials` | 401 | false | 用户名或密码错误 |
| `invalid_refresh_token` | 401 | false | 登录状态已失效，请重新登录 |
| `workspace_not_found` | 404 | false | 工作区不存在 |
| `member_not_found` | 404 | false | 成员不存在 |
| `account_not_found` | 404 | false | 该账号尚未注册 |
| `snapshot_not_found` | 404 | false | 找不到可用快照 |
| `username_taken` | 409 | false | 该用户名已被占用 |
| `email_taken` | 409 | false | 该邮箱已被占用 |
| `last_owner` | 409 | false | 至少需要保留一个所有者 |
| `cannot_remove_self` | 409 | false | 不能移除自己 |
| `restore_too_large` | 409 | false | 恢复范围过大，请使用更近的快照 |
| `payload_too_large` | 413 | false | 同步数据过大，请分批提交 |
| `rate_limited` | 429 | true | 操作过于频繁，请稍后重试 |
| `internal_error` | 500 | true | 服务端内部错误，请重试 |
| `not_implemented` | 501 | false | 该功能尚未实现 |

## 十三、兼容与演进

- 未知 JSON 字段一律拒绝（`extra = forbid`），保持信封严格；新增字段属于破坏性变更，须升 `/v2`。
- 新增可选字段时，客户端必须先升级到同时兼容旧/新字段的版本，服务端再切换。
- `source` 新增枚举值同样视为破坏性变更（服务端会拒绝未知枚举）。
- 服务端不得因 `source` / `source_meta` 改变裁决；`source_meta` 只落库。
- 服务端拒绝为功能追加特例分支：合并原语只有 LWW 与 OR-Set 两种。

## 十四、Windows 端接入契约（已实现）

实现细节见 [`client-sync-integration.md`](./client-sync-integration.md)。本节保留双方必须遵守的约束。

1. **身份绑定**：登录成功后，Rust 侧把 `preferences.current_user_id` 设为服务端 `user.user_id`，`device_id` 沿用本地 `device_id`；op 的 `actor_id` 一律取该值。
2. **账户切换与本地数据**：登录到不同账户时，客户端先用 `VACUUM INTO` 把本地库备份到 `<数据目录>\backups\`，再清空 `op_log` 与 `sync_*` 元数据并全量拉取；同一账户再次登录保留本地 op 与游标。未做 id 重映射，因此不尝试把本地历史迁移到新账户。
3. **同步循环**（`windows/src-tauri/src/sync/`）：
   - 推送：读取 `sync_state = 'pending'` 的 op，每批最多 100 条；`accepted`/`duplicate` 标记 `synced` 并写 `server_seq`，`rejected` 标记终态并记录 `sync_error`；
   - 拉取：`GET /v1/sync/pull?since_seq=<cursor>&limit=200`，不带 `workspace_id`，拉取调用者全部工作区，直到 `has_more = false`（单轮最多 20 页）；
   - 应用顺序：先 push 再 pull；pull 每页在同一 SQLite 事务内落库并推进游标，随后广播 `projection-updated`；
   - 触发：本地写入 / 手动同步 / 登录通过 `Notify` 立即唤醒；正常间隔取 `sync_interval`（最小 1 分钟）；
   - 退避：网络错误指数退避（1s → 2s → 4s，上限 60s）；`413` 批次减半；`401` 刷新令牌后重试一次。
4. **时钟校正**：收到 `server_hlc` 时按 HLC 规则推进本地 `local_hlc`（只前进），UI 显示黄色条「本机时间偏差较大，已按服务器时间校正」。
5. **权限拒绝**：`workspace_access_revoked` / `forbidden` 对应红条「权限已变更，此操作未生效」，带 `server_hlc`，不自动重试。
6. **令牌存储**：访问/刷新令牌存 Windows 凭据管理器（service `com.todo.windows.session`）；不得落入 SQLite、日志、备份。刷新令牌每次轮换，客户端必须保存新值。
7. **Wire 映射**：按 3.3 表在同步层完成 `camelCase ↔ snake_case` 与 HLC 嵌套/扁平转换，领域层与 IPC 类型不变。
8. **工作区级事件**：拉取到 `workspace.__deleted` / `membership` 变更后，广播 `projection-updated` 且 `workspaceId = null`（与 `windows-frontend-spec.md` 第四节一致）。
9. **清单 / 标签与成员**：清单/标签 CRUD 以普通 op 推送；成员邀请/改角色/移除在已登录时改走 `/v1/workspace/*` REST，成功后立即 pull 一次。
