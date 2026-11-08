//! 纯领域层：op 模型、HLC、字段级 LWW、集合 OR-Set 与投影 reducer。
//!
//! 约束（见 docs/conventions.md）：
//! - 不 import 数据库、网络、UI 库；
//! - 不读取物理时钟；HLC 一律由调用方传入；
//! - 投影只依赖 op_log 的确定性顺序，不依赖 SQLite 插入顺序。

use chrono::{Local, TimeZone};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, HashMap, HashSet};

/// 混合逻辑时钟。推导的 Ord 顺序固定为 (physical_ms, counter, node_id)。
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "camelCase")]
pub struct Hlc {
    pub physical_ms: i64,
    pub counter: u64,
    pub node_id: String,
}

/// op 信封。字段命名与 docs/todo-app-plan.md 2.1 对齐。
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Op {
    pub op_id: String,
    pub workspace_id: String,
    pub entity_type: String,
    pub entity_id: String,
    pub actor_id: String,
    pub device_id: String,
    pub operation: String,
    pub field: String,
    pub value: Value,
    pub physical_ms: i64,
    pub counter: u64,
    pub node_id: String,
    pub source: String,
    pub model: Option<String>,
}

impl Op {
    pub fn hlc(&self) -> Hlc {
        Hlc {
            physical_ms: self.physical_ms,
            counter: self.counter,
            node_id: self.node_id.clone(),
        }
    }
}

/* ----------------------------- 投影输出 DTO ----------------------------- */

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Subtask {
    pub id: String,
    pub text: String,
    pub done: bool,
    pub order: f64,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub id: String,
    pub title: String,
    pub notes: String,
    pub due_at: Option<String>,
    pub remind_at: Option<String>,
    pub priority: String,
    pub status: String,
    pub list_id: String,
    pub tag_ids: Vec<String>,
    pub subtasks: Vec<Subtask>,
    pub source: String,
    pub created_at: String,
    pub completed_at: Option<String>,
    pub order: f64,
    pub model: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TodoList {
    pub id: String,
    pub name: String,
    pub color: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub smart: Option<bool>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Tag {
    pub id: String,
    pub name: String,
    pub bg: String,
    pub fg: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show_in_sidebar: Option<bool>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Workspace {
    pub id: String,
    pub name: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Member {
    pub id: String,
    pub name: String,
    pub initial: String,
    pub color: String,
    pub role: String,
    pub email: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryDiff {
    pub kind: String,
    pub field: String,
    pub old_text: Option<String>,
    pub new_text: Option<String>,
    pub text: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryOp {
    pub id: String,
    pub task_id: String,
    pub task_title: String,
    pub at: i64,
    pub time_label: String,
    pub actor: String,
    pub source: String,
    pub action: String,
    pub field: Option<String>,
    pub old_value: Option<Value>,
    pub new_value: Option<Value>,
    pub diff: Option<HistoryDiff>,
    pub model: Option<String>,
    pub undoable: bool,
    pub undone: bool,
    pub system: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub theme: String,
    pub density: String,
    pub notifications: bool,
    pub reminder_lead: i64,
    pub dnd_enabled: bool,
    pub default_priority: String,
    pub confirm_delete: bool,
    pub auto_archive: bool,
    pub sync_interval: i64,
    pub server_url: String,
    pub ai_enabled: bool,
    pub ai_endpoint: String,
    pub ai_model: String,
    pub ai_confirm_threshold: i64,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme: "light".into(),
            density: "comfortable".into(),
            notifications: true,
            reminder_lead: 10,
            dnd_enabled: false,
            default_priority: "mid".into(),
            confirm_delete: true,
            auto_archive: false,
            sync_interval: 5,
            server_url: "http://127.0.0.1:8000".into(),
            ai_enabled: true,
            ai_endpoint: "http://127.0.0.1:11434/v1".into(),
            ai_model: "qwen2.5-7b".into(),
            ai_confirm_threshold: 10,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Projection {
    pub active_workspace_id: String,
    pub current_user_id: String,
    pub workspaces: Vec<Workspace>,
    pub members: Vec<Member>,
    pub lists: Vec<TodoList>,
    pub tags: Vec<Tag>,
    pub tasks: Vec<Task>,
    pub history: Vec<HistoryOp>,
}

/* ------------------------------- reducer ------------------------------- */

#[derive(Clone, Debug)]
struct Cell {
    value: Value,
    hlc: Hlc,
    op_id: String,
}

#[derive(Clone, Debug, Default)]
struct ElementState {
    add: Option<(Hlc, String)>,
    remove: Option<(Hlc, String)>,
}

impl ElementState {
    /// OR-Set add-wins：并发时 add 胜出。
    fn alive(&self) -> bool {
        match (&self.add, &self.remove) {
            (None, _) => false,
            (Some(_), None) => true,
            (Some((a, _)), Some((r, _))) => a >= r,
        }
    }
}

#[derive(Clone, Debug)]
struct EntityMeta {
    first_ms: i64,
    source: String,
    model: Option<String>,
}

type FieldKey = (String, String, String);
type EntityKey = (String, String);

#[derive(Default)]
struct State {
    fields: HashMap<FieldKey, Cell>,
    collections: HashMap<FieldKey, BTreeMap<String, ElementState>>,
    meta: HashMap<EntityKey, EntityMeta>,
    order: Vec<Op>,
}

fn field_key(entity_type: &str, entity_id: &str, field: &str) -> FieldKey {
    (entity_type.to_string(), entity_id.to_string(), field.to_string())
}

impl State {
    fn apply(&mut self, op: &Op) {
        let key = field_key(&op.entity_type, &op.entity_id, &op.field);
        let hlc = op.hlc();

        self.meta
            .entry((op.entity_type.clone(), op.entity_id.clone()))
            .and_modify(|meta| {
                if op.physical_ms < meta.first_ms {
                    meta.first_ms = op.physical_ms;
                }
            })
            .or_insert(EntityMeta {
                first_ms: op.physical_ms,
                source: op.source.clone(),
                model: op.model.clone(),
            });

        if op.field == "__created" {
            if let Some(meta) = self
                .meta
                .get_mut(&(op.entity_type.clone(), op.entity_id.clone()))
            {
                meta.source = op.source.clone();
                meta.model = op.model.clone();
            }
        }

        match op.operation.as_str() {
            "add" | "remove" => {
                let element = op
                    .value
                    .as_str()
                    .map(|s| s.to_string())
                    .unwrap_or_else(|| op.value.to_string());
                let bucket = self.collections.entry(key).or_default();
                let state = bucket.entry(element).or_default();
                let slot = if op.operation == "add" {
                    &mut state.add
                } else {
                    &mut state.remove
                };
                let replace = match slot {
                    None => true,
                    Some((existing, existing_op)) => {
                        (&hlc, &op.op_id) > (existing, existing_op)
                    }
                };
                if replace {
                    *slot = Some((hlc, op.op_id.clone()));
                }
            }
            _ => {
                let replace = match self.fields.get(&key) {
                    None => true,
                    Some(existing) => (&hlc, &op.op_id) > (&existing.hlc, &existing.op_id),
                };
                if replace {
                    self.fields.insert(
                        key,
                        Cell {
                            value: op.value.clone(),
                            hlc,
                            op_id: op.op_id.clone(),
                        },
                    );
                }
            }
        }

        self.order.push(op.clone());
    }

    fn text(&self, entity_type: &str, entity_id: &str, field: &str) -> Option<String> {
        self.fields
            .get(&field_key(entity_type, entity_id, field))
            .and_then(|cell| match &cell.value {
                Value::String(s) => Some(s.clone()),
                Value::Null => None,
                other => Some(other.to_string()),
            })
    }

    fn raw(&self, entity_type: &str, entity_id: &str, field: &str) -> Option<&Value> {
        self.fields
            .get(&field_key(entity_type, entity_id, field))
            .map(|cell| &cell.value)
    }

    fn bool(&self, entity_type: &str, entity_id: &str, field: &str) -> bool {
        self.raw(entity_type, entity_id, field)
            .and_then(|v| v.as_bool())
            .unwrap_or(false)
    }

    fn number(&self, entity_type: &str, entity_id: &str, field: &str, fallback: f64) -> f64 {
        self.raw(entity_type, entity_id, field)
            .and_then(|v| v.as_f64())
            .unwrap_or(fallback)
    }

    fn deleted(&self, entity_type: &str, entity_id: &str) -> bool {
        self.bool(entity_type, entity_id, "__deleted")
    }

    fn elements(&self, entity_type: &str, entity_id: &str, field: &str) -> Vec<String> {
        self.collections
            .get(&field_key(entity_type, entity_id, field))
            .map(|bucket| {
                bucket
                    .iter()
                    .filter(|(_, state)| state.alive())
                    .map(|(element, _)| element.clone())
                    .collect()
            })
            .unwrap_or_default()
    }

    fn meta(&self, entity_type: &str, entity_id: &str) -> Option<&EntityMeta> {
        self.meta
            .get(&(entity_type.to_string(), entity_id.to_string()))
    }
}

fn iso_from_ms(ms: i64) -> String {
    Local
        .timestamp_millis_opt(ms)
        .single()
        .map(|dt| dt.to_rfc3339())
        .unwrap_or_default()
}

fn time_label(ms: i64, now: i64) -> String {
    let minutes = (now - ms).max(0) / 60_000;
    if minutes < 1 {
        "刚刚".to_string()
    } else if minutes < 60 {
        format!("{minutes} 分钟前")
    } else {
        Local
            .timestamp_millis_opt(ms)
            .single()
            .map(|dt| dt.format("%m月%d日 %H:%M").to_string())
            .unwrap_or_else(|| "较早".to_string())
    }
}

fn value_text(value: &Value) -> String {
    match value {
        Value::Null => "空".to_string(),
        Value::Bool(true) => "是".to_string(),
        Value::Bool(false) => "否".to_string(),
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

fn priority_text(value: &str) -> String {
    match value {
        "high" => "高".into(),
        "mid" => "中".into(),
        "low" => "低".into(),
        other => other.into(),
    }
}

fn status_text(value: &str) -> String {
    match value {
        "done" => "已完成".into(),
        "todo" => "未完成".into(),
        other => other.into(),
    }
}

fn action_of(op: &Op, state: &State) -> String {
    if op.field == "__created" {
        return match op.entity_type.as_str() {
            "workspace" => "创建了工作区".into(),
            "list" => "创建了清单".into(),
            "tag" => "创建了标签".into(),
            _ => "创建了任务".into(),
        };
    }
    if op.field == "__deleted" {
        return match op.entity_type.as_str() {
            "workspace" => "删除了工作区".into(),
            "list" => "删除了清单".into(),
            "tag" => "删除了标签".into(),
            _ => "删除了任务".into(),
        };
    }
    if op.field == "__undo_of" {
        return "生成了撤销 op".into();
    }
    if op.entity_type == "membership" {
        return match op.field.as_str() {
            "__role" => "变更了成员角色".into(),
            "__removed" => "移除了成员".into(),
            _ => "更新了成员权限".into(),
        };
    }
    if op.entity_type == "workspace" {
        return "重命名了工作区".into();
    }
    if op.operation == "add" {
        return match op.field.as_str() {
            "__tag" => "添加了标签".into(),
            "__subtask" => "添加了子任务".into(),
            _ => "添加了内容".into(),
        };
    }
    if op.operation == "remove" {
        return match op.field.as_str() {
            "__tag" => "移除了标签".into(),
            "__subtask" => "删除了子任务".into(),
            _ => "移除了内容".into(),
        };
    }
    if let Some(rest) = op.field.strip_prefix("__subtask:") {
        if rest.ends_with(":done") {
            let done = op.value.as_bool().unwrap_or(false);
            return if done {
                "完成了子任务".into()
            } else {
                "恢复子任务".into()
            };
        }
        if rest.ends_with(":text") {
            return "修改了子任务".into();
        }
    }
    match op.field.as_str() {
        "title" => "修改了标题".into(),
        "notes" => "修改了备注".into(),
        "due_at" => "修改了截止时间".into(),
        "remind_at" => "修改了提醒时间".into(),
        "priority" => "修改了优先级".into(),
        "status" => {
            if state.text(&op.entity_type, &op.entity_id, "status").as_deref() == Some("done") {
                "完成了任务".into()
            } else {
                "恢复为待办".into()
            }
        }
        "list_id" => "移动了清单".into(),
        _ => "更新了任务".into(),
    }
}

fn display_value(field: &str, value: &Value) -> String {
    match field {
        "priority" => priority_text(&value_text(value)),
        "status" => status_text(&value_text(value)),
        "list_id" => value_text(value),
        _ => value_text(value),
    }
}

fn build_history(
    state: &State,
    active_workspace: &str,
    task_ids: &HashSet<String>,
    task_titles: &HashMap<String, String>,
    user_names: &HashMap<String, String>,
    current_user: &str,
    now: i64,
) -> Vec<HistoryOp> {
    let undone: HashSet<String> = state
        .order
        .iter()
        .filter(|op| op.field == "__undo_of")
        .filter_map(|op| op.value.as_str().map(|s| s.to_string()))
        .collect();

    let mut last_values: HashMap<FieldKey, Value> = HashMap::new();
    let mut rows: Vec<HistoryOp> = Vec::new();

    let mut ordered = state.order.clone();
    ordered.sort_by(|a, b| a.hlc().cmp(&b.hlc()));

    for op in ordered {
        if op.workspace_id != active_workspace {
            continue;
        }
        let is_task = op.entity_type == "task";
        if is_task && !task_ids.contains(&op.entity_id) {
            continue;
        }
        if op.entity_type == "user" {
            continue;
        }
        if op.field == "__workspace" {
            continue;
        }

        let key = field_key(&op.entity_type, &op.entity_id, &op.field);
        let old_value = last_values.get(&key).cloned();
        last_values.insert(key, op.value.clone());

        let title = task_titles
            .get(&op.entity_id)
            .cloned()
            .or_else(|| state.text("workspace", &op.entity_id, "name"))
            .unwrap_or_else(|| "工作区".to_string());

        let actor = if op.source == "ai" {
            "AI 助手".to_string()
        } else if op.actor_id == current_user {
            "我".to_string()
        } else {
            user_names
                .get(&op.actor_id)
                .cloned()
                .unwrap_or_else(|| op.actor_id.clone())
        };

        let kind = match op.operation.as_str() {
            "add" => "add",
            "remove" => "remove",
            _ if old_value.is_none() => "set",
            _ => "change",
        };

        let new_text = if op.operation == "add" || op.operation == "remove" {
            Some(value_text(&op.value))
        } else {
            Some(display_value(&op.field, &op.value))
        };
        let old_text = old_value
            .as_ref()
            .map(|value| display_value(&op.field, value));

        let field_label = match op.field.as_str() {
            "__tag" => "__tag",
            "__subtask" => "__subtask",
            "__created" => "title",
            "__deleted" => "__deleted",
            other => other,
        };

        let prefix = match op.operation.as_str() {
            "add" => format!("{field_label} += "),
            "remove" => format!("{field_label} -= "),
            _ if old_value.is_none() => format!("{field_label} = "),
            _ => format!("{field_label}: "),
        };

        let undoable = op.operation == "set"
            && op.field != "__created"
            && op.field != "__deleted"
            && op.source != "system"
            && op.field != "__undo_of";

        rows.push(HistoryOp {
            id: op.op_id.clone(),
            task_id: op.entity_id.clone(),
            task_title: title,
            at: op.physical_ms,
            time_label: time_label(op.physical_ms, now),
            actor,
            source: op.source.clone(),
            action: action_of(&op, state),
            field: Some(op.field.clone()),
            old_value,
            new_value: Some(op.value.clone()),
            diff: Some(HistoryDiff {
                kind: kind.to_string(),
                field: op.field.clone(),
                old_text,
                new_text,
                text: Some(prefix),
            }),
            model: op.model.clone(),
            undoable,
            undone: undone.contains(&op.op_id),
            system: op.source == "system",
        });
    }

    rows.sort_by(|a, b| b.at.cmp(&a.at).then_with(|| b.id.cmp(&a.id)));
    rows
}

impl Projection {
    pub fn build(
        ops: Vec<Op>,
        requested_workspace: Option<&str>,
        current_user: &str,
        settings: Settings,
        ai_key_configured: bool,
    ) -> Self {
        let mut state = State::default();
        let mut sorted = ops;
        sorted.sort_by(|a, b| a.hlc().cmp(&b.hlc()).then_with(|| a.op_id.cmp(&b.op_id)));
        for op in &sorted {
            state.apply(op);
        }

        // 工作区：当前用户拥有有效 membership 的 workspace。
        let mut workspaces: Vec<Workspace> = state
            .meta
            .keys()
            .filter(|(entity_type, _)| entity_type == "workspace")
            .map(|(_, id)| id.clone())
            .collect::<HashSet<_>>()
            .into_iter()
            .filter(|id| !state.deleted("workspace", id))
            .filter(|id| {
                let membership_id = format!("{id}:{current_user}");
                !state.bool("membership", &membership_id, "__removed")
                    && state
                        .text("membership", &membership_id, "__role")
                        .is_some()
            })
            .map(|id| Workspace {
                name: state
                    .text("workspace", &id, "name")
                    .unwrap_or_else(|| "未命名空间".into()),
                id,
            })
            .collect();
        workspaces.sort_by(|a, b| a.name.cmp(&b.name));

        let active_workspace = requested_workspace
            .filter(|id| workspaces.iter().any(|ws| ws.id == *id))
            .map(|id| id.to_string())
            .or_else(|| workspaces.first().map(|ws| ws.id.clone()))
            .unwrap_or_default();

        // 清单：按 workspace_id 过滤。
        let mut lists: Vec<TodoList> = Vec::new();
        let mut list_workspace: HashMap<String, String> = HashMap::new();
        for (entity_type, id) in state.meta.keys() {
            if entity_type != "list" || state.deleted("list", id) {
                continue;
            }
            let workspace = state
                .text("list", id, "workspace_id")
                .unwrap_or_else(|| active_workspace.clone());
            list_workspace.insert(id.clone(), workspace.clone());
            if workspace != active_workspace {
                continue;
            }
            lists.push(TodoList {
                id: id.clone(),
                name: state.text("list", id, "name").unwrap_or_else(|| "未命名清单".into()),
                color: state
                    .text("list", id, "color")
                    .unwrap_or_else(|| "#3B82F6".into()),
                smart: Some(id == "today"),
            });
        }
        lists.sort_by(|a, b| {
            state
                .number("list", &a.id, "order", 0.0)
                .partial_cmp(&state.number("list", &b.id, "order", 0.0))
                .unwrap_or(std::cmp::Ordering::Equal)
                .then_with(|| a.id.cmp(&b.id))
        });

        // 标签：按 workspace_id 过滤。
        let mut tags: Vec<Tag> = Vec::new();
        for (entity_type, id) in state.meta.keys() {
            if entity_type != "tag" || state.deleted("tag", id) {
                continue;
            }
            let workspace = state
                .text("tag", id, "workspace_id")
                .unwrap_or_else(|| active_workspace.clone());
            if workspace != active_workspace {
                continue;
            }
            tags.push(Tag {
                id: id.clone(),
                name: state.text("tag", id, "name").unwrap_or_else(|| "标签".into()),
                bg: state.text("tag", id, "bg").unwrap_or_else(|| "#EFF6FF".into()),
                fg: state.text("tag", id, "fg").unwrap_or_else(|| "#2563EB".into()),
                show_in_sidebar: state.raw("tag", id, "show_in_sidebar").and_then(|v| v.as_bool()),
            });
        }
        tags.sort_by(|a, b| a.name.cmp(&b.name));

        // 成员：membership 投影 + user 资料。
        let mut members: Vec<Member> = Vec::new();
        for (entity_type, entity_id) in state.meta.keys() {
            if entity_type != "membership" {
                continue;
            }
            let Some((workspace, user_id)) = entity_id.split_once(':') else {
                continue;
            };
            if workspace != active_workspace.as_str() {
                continue;
            }
            if state.bool("membership", entity_id, "__removed") {
                continue;
            }
            let Some(role) = state.text("membership", entity_id, "__role") else {
                continue;
            };
            members.push(Member {
                id: user_id.to_string(),
                name: state
                    .text("user", user_id, "name")
                    .unwrap_or_else(|| user_id.to_string()),
                initial: state
                    .text("user", user_id, "initial")
                    .unwrap_or_else(|| "用".into()),
                color: state
                    .text("user", user_id, "color")
                    .unwrap_or_else(|| "#3B82F6".into()),
                role,
                email: state
                    .text("user", user_id, "email")
                    .unwrap_or_else(|| format!("{user_id}@todo.local")),
            });
        }
        let role_rank = |role: &str| match role {
            "owner" => 0,
            "editor" => 1,
            _ => 2,
        };
        members.sort_by(|a, b| {
            let a_me = if a.id == current_user { 0 } else { 1 };
            let b_me = if b.id == current_user { 0 } else { 1 };
            a_me.cmp(&b_me)
                .then_with(|| role_rank(&a.role).cmp(&role_rank(&b.role)))
                .then_with(|| a.name.cmp(&b.name))
        });

        let user_names: HashMap<String, String> = state
            .meta
            .keys()
            .filter(|(entity_type, _)| entity_type == "user")
            .map(|(_, id)| {
                (
                    id.clone(),
                    state
                        .text("user", id, "name")
                        .unwrap_or_else(|| id.clone()),
                )
            })
            .collect();

        // 任务：通过 list_id 反查工作区。
        let mut tasks: Vec<Task> = Vec::new();
        for (entity_type, id) in state.meta.keys() {
            if entity_type != "task" || state.deleted("task", id) {
                continue;
            }
            let list_id = state.text("task", id, "list_id").unwrap_or_default();
            if list_workspace.get(&list_id).map(|w| w.as_str()) != Some(active_workspace.as_str()) {
                continue;
            }

            let meta = state.meta("task", id);
            let status = state
                .text("task", id, "status")
                .unwrap_or_else(|| "todo".into());
            let status_ms = state
                .fields
                .get(&field_key("task", id, "status"))
                .map(|cell| cell.hlc.physical_ms);
            let created_at = state
                .text("task", id, "__created")
                .unwrap_or_else(|| iso_from_ms(meta.map(|m| m.first_ms).unwrap_or(0)));

            let mut subtasks: Vec<Subtask> = state
                .elements("task", id, "__subtask")
                .into_iter()
                .map(|subtask_id| Subtask {
                    text: state
                        .text("task", id, &format!("__subtask:{subtask_id}:text"))
                        .unwrap_or_else(|| "子任务".into()),
                    done: state.bool("task", id, &format!("__subtask:{subtask_id}:done")),
                    order: state.number("task", id, &format!("__subtask:{subtask_id}:order"), 0.0),
                    id: subtask_id,
                })
                .collect();
            subtasks.sort_by(|a, b| {
                a.order
                    .partial_cmp(&b.order)
                    .unwrap_or(std::cmp::Ordering::Equal)
                    .then_with(|| a.id.cmp(&b.id))
            });

            tasks.push(Task {
                id: id.clone(),
                title: state
                    .text("task", id, "title")
                    .unwrap_or_else(|| "未命名任务".into()),
                notes: state.text("task", id, "notes").unwrap_or_default(),
                due_at: state.text("task", id, "due_at"),
                remind_at: state.text("task", id, "remind_at"),
                priority: state
                    .text("task", id, "priority")
                    .unwrap_or_else(|| "mid".into()),
                status: status.clone(),
                list_id,
                tag_ids: state.elements("task", id, "__tag"),
                subtasks,
                source: meta.map(|m| m.source.clone()).unwrap_or_else(|| "human".into()),
                created_at,
                completed_at: if status == "done" {
                    status_ms.map(iso_from_ms)
                } else {
                    None
                },
                order: state.number("task", id, "order", 0.0),
                model: meta.and_then(|m| m.model.clone()),
            });
        }
        tasks.sort_by(|a, b| {
            let a_status = if a.status == "done" { 1 } else { 0 };
            let b_status = if b.status == "done" { 1 } else { 0 };
            a_status
                .cmp(&b_status)
                .then_with(|| {
                    a.order
                        .partial_cmp(&b.order)
                        .unwrap_or(std::cmp::Ordering::Equal)
                })
                .then_with(|| a.id.cmp(&b.id))
        });

        let task_ids: HashSet<String> = tasks.iter().map(|task| task.id.clone()).collect();
        let task_titles: HashMap<String, String> = tasks
            .iter()
            .map(|task| (task.id.clone(), task.title.clone()))
            .collect();
        let now = Local::now().timestamp_millis();
        let history = build_history(
            &state,
            &active_workspace,
            &task_ids,
            &task_titles,
            &user_names,
            current_user,
            now,
        );

        let _ = settings;
        let _ = ai_key_configured;

        Self {
            active_workspace_id: active_workspace,
            current_user_id: current_user.to_string(),
            workspaces,
            members,
            lists,
            tags,
            tasks,
            history,
        }
    }
}
