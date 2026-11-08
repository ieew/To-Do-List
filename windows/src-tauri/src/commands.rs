//! Tauri 命令层：只做参数校验、op 追加与投影读取，不实现合并裁决。
//! 合并与投影逻辑全部在 core_domain；SQLite 访问全部在 storage。

use crate::core_domain::{HistoryOp, Op, Projection, Settings, Workspace};
use crate::storage;
use crate::AppState;
use anyhow::{anyhow, Result as AnyResult};
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tauri::{AppHandle, Emitter, State};
use uuid::Uuid;

const KEYRING_SERVICE: &str = "com.todo.windows.ai";
const KEYRING_USER: &str = "default";

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BootstrapResponse {
    pub projection: Projection,
    pub settings: Settings,
    pub ai_key_configured: bool,
    pub device_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateTaskInput {
    pub title: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub due_at: Option<String>,
    #[serde(default)]
    pub remind_at: Option<String>,
    #[serde(default)]
    pub priority: Option<String>,
    #[serde(default)]
    pub list_id: Option<String>,
    #[serde(default)]
    pub tag_ids: Vec<String>,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default)]
    pub subtasks: Vec<String>,
    #[serde(default)]
    pub model: Option<String>,
}

fn with_db<T>(state: &AppState, f: impl FnOnce(&Connection) -> AnyResult<T>) -> Result<T, String> {
    let guard = state
        .db
        .lock()
        .map_err(|_| "数据库连接锁失败，请重试".to_string())?;
    f(&guard).map_err(|error| error.to_string())
}

fn emit_projection(app: &AppHandle, workspace_id: &str) {
    let _ = app.emit(
        "projection-updated",
        json!({ "workspaceId": workspace_id }),
    );
}

#[allow(clippy::too_many_arguments)]
fn append(
    conn: &Connection,
    device: &str,
    actor: &str,
    workspace: &str,
    entity_type: &str,
    entity_id: &str,
    operation: &str,
    field: &str,
    value: Value,
    source: &str,
    model: Option<&str>,
) -> AnyResult<()> {
    let hlc = storage::next_hlc(conn)?;
    storage::insert_op(
        conn,
        &Op {
            op_id: Uuid::new_v4().to_string(),
            workspace_id: workspace.to_string(),
            entity_type: entity_type.to_string(),
            entity_id: entity_id.to_string(),
            actor_id: actor.to_string(),
            device_id: device.to_string(),
            operation: operation.to_string(),
            field: field.to_string(),
            value,
            physical_ms: hlc.physical_ms,
            counter: hlc.counter,
            node_id: hlc.node_id,
            source: source.to_string(),
            model: model.map(|m| m.to_string()),
        },
    )?;
    Ok(())
}

fn active_workspace(conn: &Connection) -> String {
    storage::active_workspace_id(conn).unwrap_or_else(|| "ws-me".to_string())
}

fn build_projection(conn: &Connection, workspace_id: &str) -> AnyResult<Projection> {
    let ops = storage::load_ops(conn)?;
    let settings = storage::load_settings(conn);
    Ok(Projection::build(
        ops,
        Some(workspace_id),
        &storage::current_user_id(conn),
        settings,
        ai_key_configured(),
    ))
}

fn default_list_id(conn: &Connection, workspace_id: &str) -> AnyResult<String> {
    let projection = build_projection(conn, workspace_id)?;
    projection
        .lists
        .iter()
        .find(|list| list.smart == Some(true))
        .or_else(|| projection.lists.first())
        .map(|list| list.id.clone())
        .ok_or_else(|| anyhow!("当前工作区还没有可用清单"))
}

fn next_task_order(conn: &Connection, workspace_id: &str) -> AnyResult<f64> {
    let projection = build_projection(conn, workspace_id)?;
    let max = projection
        .tasks
        .iter()
        .map(|task| task.order)
        .fold(-1.0_f64, f64::max);
    Ok(max + 1.0)
}

fn map_field(field: &str) -> &str {
    match field {
        "dueAt" => "due_at",
        "remindAt" => "remind_at",
        "listId" => "list_id",
        "workspaceId" => "workspace_id",
        other => other,
    }
}

/* ------------------------------- 启动 ------------------------------- */

#[tauri::command]
pub fn bootstrap(
    state: State<'_, AppState>,
    workspace_id: Option<String>,
) -> Result<BootstrapResponse, String> {
    with_db(&state, |conn| {
        let requested = workspace_id.or_else(|| storage::active_workspace_id(conn));
        let ops = storage::load_ops(conn)?;
        let settings = storage::load_settings(conn);
        let projection = Projection::build(
            ops,
            requested.as_deref(),
            &storage::current_user_id(conn),
            settings.clone(),
            false,
        );
        Ok(BootstrapResponse {
            projection,
            settings,
            ai_key_configured: ai_key_configured(),
            device_id: storage::device_id(conn)?,
        })
    })
}

#[tauri::command]
pub fn set_active_workspace(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
) -> Result<(), String> {
    with_db(&state, |conn| {
        storage::pref_set(conn, "active_workspace_id", &workspace_id)
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

/* ----------------------------- 工作区 ----------------------------- */

#[tauri::command]
pub fn create_workspace(
    app: AppHandle,
    state: State<'_, AppState>,
    name: String,
) -> Result<Workspace, String> {
    let clean = name.trim();
    if clean.is_empty() {
        return Err("工作区名称不能为空".to_string());
    }
    let workspace_id = format!("ws-{}", &Uuid::new_v4().to_string()[..8]);
    let created = with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        append(
            conn, &device, &actor, &workspace_id, "workspace", &workspace_id, "set",
            "__created", json!(chrono::Local::now().to_rfc3339()), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "workspace", &workspace_id, "set",
            "name", json!(clean), "human", None,
        )?;
        let membership_id = format!("{workspace_id}:{actor}");
        append(
            conn, &device, &actor, &workspace_id, "membership", &membership_id, "set",
            "__role", json!("owner"), "human", None,
        )?;
        let list_id = format!("{workspace_id}-today");
        append(
            conn, &device, &actor, &workspace_id, "list", &list_id, "set",
            "__created", json!(chrono::Local::now().to_rfc3339()), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "list", &list_id, "set",
            "workspace_id", json!(workspace_id), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "list", &list_id, "set",
            "name", json!("今天"), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "list", &list_id, "set",
            "color", json!("#3B82F6"), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "list", &list_id, "set",
            "order", json!(0.0), "human", None,
        )?;
        storage::pref_set(conn, "active_workspace_id", &workspace_id)?;
        Ok(Workspace {
            id: workspace_id.clone(),
            name: clean.to_string(),
        })
    })?;
    emit_projection(&app, &workspace_id);
    Ok(created)
}

#[tauri::command]
pub fn rename_workspace(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
    name: String,
) -> Result<(), String> {
    let clean = name.trim();
    if clean.is_empty() {
        return Err("工作区名称不能为空".to_string());
    }
    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        append(
            conn, &device, &actor, &workspace_id, "workspace", &workspace_id, "set",
            "name", json!(clean), "human", None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

/* ------------------------------- 任务 ------------------------------- */

#[tauri::command]
pub fn create_task(
    app: AppHandle,
    state: State<'_, AppState>,
    input: CreateTaskInput,
) -> Result<String, String> {
    let title = input.title.trim().to_string();
    if title.is_empty() {
        return Err("任务标题不能为空".to_string());
    }
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    let task_id = format!("task-{}", &Uuid::new_v4().to_string()[..8]);

    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        let source = input.source.as_deref().unwrap_or("human");
        let list_id = match input.list_id.clone() {
            Some(id) if !id.is_empty() => id,
            _ => default_list_id(conn, &workspace_id)?,
        };
        let order = next_task_order(conn, &workspace_id)?;

        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "set", "__created",
            json!(chrono::Local::now().to_rfc3339()), source, input.model.as_deref(),
        )?;
        for (field, value) in [
            ("title", json!(title)),
            ("notes", json!(input.notes.clone())),
            ("due_at", json!(input.due_at.clone())),
            ("remind_at", json!(input.remind_at.clone())),
            (
                "priority",
                json!(input.priority.clone().unwrap_or_else(|| "mid".to_string())),
            ),
            ("status", json!("todo")),
            ("list_id", json!(list_id)),
            ("order", json!(order)),
        ] {
            append(
                conn, &device, &actor, &workspace_id, "task", &task_id, "set", field,
                value, source, input.model.as_deref(),
            )?;
        }

        for tag_id in &input.tag_ids {
            append(
                conn, &device, &actor, &workspace_id, "task", &task_id, "add", "__tag",
                json!(tag_id), source, input.model.as_deref(),
            )?;
        }
        for (index, text) in input.subtasks.iter().enumerate() {
            let sub_id = format!("sub-{}", &Uuid::new_v4().to_string()[..8]);
            append(
                conn, &device, &actor, &workspace_id, "task", &task_id, "add", "__subtask",
                json!(sub_id), "human", None,
            )?;
            append(
                conn, &device, &actor, &workspace_id, "task", &task_id, "set",
                &format!("__subtask:{sub_id}:text"), json!(text), "human", None,
            )?;
            append(
                conn, &device, &actor, &workspace_id, "task", &task_id, "set",
                &format!("__subtask:{sub_id}:done"), json!(false), "human", None,
            )?;
            append(
                conn, &device, &actor, &workspace_id, "task", &task_id, "set",
                &format!("__subtask:{sub_id}:order"), json!(index as f64), "human", None,
            )?;
        }
        Ok(())
    })?;

    emit_projection(&app, &workspace_id);
    Ok(task_id)
}

#[tauri::command]
pub fn update_task_field(
    app: AppHandle,
    state: State<'_, AppState>,
    task_id: String,
    field: String,
    value: Value,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        let projection = build_projection(conn, &workspace_id)?;
        let task = projection
            .tasks
            .iter()
            .find(|item| item.id == task_id)
            .ok_or_else(|| anyhow!("任务不存在或不属于当前工作区"))?;

        if field == "tagIds" {
            let next: Vec<String> = value
                .as_array()
                .map(|items| {
                    items
                        .iter()
                        .filter_map(|item| item.as_str().map(|s| s.to_string()))
                        .collect()
                })
                .unwrap_or_default();
            for tag_id in next.iter().filter(|id| !task.tag_ids.contains(id)) {
                append(
                    conn, &device, &actor, &workspace_id, "task", &task_id, "add", "__tag",
                    json!(tag_id), "human", None,
                )?;
            }
            for tag_id in task.tag_ids.iter().filter(|id| !next.contains(id)) {
                append(
                    conn, &device, &actor, &workspace_id, "task", &task_id, "remove", "__tag",
                    json!(tag_id), "human", None,
                )?;
            }
            return Ok(());
        }

        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "set",
            map_field(&field), value, "human", None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn delete_task(
    app: AppHandle,
    state: State<'_, AppState>,
    task_id: String,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "set",
            "__deleted", json!(true), "human", None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn reorder_task(
    app: AppHandle,
    state: State<'_, AppState>,
    task_id: String,
    target_id: String,
) -> Result<(), String> {
    if task_id == target_id {
        return Ok(());
    }
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        let mut tasks = projection.tasks;
        let from = tasks
            .iter()
            .position(|task| task.id == task_id)
            .ok_or_else(|| anyhow!("未找到被拖拽任务"))?;
        let to = tasks
            .iter()
            .position(|task| task.id == target_id)
            .ok_or_else(|| anyhow!("未找到目标任务"))?;
        let moved = tasks.remove(from);
        let insert_at = to.min(tasks.len());
        tasks.insert(insert_at, moved);

        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        for (index, task) in tasks.iter().enumerate() {
            if (task.order - index as f64).abs() > f64::EPSILON {
                append(
                    conn, &device, &actor, &workspace_id, "task", &task.id, "set",
                    "order", json!(index as f64), "human", None,
                )?;
            }
        }
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

/* ------------------------------- 子任务 ------------------------------- */

#[tauri::command]
pub fn add_subtask(
    app: AppHandle,
    state: State<'_, AppState>,
    task_id: String,
    text: String,
) -> Result<String, String> {
    let clean = text.trim().to_string();
    if clean.is_empty() {
        return Err("子任务内容不能为空".to_string());
    }
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    let subtask_id = format!("sub-{}", &Uuid::new_v4().to_string()[..8]);
    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        let projection = build_projection(conn, &workspace_id)?;
        let task = projection
            .tasks
            .iter()
            .find(|item| item.id == task_id)
            .ok_or_else(|| anyhow!("任务不存在"))?;
        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "add", "__subtask",
            json!(subtask_id), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "set",
            &format!("__subtask:{subtask_id}:text"), json!(clean), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "set",
            &format!("__subtask:{subtask_id}:done"), json!(false), "human", None,
        )?;
        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "set",
            &format!("__subtask:{subtask_id}:order"), json!(task.subtasks.len() as f64), "human", None,
        )?;
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(subtask_id)
}

#[tauri::command]
pub fn toggle_subtask(
    app: AppHandle,
    state: State<'_, AppState>,
    task_id: String,
    subtask_id: String,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        let task = projection
            .tasks
            .iter()
            .find(|item| item.id == task_id)
            .ok_or_else(|| anyhow!("任务不存在"))?;
        let subtask = task
            .subtasks
            .iter()
            .find(|item| item.id == subtask_id)
            .ok_or_else(|| anyhow!("子任务不存在"))?;
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "set",
            &format!("__subtask:{subtask_id}:done"), json!(!subtask.done), "human", None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn remove_subtask(
    app: AppHandle,
    state: State<'_, AppState>,
    task_id: String,
    subtask_id: String,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        append(
            conn, &device, &actor, &workspace_id, "task", &task_id, "remove", "__subtask",
            json!(subtask_id), "human", None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

/* ------------------------------- 历史 ------------------------------- */

#[tauri::command]
pub fn get_history(
    state: State<'_, AppState>,
    workspace_id: Option<String>,
    task_id: Option<String>,
) -> Result<Vec<HistoryOp>, String> {
    with_db(&state, |conn| {
        let workspace = workspace_id.unwrap_or_else(|| active_workspace(conn));
        let projection = build_projection(conn, &workspace)?;
        Ok(match task_id {
            Some(id) => projection
                .history
                .into_iter()
                .filter(|op| op.task_id == id)
                .collect(),
            None => projection.history,
        })
    })
}

#[tauri::command]
pub fn undo_op(
    app: AppHandle,
    state: State<'_, AppState>,
    op_id: String,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let ops = storage::load_ops(conn)?;
        let target = ops
            .iter()
            .find(|op| op.op_id == op_id)
            .cloned()
            .ok_or_else(|| anyhow!("op 不存在"))?;
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);

        if target.operation == "add" {
            append(
                conn, &device, &actor, &workspace_id, &target.entity_type, &target.entity_id,
                "remove", &target.field, target.value.clone(), "human", None,
            )?;
        } else if target.operation == "remove" {
            append(
                conn, &device, &actor, &workspace_id, &target.entity_type, &target.entity_id,
                "add", &target.field, target.value.clone(), "human", None,
            )?;
        } else if target.field == "__created" {
            append(
                conn, &device, &actor, &workspace_id, &target.entity_type, &target.entity_id,
                "set", "__deleted", json!(true), "human", None,
            )?;
        } else {
            let previous = ops
                .iter()
                .filter(|op| {
                    op.entity_type == target.entity_type
                        && op.entity_id == target.entity_id
                        && op.field == target.field
                        && op.operation == "set"
                        && op.hlc() < target.hlc()
                })
                .max_by(|a, b| a.hlc().cmp(&b.hlc()))
                .map(|op| op.value.clone())
                .unwrap_or(Value::Null);
            append(
                conn, &device, &actor, &workspace_id, &target.entity_type, &target.entity_id,
                "set", &target.field, previous, "human", None,
            )?;
        }

        append(
            conn, &device, &actor, &workspace_id, &target.entity_type, &target.entity_id,
            "set", "__undo_of", json!(target.op_id), "human", None,
        )?;
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

/* ------------------------------- 成员 ------------------------------- */

#[tauri::command]
pub fn update_member_role(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
    user_id: String,
    role: String,
) -> Result<(), String> {
    if !matches!(role.as_str(), "owner" | "editor" | "viewer") {
        return Err("非法角色".to_string());
    }
    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        let membership_id = format!("{workspace_id}:{user_id}");
        append(
            conn, &device, &actor, &workspace_id, "membership", &membership_id, "set",
            "__role", json!(role), "human", None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn remove_member(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
    user_id: String,
) -> Result<(), String> {
    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        if user_id == actor {
            return Err(anyhow!("不能移除自己"));
        }
        let membership_id = format!("{workspace_id}:{user_id}");
        append(
            conn, &device, &actor, &workspace_id, "membership", &membership_id, "set",
            "__removed", json!(true), "human", None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn invite_member(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
    name: String,
    email: String,
    role: String,
) -> Result<String, String> {
    let clean_name = name.trim().to_string();
    if clean_name.is_empty() {
        return Err("成员名称不能为空".to_string());
    }
    if !matches!(role.as_str(), "owner" | "editor" | "viewer") {
        return Err("非法角色".to_string());
    }
    let user_id = format!("u-{}", &Uuid::new_v4().to_string()[..8]);
    let initial = clean_name
        .chars()
        .next()
        .map(|c| c.to_string())
        .unwrap_or_else(|| "用".to_string());
    let palette = ["#3B82F6", "#10B981", "#8B5CF6", "#F59E0B", "#EC4899"];
    let color = palette[clean_name.len() % palette.len()];

    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        for (field, value) in [
            ("name", json!(clean_name)),
            ("initial", json!(initial)),
            ("color", json!(color)),
            ("email", json!(email)),
        ] {
            append(
                conn, &device, &actor, &workspace_id, "user", &user_id, "set", field,
                value, "human", None,
            )?;
        }
        let membership_id = format!("{workspace_id}:{user_id}");
        append(
            conn, &device, &actor, &workspace_id, "membership", &membership_id, "set",
            "__role", json!(role), "human", None,
        )?;
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(user_id)
}

/* ------------------------------- 设置 ------------------------------- */

#[tauri::command]
pub fn save_settings(
    state: State<'_, AppState>,
    settings: Settings,
) -> Result<(), String> {
    with_db(&state, |conn| storage::save_settings(conn, &settings))
}

/* ----------------------------- AI 密钥 ----------------------------- */

fn keyring_entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER)
        .map_err(|error| format!("无法访问系统凭据管理器: {error}"))
}

fn ai_key_configured() -> bool {
    keyring_entry()
        .and_then(|entry| entry.get_password().map_err(|error| error.to_string()))
        .map(|key| !key.is_empty())
        .unwrap_or(false)
}

#[tauri::command]
pub fn ai_key_status() -> bool {
    ai_key_configured()
}

#[tauri::command]
pub fn set_ai_key(state: State<'_, AppState>, api_key: String) -> Result<bool, String> {
    let clean = api_key.trim();
    if clean.is_empty() {
        return Err("API 密钥不能为空".to_string());
    }
    keyring_entry()?
        .set_password(clean)
        .map_err(|error| format!("保存密钥失败: {error}"))?;
    // 不把明文传给日志或 SQLite；仅返回配置状态。
    let _ = &state;
    Ok(true)
}

#[tauri::command]
pub fn clear_ai_key(state: State<'_, AppState>) -> Result<bool, String> {
    let entry = keyring_entry()?;
    if let Err(error) = entry.delete_credential() {
        let text = error.to_string().to_lowercase();
        // 条目本就不存在时也视为已清除。
        if !text.contains("no entry") && !text.contains("not found") && !text.contains("不存在") {
            return Err(format!("清除密钥失败: {error}"));
        }
    }
    let _ = &state;
    Ok(false)
}
