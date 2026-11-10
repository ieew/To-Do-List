//! Tauri 命令层：只做参数校验、op 追加与投影读取，不实现合并裁决。
//! 合并与投影逻辑全部在 core_domain；SQLite 访问全部在 storage。

use crate::core_domain::{HistoryOp, Op, Projection, Settings, Workspace};
use crate::storage;
use crate::sync::{
    self,
    client::Api,
    engine,
    session::{self, AccountRecord, Tokens},
};
use crate::AppState;
use anyhow::{anyhow, Result as AnyResult};
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager, State};
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
    pub sync: sync::SyncStateDto,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthResult {
    pub account: AccountRecord,
    pub backup_path: Option<String>,
    pub sync: sync::SyncStateDto,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncPreflight {
    pub local_op_count: i64,
    pub backup_dir: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateTaskInput {
    pub title: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub start_at: Option<String>,
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

/// 与 `with_db` 相同，但把整组写入放进单事务，避免半成品工作区。
fn with_db_tx<T>(
    state: &AppState,
    f: impl FnOnce(&Connection) -> AnyResult<T>,
) -> Result<T, String> {
    let guard = state
        .db
        .lock()
        .map_err(|_| "数据库连接锁失败，请重试".to_string())?;
    storage::with_transaction(&guard, f).map_err(|error| error.to_string())
}

fn emit_projection(app: &AppHandle, workspace_id: &str) {
    wake_sync(app);
    let _ = app.emit("projection-updated", json!({ "workspaceId": workspace_id }));
}

/// 工作区列表本身发生变化时使用全局事件：`workspaceId = null` 让所有窗口刷新。
fn emit_workspaces_changed(app: &AppHandle) {
    wake_sync(app);
    let _ = app.emit("projection-updated", json!({ "workspaceId": null }));
}

/// 本地写入后立即唤醒后台同步；未登录时 worker 会在 Notify 上等待。
fn wake_sync(app: &AppHandle) {
    if let Some(state) = app.try_state::<AppState>() {
        state.sync.wake();
    }
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

pub(crate) fn active_workspace(conn: &Connection) -> String {
    storage::active_workspace_id(conn).unwrap_or_else(|| "ws-me".to_string())
}

pub(crate) fn build_projection(conn: &Connection, workspace_id: &str) -> AnyResult<Projection> {
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
        "startAt" => "start_at",
        "dueAt" => "due_at",
        "remindAt" => "remind_at",
        "listId" => "list_id",
        "workspaceId" => "workspace_id",
        other => other,
    }
}

fn workspace_name(raw: &str) -> AnyResult<String> {
    let clean = raw.trim();
    let count = clean.chars().count();
    if count == 0 || count > 24 {
        return Err(anyhow!("工作区名称需为 1–24 个字符"));
    }
    Ok(clean.to_string())
}

/// 本地命令层的前置校验；未来服务端 Writer Worker 仍是唯一授权入口。
fn ensure_workspace_owner(projection: &Projection, current_user: &str) -> AnyResult<()> {
    let is_owner = projection
        .members
        .iter()
        .any(|member| member.id == current_user && member.role == "owner");
    if is_owner {
        Ok(())
    } else {
        Err(anyhow!(
            "workspace_forbidden: 只有所有者可以重命名或删除工作区"
        ))
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
        let sync_state = sync::snapshot(conn, &state.sync);
        Ok(BootstrapResponse {
            projection,
            settings,
            ai_key_configured: ai_key_configured(),
            device_id: storage::device_id(conn)?,
            sync: sync_state,
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
        let projection = build_projection(conn, &workspace_id)?;
        if !projection
            .workspaces
            .iter()
            .any(|workspace| workspace.id == workspace_id)
        {
            return Err(anyhow!("workspace_not_found: 工作区不存在或已被删除"));
        }
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
    let clean = workspace_name(&name).map_err(|error| error.to_string())?;
    let workspace_id = format!("ws-{}", &Uuid::new_v4().to_string()[..8]);
    let created = with_db_tx(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "workspace",
            &workspace_id,
            "set",
            "__created",
            json!(chrono::Local::now().to_rfc3339()),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "workspace",
            &workspace_id,
            "set",
            "name",
            json!(clean),
            "human",
            None,
        )?;
        let membership_id = format!("{workspace_id}:{actor}");
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "membership",
            &membership_id,
            "set",
            "__role",
            json!("owner"),
            "human",
            None,
        )?;
        // 新工作区与默认工作区共用同一份预设清单/标签定义。
        storage::seed_workspace_presets(conn, &device, &actor, &workspace_id, &workspace_id)?;
        storage::pref_set(conn, "active_workspace_id", &workspace_id)?;
        Ok(Workspace {
            id: workspace_id.clone(),
            name: clean.to_string(),
        })
    })?;
    emit_workspaces_changed(&app);
    Ok(created)
}

#[tauri::command]
pub fn rename_workspace(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
    name: String,
) -> Result<(), String> {
    let clean = workspace_name(&name).map_err(|error| error.to_string())?;
    with_db(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        if !projection
            .workspaces
            .iter()
            .any(|workspace| workspace.id == workspace_id)
        {
            return Err(anyhow!("workspace_not_found: 工作区不存在或已被删除"));
        }
        let actor = storage::current_user_id(conn);
        ensure_workspace_owner(&projection, &actor)?;
        let device = storage::device_id(conn)?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "workspace",
            &workspace_id,
            "set",
            "name",
            json!(clean),
            "human",
            None,
        )
    })?;
    emit_workspaces_changed(&app);
    Ok(())
}

/// 删除工作区：写 `__deleted` 墓碑 op，不物理删除 `op_log`。
///
/// 返回删除后的当前工作区 id，方便前端立即切换作用域。
#[tauri::command]
pub fn delete_workspace(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
) -> Result<String, String> {
    let next_active = with_db_tx(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        if !projection
            .workspaces
            .iter()
            .any(|workspace| workspace.id == workspace_id)
        {
            return Err(anyhow!("workspace_not_found: 工作区不存在或已被删除"));
        }
        let actor = storage::current_user_id(conn);
        ensure_workspace_owner(&projection, &actor)?;

        let remaining: Vec<String> = projection
            .workspaces
            .iter()
            .filter(|workspace| workspace.id != workspace_id)
            .map(|workspace| workspace.id.clone())
            .collect();
        if remaining.is_empty() {
            return Err(anyhow!("last_workspace: 至少需要保留一个工作区"));
        }

        let current = storage::active_workspace_id(conn).unwrap_or_else(|| workspace_id.clone());
        let keep_current = current != workspace_id && remaining.iter().any(|id| id == &current);
        let next_active = if keep_current {
            current
        } else {
            remaining[0].clone()
        };

        let device = storage::device_id(conn)?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "workspace",
            &workspace_id,
            "set",
            "__deleted",
            json!(true),
            "human",
            None,
        )?;
        storage::pref_set(conn, "active_workspace_id", &next_active)?;
        Ok(next_active)
    })?;
    emit_workspaces_changed(&app);
    Ok(next_active)
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
        // 未显式指定开始时间时，默认取创建任务的当天时间（即当前时刻）。
        let start_at = input
            .start_at
            .clone()
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| chrono::Local::now().to_rfc3339());

        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "set",
            "__created",
            json!(chrono::Local::now().to_rfc3339()),
            source,
            input.model.as_deref(),
        )?;
        for (field, value) in [
            ("title", json!(title)),
            ("notes", json!(input.notes.clone())),
            ("start_at", json!(start_at)),
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
                conn,
                &device,
                &actor,
                &workspace_id,
                "task",
                &task_id,
                "set",
                field,
                value,
                source,
                input.model.as_deref(),
            )?;
        }

        for tag_id in &input.tag_ids {
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                "task",
                &task_id,
                "add",
                "__tag",
                json!(tag_id),
                source,
                input.model.as_deref(),
            )?;
        }
        for (index, text) in input.subtasks.iter().enumerate() {
            let sub_id = format!("sub-{}", &Uuid::new_v4().to_string()[..8]);
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                "task",
                &task_id,
                "add",
                "__subtask",
                json!(sub_id),
                source,
                input.model.as_deref(),
            )?;
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                "task",
                &task_id,
                "set",
                &format!("__subtask:{sub_id}:text"),
                json!(text),
                source,
                input.model.as_deref(),
            )?;
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                "task",
                &task_id,
                "set",
                &format!("__subtask:{sub_id}:done"),
                json!(false),
                source,
                input.model.as_deref(),
            )?;
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                "task",
                &task_id,
                "set",
                &format!("__subtask:{sub_id}:order"),
                json!(index as f64),
                source,
                input.model.as_deref(),
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
                    conn,
                    &device,
                    &actor,
                    &workspace_id,
                    "task",
                    &task_id,
                    "add",
                    "__tag",
                    json!(tag_id),
                    "human",
                    None,
                )?;
            }
            for tag_id in task.tag_ids.iter().filter(|id| !next.contains(id)) {
                append(
                    conn,
                    &device,
                    &actor,
                    &workspace_id,
                    "task",
                    &task_id,
                    "remove",
                    "__tag",
                    json!(tag_id),
                    "human",
                    None,
                )?;
            }
            return Ok(());
        }

        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "set",
            map_field(&field),
            value,
            "human",
            None,
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
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "set",
            "__deleted",
            json!(true),
            "human",
            None,
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
                    conn,
                    &device,
                    &actor,
                    &workspace_id,
                    "task",
                    &task.id,
                    "set",
                    "order",
                    json!(index as f64),
                    "human",
                    None,
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
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "add",
            "__subtask",
            json!(subtask_id),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "set",
            &format!("__subtask:{subtask_id}:text"),
            json!(clean),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "set",
            &format!("__subtask:{subtask_id}:done"),
            json!(false),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "set",
            &format!("__subtask:{subtask_id}:order"),
            json!(task.subtasks.len() as f64),
            "human",
            None,
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
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "set",
            &format!("__subtask:{subtask_id}:done"),
            json!(!subtask.done),
            "human",
            None,
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
            conn,
            &device,
            &actor,
            &workspace_id,
            "task",
            &task_id,
            "remove",
            "__subtask",
            json!(subtask_id),
            "human",
            None,
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
pub fn undo_op(app: AppHandle, state: State<'_, AppState>, op_id: String) -> Result<(), String> {
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
                conn,
                &device,
                &actor,
                &workspace_id,
                &target.entity_type,
                &target.entity_id,
                "remove",
                &target.field,
                target.value.clone(),
                "human",
                None,
            )?;
        } else if target.operation == "remove" {
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                &target.entity_type,
                &target.entity_id,
                "add",
                &target.field,
                target.value.clone(),
                "human",
                None,
            )?;
        } else if target.field == "__created" {
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                &target.entity_type,
                &target.entity_id,
                "set",
                "__deleted",
                json!(true),
                "human",
                None,
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
                conn,
                &device,
                &actor,
                &workspace_id,
                &target.entity_type,
                &target.entity_id,
                "set",
                &target.field,
                previous,
                "human",
                None,
            )?;
        }

        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            &target.entity_type,
            &target.entity_id,
            "set",
            "__undo_of",
            json!(target.op_id),
            "human",
            None,
        )?;
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

/* ------------------------------- 成员 ------------------------------- */

/// 成员变更成功后立即拉取服务端生成的 op；失败时保留同步状态提示，后台循环会重试。
async fn refresh_after_remote(app: &AppHandle, state: &AppState) {
    match engine::pull_initial(state).await {
        Ok(outcome) => sync::publish(app, state, &outcome),
        Err(_) => sync::emit_state(app, state),
    }
}

#[tauri::command]
pub async fn update_member_role(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
    user_id: String,
    role: String,
) -> Result<(), String> {
    if !matches!(role.as_str(), "owner" | "editor" | "viewer") {
        return Err("非法角色".to_string());
    }
    if let Some((api, session)) = engine::authorized_session(&state)
        .await
        .map_err(|error| error.message)?
    {
        api.set_role(&session.tokens.access_token, &workspace_id, &user_id, &role)
            .await
            .map_err(|error| error.message)?;
        refresh_after_remote(&app, &state).await;
        return Ok(());
    }

    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        let membership_id = format!("{workspace_id}:{user_id}");
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "membership",
            &membership_id,
            "set",
            "__role",
            json!(role),
            "human",
            None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub async fn remove_member(
    app: AppHandle,
    state: State<'_, AppState>,
    workspace_id: String,
    user_id: String,
) -> Result<(), String> {
    if let Some((api, session)) = engine::authorized_session(&state)
        .await
        .map_err(|error| error.message)?
    {
        api.remove_member(&session.tokens.access_token, &workspace_id, &user_id)
            .await
            .map_err(|error| error.message)?;
        refresh_after_remote(&app, &state).await;
        return Ok(());
    }

    with_db(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        if user_id == actor {
            return Err(anyhow!("不能移除自己"));
        }
        let membership_id = format!("{workspace_id}:{user_id}");
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "membership",
            &membership_id,
            "set",
            "__removed",
            json!(true),
            "human",
            None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub async fn invite_member(
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

    if let Some((api, session)) = engine::authorized_session(&state)
        .await
        .map_err(|error| error.message)?
    {
        // 服务端按已注册账号邀请：优先用邮箱，其次用户名。
        let account = {
            let email = email.trim();
            if email.is_empty() {
                clean_name.clone()
            } else {
                email.to_string()
            }
        };
        let response = api
            .invite(&session.tokens.access_token, &workspace_id, &account, &role)
            .await
            .map_err(|error| error.message)?;
        refresh_after_remote(&app, &state).await;
        return Ok(response
            .get("user_id")
            .and_then(|value| value.as_str())
            .unwrap_or_default()
            .to_string());
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
                conn,
                &device,
                &actor,
                &workspace_id,
                "user",
                &user_id,
                "set",
                field,
                value,
                "human",
                None,
            )?;
        }
        let membership_id = format!("{workspace_id}:{user_id}");
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "membership",
            &membership_id,
            "set",
            "__role",
            json!(role),
            "human",
            None,
        )?;
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(user_id)
}

/* --------------------------- 账户与同步 --------------------------- */

fn backup_dir(state: &AppState) -> PathBuf {
    state.data_dir.join("backups")
}

fn lock_db(state: &AppState) -> Result<std::sync::MutexGuard<'_, Connection>, String> {
    state
        .db
        .lock()
        .map_err(|_| "数据库连接锁失败，请重试".to_string())
}

fn save_server_url(conn: &Connection, server_url: &str) -> Result<(), String> {
    let mut settings = storage::load_settings(conn);
    if settings.server_url == server_url {
        return Ok(());
    }
    settings.server_url = server_url.to_string();
    storage::save_settings(conn, &settings).map_err(|error| error.to_string())
}

/// 登录/注册成功后切换到云端账户：先整库备份，再清空本地 op 与游标。
fn prepare_account(
    conn: &Connection,
    state: &AppState,
    account: &AccountRecord,
    active_workspace: Option<&str>,
) -> Result<Option<String>, String> {
    let previous = session::account(conn);
    let same_account = previous
        .as_ref()
        .map(|item| item.server_url == account.server_url && item.user_id == account.user_id)
        .unwrap_or(false);

    // 同一账户再次登录时保留本地库与游标，避免把离线期间生成的任务清掉。
    let backup = if same_account {
        None
    } else {
        if let Some(previous_account) = previous.as_ref() {
            let _ = session::clear_tokens(previous_account);
        }
        storage::reset_for_account(conn, &backup_dir(state)).map_err(|error| error.to_string())?
    };

    storage::pref_set(conn, "current_user_id", &account.user_id)
        .map_err(|error| error.to_string())?;
    if !same_account {
        if let Some(workspace) = active_workspace {
            storage::pref_set(conn, "active_workspace_id", workspace)
                .map_err(|error| error.to_string())?;
        }
    }
    session::save_account(conn, account)?;
    Ok(backup.map(|path| path.display().to_string()))
}

async fn finish_login(
    app: &AppHandle,
    state: &AppState,
    account: AccountRecord,
    backup_path: Option<String>,
) -> AuthResult {
    state.sync.set_signed_in(true);
    match engine::run_once(state).await {
        Ok(outcome) => sync::publish(app, state, &outcome),
        Err(error) => {
            if let Ok(mut runtime) = state.sync.runtime.lock() {
                runtime.last_error = Some(error.message.clone());
                runtime.status = if error.code == "auth_required" {
                    sync::SyncStatus::AuthRequired
                } else if error.is_retryable() {
                    sync::SyncStatus::Retrying
                } else {
                    sync::SyncStatus::Offline
                };
            }
            sync::emit_state(app, state);
        }
    }
    AuthResult {
        account,
        backup_path,
        sync: sync::snapshot_for(state),
    }
}

fn normalize_optional(value: Option<String>) -> Option<String> {
    value
        .map(|item| item.trim().to_string())
        .filter(|item| !item.is_empty())
}

#[tauri::command]
pub async fn auth_register(
    app: AppHandle,
    state: State<'_, AppState>,
    server_url: String,
    username: String,
    password: String,
    email: Option<String>,
    display_name: Option<String>,
    workspace_name: Option<String>,
) -> Result<AuthResult, String> {
    let username = username.trim().to_string();
    if username.is_empty() || password.is_empty() {
        return Err("用户名和密码不能为空".to_string());
    }
    let api = Api::new(&server_url).map_err(|error| error.message)?;
    let device = with_db(&state, |conn| storage::device_id(conn))?;
    let body = crate::sync::client::RegisterBody {
        username,
        password,
        email: normalize_optional(email),
        display_name: normalize_optional(display_name),
        device_id: Some(device),
        workspace_name: normalize_optional(workspace_name),
    };
    let response = api.register(&body).await.map_err(|error| error.message)?;
    let account = AccountRecord {
        server_url: api.base_url.clone(),
        user_id: response.user.user_id.clone(),
        username: response.user.username.clone(),
        display_name: response.user.display_name.clone(),
    };
    let tokens = Tokens::from(&response.tokens);
    session::save_tokens(&account, &tokens)?;
    let backup_path = {
        let conn = lock_db(&state)?;
        let path = prepare_account(
            &conn,
            &state,
            &account,
            Some(response.default_workspace_id.as_str()),
        )?;
        save_server_url(&conn, &api.base_url)?;
        path
    };
    Ok(finish_login(&app, &state, account, backup_path).await)
}

#[tauri::command]
pub async fn auth_login(
    app: AppHandle,
    state: State<'_, AppState>,
    server_url: String,
    username: String,
    password: String,
) -> Result<AuthResult, String> {
    let username = username.trim().to_string();
    if username.is_empty() || password.is_empty() {
        return Err("用户名和密码不能为空".to_string());
    }
    let api = Api::new(&server_url).map_err(|error| error.message)?;
    let device = with_db(&state, |conn| storage::device_id(conn))?;
    let response = api
        .login(&username, &password, &device)
        .await
        .map_err(|error| error.message)?;
    let account = AccountRecord {
        server_url: api.base_url.clone(),
        user_id: response.user.user_id.clone(),
        username: response.user.username.clone(),
        display_name: response.user.display_name.clone(),
    };
    let tokens = Tokens::from(&response.tokens);
    session::save_tokens(&account, &tokens)?;
    let backup_path = {
        let conn = lock_db(&state)?;
        let workspace = response
            .workspaces
            .first()
            .map(|item| item.workspace_id.clone());
        let path = prepare_account(&conn, &state, &account, workspace.as_deref())?;
        save_server_url(&conn, &api.base_url)?;
        path
    };
    Ok(finish_login(&app, &state, account, backup_path).await)
}

#[tauri::command]
pub async fn auth_logout(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let session = {
        let conn = lock_db(&state)?;
        session::load_session(&conn)?
    };
    if let Some(session) = session {
        if let Ok(api) = Api::new(&session.account.server_url) {
            let _ = api.logout(&session.tokens.refresh_token).await;
        }
        session::clear_tokens(&session.account)?;
    }
    // 保留本地数据与 sync_account：同账户再次登录时不会重复清库。
    state.sync.set_signed_in(false);
    sync::emit_state(&app, &state);
    Ok(())
}

#[tauri::command]
pub async fn sync_now(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<sync::SyncStateDto, String> {
    let outcome = engine::run_once(&state)
        .await
        .map_err(|error| error.message)?;
    sync::publish(&app, &state, &outcome);
    Ok(sync::snapshot_for(&state))
}

#[tauri::command]
pub fn sync_preflight(state: State<'_, AppState>) -> Result<SyncPreflight, String> {
    let count = with_db(&state, |conn| Ok(storage::load_all_ops(conn)?.len() as i64))?;
    Ok(SyncPreflight {
        local_op_count: count,
        backup_dir: backup_dir(&state).display().to_string(),
    })
}

/* --------------------------- 清单与标签 --------------------------- */

fn clean_text(raw: &str, max: usize, label: &str) -> AnyResult<String> {
    let clean = raw.trim();
    let count = clean.chars().count();
    if count == 0 || count > max {
        return Err(anyhow!("{label}需为 1–{max} 个字符"));
    }
    Ok(clean.to_string())
}

fn clean_color(raw: &str, label: &str) -> AnyResult<String> {
    let clean = raw.trim();
    if clean.is_empty() || clean.len() > 32 {
        return Err(anyhow!("{label}需为 1–32 个字符"));
    }
    Ok(clean.to_string())
}

fn require_role(
    conn: &Connection,
    workspace_id: &str,
    user_id: &str,
    allowed: &[&str],
) -> AnyResult<()> {
    let projection = build_projection(conn, workspace_id)?;
    let role = projection
        .members
        .iter()
        .find(|member| member.id == user_id)
        .map(|member| member.role.as_str());
    match role {
        Some(role) if allowed.contains(&role) => Ok(()),
        Some(_) => Err(anyhow!("workspace_forbidden: 当前角色没有该操作权限")),
        None => Err(anyhow!("workspace_forbidden: 不是该工作区成员")),
    }
}

fn validate_order(value: &Value) -> AnyResult<Value> {
    let number = value.as_f64().ok_or_else(|| anyhow!("排序值必须是数字"))?;
    if !number.is_finite() || number.abs() > 1e9 {
        return Err(anyhow!("排序值超出范围"));
    }
    Ok(json!(number))
}

#[tauri::command]
pub fn create_list(
    app: AppHandle,
    state: State<'_, AppState>,
    name: String,
    color: Option<String>,
) -> Result<String, String> {
    let name = clean_text(&name, 60, "清单名称").map_err(|error| error.to_string())?;
    let color = match color {
        Some(raw) => clean_color(&raw, "清单颜色").map_err(|error| error.to_string())?,
        None => "#3B82F6".to_string(),
    };
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    let list_id = format!("list-{}", &Uuid::new_v4().to_string()[..8]);
    with_db_tx(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        require_role(conn, &workspace_id, &actor, &["owner"])?;
        let order = build_projection(conn, &workspace_id)?.lists.len() as f64;
        let created_at = chrono::Local::now().to_rfc3339();
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "list",
            &list_id,
            "set",
            "__created",
            json!(created_at),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "list",
            &list_id,
            "set",
            "workspace_id",
            json!(workspace_id),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "list",
            &list_id,
            "set",
            "name",
            json!(name),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "list",
            &list_id,
            "set",
            "color",
            json!(color),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "list",
            &list_id,
            "set",
            "order",
            json!(order),
            "human",
            None,
        )?;
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(list_id)
}

#[tauri::command]
pub fn update_list_field(
    app: AppHandle,
    state: State<'_, AppState>,
    list_id: String,
    field: String,
    value: Value,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        projection
            .lists
            .iter()
            .find(|list| list.id == list_id)
            .ok_or_else(|| anyhow!("清单不存在"))?;
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        require_role(conn, &workspace_id, &actor, &["owner", "editor"])?;
        let (field, value) = match field.as_str() {
            "name" => (
                "name",
                json!(clean_text(
                    value.as_str().unwrap_or_default(),
                    60,
                    "清单名称"
                )?),
            ),
            "color" => (
                "color",
                json!(clean_color(value.as_str().unwrap_or_default(), "清单颜色")?),
            ),
            "order" => ("order", validate_order(&value)?),
            _ => return Err(anyhow!("不支持的清单字段")),
        };
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "list",
            &list_id,
            "set",
            field,
            value,
            "human",
            None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn delete_list(
    app: AppHandle,
    state: State<'_, AppState>,
    list_id: String,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db_tx(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        let list = projection
            .lists
            .iter()
            .find(|item| item.id == list_id)
            .ok_or_else(|| anyhow!("清单不存在"))?;
        if list.smart == Some(true) {
            return Err(anyhow!("智能清单不能删除"));
        }
        let today = projection
            .lists
            .iter()
            .find(|item| item.smart == Some(true))
            .map(|item| item.id.clone())
            .ok_or_else(|| anyhow!("当前工作区缺少今天清单"))?;
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        require_role(conn, &workspace_id, &actor, &["owner"])?;

        // 删除前把任务迁到智能清单，避免任务指向不存在的清单。
        for task in projection
            .tasks
            .iter()
            .filter(|task| task.list_id == list_id)
        {
            append(
                conn,
                &device,
                &actor,
                &workspace_id,
                "task",
                &task.id,
                "set",
                "list_id",
                json!(today),
                "human",
                None,
            )?;
        }
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "list",
            &list_id,
            "set",
            "__deleted",
            json!(true),
            "human",
            None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn create_tag(
    app: AppHandle,
    state: State<'_, AppState>,
    name: String,
    bg: Option<String>,
    fg: Option<String>,
) -> Result<String, String> {
    let name = clean_text(&name, 60, "标签名称").map_err(|error| error.to_string())?;
    let bg = match bg {
        Some(raw) => clean_color(&raw, "标签背景色").map_err(|error| error.to_string())?,
        None => "#EFF6FF".to_string(),
    };
    let fg = match fg {
        Some(raw) => clean_color(&raw, "标签前景色").map_err(|error| error.to_string())?,
        None => "#2563EB".to_string(),
    };
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    let tag_id = format!("tag-{}", &Uuid::new_v4().to_string()[..8]);
    with_db_tx(&state, |conn| {
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        require_role(conn, &workspace_id, &actor, &["owner"])?;
        let created_at = chrono::Local::now().to_rfc3339();
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            "__created",
            json!(created_at),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            "workspace_id",
            json!(workspace_id),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            "name",
            json!(name),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            "bg",
            json!(bg),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            "fg",
            json!(fg),
            "human",
            None,
        )?;
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            "show_in_sidebar",
            json!(true),
            "human",
            None,
        )?;
        Ok(())
    })?;
    emit_projection(&app, &workspace_id);
    Ok(tag_id)
}

#[tauri::command]
pub fn update_tag_field(
    app: AppHandle,
    state: State<'_, AppState>,
    tag_id: String,
    field: String,
    value: Value,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        projection
            .tags
            .iter()
            .find(|tag| tag.id == tag_id)
            .ok_or_else(|| anyhow!("标签不存在"))?;
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        require_role(conn, &workspace_id, &actor, &["owner", "editor"])?;
        let (field, value) = match field.as_str() {
            "name" => (
                "name",
                json!(clean_text(
                    value.as_str().unwrap_or_default(),
                    60,
                    "标签名称"
                )?),
            ),
            "bg" => (
                "bg",
                json!(clean_color(
                    value.as_str().unwrap_or_default(),
                    "标签背景色"
                )?),
            ),
            "fg" => (
                "fg",
                json!(clean_color(
                    value.as_str().unwrap_or_default(),
                    "标签前景色"
                )?),
            ),
            "showInSidebar" | "show_in_sidebar" => {
                let flag = value
                    .as_bool()
                    .ok_or_else(|| anyhow!("侧栏显示必须是布尔值"))?;
                ("show_in_sidebar", json!(flag))
            }
            _ => return Err(anyhow!("不支持的标签字段")),
        };
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            field,
            value,
            "human",
            None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

#[tauri::command]
pub fn delete_tag(
    app: AppHandle,
    state: State<'_, AppState>,
    tag_id: String,
) -> Result<(), String> {
    let workspace_id = with_db(&state, |conn| Ok(active_workspace(conn)))?;
    with_db(&state, |conn| {
        let projection = build_projection(conn, &workspace_id)?;
        projection
            .tags
            .iter()
            .find(|tag| tag.id == tag_id)
            .ok_or_else(|| anyhow!("标签不存在"))?;
        let device = storage::device_id(conn)?;
        let actor = storage::current_user_id(conn);
        require_role(conn, &workspace_id, &actor, &["owner"])?;
        // 与共享 testvector 一致：任务上的 tag_ids 保留已删标签 id，不做清洗。
        append(
            conn,
            &device,
            &actor,
            &workspace_id,
            "tag",
            &tag_id,
            "set",
            "__deleted",
            json!(true),
            "human",
            None,
        )
    })?;
    emit_projection(&app, &workspace_id);
    Ok(())
}

/* ------------------------------- 设置 ------------------------------- */

#[tauri::command]
pub fn save_settings(
    app: AppHandle,
    state: State<'_, AppState>,
    settings: Settings,
) -> Result<(), String> {
    with_db(&state, |conn| storage::save_settings(conn, &settings))?;
    // 主窗口与便利贴是独立 WebviewWindow，用事件把外观设置推给所有窗口。
    let _ = app.emit("settings-updated", &settings);
    Ok(())
}

/* ----------------------------- AI 密钥 ----------------------------- */

pub(crate) fn keyring_entry() -> Result<keyring::Entry, String> {
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
        if !text.contains("no entry") && !text.contains("not found") && !text.contains("不存在")
        {
            return Err(format!("清除密钥失败: {error}"));
        }
    }
    let _ = &state;
    Ok(false)
}
