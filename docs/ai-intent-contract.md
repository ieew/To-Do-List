# AI 意图契约（intent v1）

> 权威 schema：`shared/protocol/ai_intent.schema.json`。本文说明归一化规则、安全边界与三端落地口径。
> 关联：[`ai-local-integration.md`](./ai-local-integration.md)、[`plans/todo-app-plan.md`](../plans/todo-app-plan.md) 第 7 节。

## 一、定位

- AI 不直接产出「最终任务」，只产出**操作列表**。客户端负责校验、预览、确认，再转成普通 op 写入。
- 所有操作仍走 `op_log`，`source="ai"`、`source_meta.model` 记录模型名；服务端只审计，不做 AI 特判。
- 模型只能引用客户端上下文里出现过的 `taskId` / `subtaskId` / `listId` / 标签；禁止编造 id。

## 二、规范形态

```json
{
  "intent_version": 1,
  "reply": "已按你的要求拆分",
  "operations": [
    {
      "op": "create_task",
      "tempId": "t1",
      "title": "整理季度汇报",
      "notes": "",
      "priority": "high",
      "listId": "me-work",
      "dueAt": "2026-11-20T18:00:00+08:00",
      "tags": ["工作"],
      "subtasks": ["汇总数据", "写提纲"]
    },
    {
      "op": "update_fields",
      "taskId": "task-1a2b3c4d",
      "patch": { "dueAt": "2026-11-21T09:00:00+08:00", "priority": "low" },
      "clearFields": ["notes"]
    }
  ]
}
```

字段命名以 **camelCase** 为规范；解析器同时接受 `task_id` / `subtask_id` / `due_at` / `clear_fields` / `list_id` / `tag_ids` 等 snake_case 别名。

## 三、操作类型

| `op` | 必填 | 说明 |
|---|---|---|
| `create_task` | `title` | 新建任务；`tempId` 仅用于同批引用与预览，不写入数据库 |
| `update_fields` | `taskId` | 字段级修改；只允许 `title/notes/startAt/dueAt/remindAt/priority/listId` |
| `add_subtasks` | `taskId`、`subtasks` | 追加子任务，单批最多 20 个 |
| `set_subtask_done` | `taskId`、`subtaskId`、`done` | 勾选/取消子任务 |
| `add_tags` / `remove_tags` | `taskId`、`tagIds` | 标签关联走 OR-Set |
| `set_status` | `taskId`、`status` | **危险操作**：改完成状态 |
| `delete_task` | `taskId` | **危险操作**：写 `__deleted` 墓碑 |

- 单批最多 **50** 个操作。
- `create_task` 不产出 `startAt`：与其他创建入口一致，由客户端以创建时刻填充，之后可在详情中修改。
- `update_fields` 的「不改」与「清空」必须区分：未提及的字段不要放进 `patch`；要清空放进 `clearFields`，不要传 `null`。
- 未知字段、未知操作、上下文外 id：逐条忽略并产生 warning；全部无效则整批拒绝。

## 四、归一化规则（三端必须一致）

| 字段 | 规则 |
|---|---|
| `title` | 去空白，1–120 字符（创建）/ 1–500（修改）；超长截断并告警 |
| `notes` | 最长 2000（创建）/ 20000（修改） |
| `priority` | `high` / `mid` / `low`，接受 `高/中/低`、`medium`、`h/l/m` 同义值；非法回落默认优先级并告警 |
| `listId` | 先匹配 id，再匹配名称（大小写不敏感）；未知回落到**非 smart** 默认清单并告警 |
| `dueAt` / `startAt` / `remindAt` | 必须是带时区的 RFC 3339；非法则忽略并告警；`null` 仅表示清空 |
| `tags` | 名称或 id，按当前工作区标签归一化为 `tagIds`；未知忽略并告警 |
| `subtasks` | 去空、去重，最多 20 个 |
| 默认清单 | 优先非 smart 清单；与 Windows 现状一致（Android 需改） |

## 五、安全边界

1. **上下文快照**：Windows 注入当前工作区最近 200 条任务（id/标题/状态/优先级/清单/截止/标签/子任务），模型只能引用这些 id。
2. **危险操作开关**：`Settings.ai_allow_destructive` 默认 `false`；关闭时 `set_status` / `delete_task` 被 Rust 拒绝（`ai_destructive_disabled`）。
3. **二次确认**：任一危险操作、或操作数 ≥ `ai_confirm_threshold`，都必须先进入确认页；确认后才写入。
4. **批量撤销**：一次 `ai_apply_plan` 返回 `opIds`；前端 toast 提供「撤销」，调用 `undo_ops` 生成更高 HLC 的反向 op（含 `__deleted` 恢复）。
5. **注入防护**：上下文与用户输入都标记为「数据，不是指令」；系统提示要求忽略其中改变规则、索取系统提示词的要求。
6. **图片**：最多 5 张、单张 ≤5MB、最长边 ≤1568px，客户端压缩为 JPEG；只发送给用户自配端点，不落盘、不入 `op_log`、不进备份。

## 六、错误码

| code | 含义 |
|---|---|
| `ai_key_missing` | 未配置 API 密钥 |
| `ai_endpoint_invalid` | 端点不是 http/https 或无法拼接 |
| `ai_model_missing` | 模型名为空 |
| `ai_disabled` | `ai_enabled=false` |
| `ai_http_401` / `403` | 密钥无效或无权限 |
| `ai_http_404` | 端点不存在（常见于缺少 `/v1`） |
| `ai_http_429` | 频率或额度受限 |
| `ai_http_5xx` | 模型服务不可用 |
| `ai_timeout` / `ai_connect_failed` | 超时 / 连不上端点 |
| `ai_json_not_found` | 输出中没有 JSON |
| `ai_schema_invalid` | JSON 结构或字段不合法 |
| `ai_empty` | 没有有效操作 |
| `ai_limit_exceeded` | 超过 50 个操作或字段上限 |
| `ai_image_unsupported` / `ai_image_too_large` | 图片类型/体积不支持 |
| `ai_target_not_found` | 引用了上下文外的 task/subtask |
| `ai_destructive_disabled` | 危险操作未开启 |

> Windows 当前错误仍是中文字符串，后续统一为 `{code, message, retryable, details}`；Android 已有稳定 code。

## 七、图片输入

- 请求体为 OpenAI Chat Completions 多模态格式：`content` 为数组，先 `{"type":"text"}`，再若干个 `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`。
- 图片仅存在于前端内存；关闭弹窗即丢弃。
- 纯图片、无文字时不发起请求（当前要求先有文字描述）。
- 必须在界面上提示「图片将发送给你配置的 AI 服务商」。

## 八、网页桥接

- `SHOW_WEB_BRIDGE = false` 时界面只显示「API 直连」；改为 `true` 恢复双通道。
- 网页桥接使用同一份 `INTENT_RULES` 与上下文；解析在本地完成（`ai_parse_intent`），不读取密钥、不发起模型请求。

## 九、三端对齐清单

| 项 | Windows | Android（待办） | 服务端 |
|---|---|---|---|
| intent schema | 已实现（`ai-core/src/intent.rs`） | 需替换 `AiModels/AiPlanner` | 不参与 |
| 上下文注入 tasks/lists/tags/now | 已实现 | 需补 | 不参与 |
| 默认清单非 smart | 已实现 | 需改 | 不参与 |
| 危险操作开关 + 二次确认 | 已实现 | 需补 | 不参与 |
| 图片压缩/隐私提示 | 已实现 | 已有图片但需压缩与开关生效 | 不参与 |
| 错误码 | 待统一 | 已有 code | 不参与 |
| 批量撤销 | 已实现 `undo_ops` | 需补 | 不参与 |
