//! AI 任务规划命令层：OpenAI 兼容 API 直连 + 网页版提示词桥接。
//!
//! 纯提示词、JSON 提取与 schema 归一化逻辑在 `todo-ai-core` crate 中，便于独立测试。
//! 本模块只负责读取本地上下文、访问 keyring、发起 HTTP 请求和映射错误。

use crate::commands::{
    active_workspace, append_op, build_projection, emit_projection, keyring_entry, map_field,
    next_task_order,
};
use crate::core_domain::{Projection, Settings};
use crate::storage;
use crate::AppState;
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::time::Duration;
use tauri::{AppHandle, State};
use todo_ai_core::{
    build_api_messages, build_intent_messages, build_web_intent_prompt, build_web_prompt,
    chat_completions_url, extract_chat_content, parse_intent_text, parse_plan_text, AiIntentResponse,
    AiOperation, AiPlanResponse, ListRef, PlanContext, SubtaskRef, TagRef, TaskRef,
    MAX_OPERATIONS,
};
use uuid::Uuid;

const REQUEST_TIMEOUT_SECS: u64 = 60;

struct AiContext {
    plan: PlanContext,
    settings: Settings,
    api_key: Option<String>,
}

#[derive(Serialize)]
struct ChatRequest<'a> {
    model: &'a str,
    messages: Vec<ChatRequestMessage>,
    temperature: f32,
    stream: bool,
}

#[derive(Serialize)]
struct ChatRequestMessage {
    role: &'static str,
    content: Value,
}

#[derive(Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct AiApplyResult {
    pub op_ids: Vec<String>,
    pub affected_task_ids: Vec<String>,
}

/// 图片只作为多模态 content 数组发送，不落盘、不入 op_log。
fn message_content(text: &str, images: &[String]) -> Value {
    if images.is_empty() {
        return Value::String(text.to_string());
    }
    let mut parts = vec![json!({ "type": "text", "text": text })];
    for url in images.iter().take(5) {
        parts.push(json!({
            "type": "image_url",
            "image_url": { "url": url }
        }));
    }
    Value::Array(parts)
}

async fn request_chat(
    model: &str,
    endpoint: &str,
    api_key: &str,
    system: &str,
    user: Value,
) -> Result<String, String> {
    let request = ChatRequest {
        model,
        messages: vec![
            ChatRequestMessage {
                role: "system",
                content: Value::String(system.to_string()),
            },
            ChatRequestMessage {
                role: "user",
                content: user,
            },
        ],
        temperature: 0.2,
        stream: false,
    };

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(REQUEST_TIMEOUT_SECS))
        .connect_timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| format!("初始化模型客户端失败: {error}"))?;

    let response = client
        .post(endpoint)
        .bearer_auth(api_key)
        .json(&request)
        .send()
        .await
        .map_err(map_reqwest_error)?;

    let status = response.status();
    let body = response
        .text()
        .await
        .map_err(|error| format!("读取模型响应失败: {error}"))?;
    if !status.is_success() {
        return Err(http_error_message(status, &body));
    }

    let payload: Value =
        serde_json::from_str(&body).map_err(|error| format!("模型响应不是合法 JSON: {error}"))?;
    extract_chat_content(&payload)
}

/* ------------------------------- 命令 ------------------------------- */

#[tauri::command]
#[specta::specta]
pub fn ai_build_prompt(
    state: State<'_, AppState>,
    prompt: String,
    workspace_id: Option<String>,
) -> Result<String, String> {
    let clean = prompt.trim();
    if clean.is_empty() {
        return Err("请先描述你想让 AI 做什么".to_string());
    }
    let context = load_context(&state, workspace_id, false)?;
    Ok(build_web_prompt(&context.plan, clean))
}

#[tauri::command]
#[specta::specta]
pub async fn ai_generate_tasks(
    state: State<'_, AppState>,
    prompt: String,
    workspace_id: Option<String>,
) -> Result<AiPlanResponse, String> {
    let clean = prompt.trim().to_string();
    if clean.is_empty() {
        return Err("请先描述你想让 AI 做什么".to_string());
    }
    let context = load_context(&state, workspace_id, true)?;
    let (api_key, model, endpoint) = prepared(&context)?;
    let (system, user) = build_api_messages(&context.plan, &clean);
    let content = request_chat(
        &model,
        &endpoint,
        &api_key,
        &system,
        Value::String(user),
    )
    .await?;
    parse_plan_text(&content, &context.plan)
}

/// 新版入口：返回操作列表（创建 / 修改 / 标签 / 子任务 / 状态 / 删除）。
#[tauri::command]
#[specta::specta]
pub async fn ai_plan(
    state: State<'_, AppState>,
    prompt: String,
    workspace_id: Option<String>,
    images: Option<Vec<String>>,
) -> Result<AiIntentResponse, String> {
    let clean = prompt.trim().to_string();
    if clean.is_empty() {
        return Err("请先描述你想让 AI 做什么".to_string());
    }
    let context = load_context(&state, workspace_id, true)?;
    let (api_key, model, endpoint) = prepared(&context)?;
    let (system, user) = build_intent_messages(&context.plan, &clean);
    let images = images.unwrap_or_default();
    let content = request_chat(&model, &endpoint, &api_key, &system, message_content(&user, &images)).await?;
    parse_intent_text(&content, &context.plan)
}

#[tauri::command]
#[specta::specta]
pub fn ai_build_intent_prompt(
    state: State<'_, AppState>,
    prompt: String,
    workspace_id: Option<String>,
) -> Result<String, String> {
    let clean = prompt.trim();
    if clean.is_empty() {
        return Err("请先描述你想让 AI 做什么".to_string());
    }
    let context = load_context(&state, workspace_id, false)?;
    Ok(build_web_intent_prompt(&context.plan, clean))
}

#[tauri::command]
#[specta::specta]
pub fn ai_parse_intent(
    state: State<'_, AppState>,
    text: String,
    workspace_id: Option<String>,
) -> Result<AiIntentResponse, String> {
    let clean = text.trim();
    if clean.is_empty() {
        return Err("请先粘贴模型输出".to_string());
    }
    let context = load_context(&state, workspace_id, false)?;
    parse_intent_text(clean, &context.plan)
}

fn prepared(context: &AiContext) -> Result<(String, String, String), String> {
    let api_key = context
        .api_key
        .clone()
        .ok_or_else(|| "请先在设置中配置 API 密钥".to_string())?;
    let model = context.settings.ai_model.trim().to_string();
    if model.is_empty() {
        return Err("请先在设置中填写模型名".to_string());
    }
    let endpoint = chat_completions_url(&context.settings.ai_endpoint)?;
    Ok((api_key, model, endpoint))
}

/* --------------------------- 意图写入通道 --------------------------- */

fn is_destructive(operation: &AiOperation) -> bool {
    matches!(
        operation,
        AiOperation::SetStatus { .. } | AiOperation::DeleteTask { .. }
    )
}

struct ApplyCtx<'a> {
    conn: &'a rusqlite::Connection,
    device: String,
    actor: String,
    workspace: String,
    projection: &'a Projection,
    model: String,
    next_order: f64,
    subtask_orders: HashMap<String, f64>,
    result: AiApplyResult,
}

impl ApplyCtx<'_> {
    fn op(
        &mut self,
        entity_type: &str,
        entity_id: &str,
        operation: &str,
        field: &str,
        value: Value,
    ) -> anyhow::Result<()> {
        let op_id = append_op(
            self.conn,
            &self.device,
            &self.actor,
            &self.workspace,
            entity_type,
            entity_id,
            operation,
            field,
            value,
            "ai",
            Some(&self.model),
        )?;
        self.result.op_ids.push(op_id);
        Ok(())
    }

    fn task_exists(&self, task_id: &str) -> bool {
        self.projection.tasks.iter().any(|task| task.id == task_id)
    }

    fn tags_exist(&self, tag_ids: &[String]) -> bool {
        tag_ids
            .iter()
            .all(|id| self.projection.tags.iter().any(|tag| &tag.id == id))
    }

    fn next_subtask_order(&mut self, task_id: &str) -> f64 {
        let base = self
            .projection
            .tasks
            .iter()
            .find(|task| task.id == task_id)
            .map(|task| task.subtasks.len() as f64)
            .unwrap_or(0.0);
        let entry = self.subtask_orders.entry(task_id.to_string()).or_insert(base);
        let value = *entry;
        *entry += 1.0;
        value
    }

    fn add_subtask(&mut self, task_id: &str, text: &str, order: f64) -> anyhow::Result<()> {
        let sub_id = format!("sub-{}", &Uuid::new_v4().to_string()[..8]);
        self.op("task", task_id, "add", "__subtask", json!(sub_id))?;
        self.op(
            "task",
            task_id,
            "set",
            &format!("__subtask:{sub_id}:text"),
            json!(text),
        )?;
        self.op(
            "task",
            task_id,
            "set",
            &format!("__subtask:{sub_id}:done"),
            json!(false),
        )?;
        self.op(
            "task",
            task_id,
            "set",
            &format!("__subtask:{sub_id}:order"),
            json!(order),
        )?;
        Ok(())
    }

    fn apply(&mut self, operation: &AiOperation) -> anyhow::Result<()> {
        match operation {
            AiOperation::CreateTask {
                title,
                notes,
                priority,
                list_id,
                due_at,
                tag_ids,
                subtasks,
                ..
            } => {
                let title = title.trim();
                if title.is_empty() || title.chars().count() > 120 {
                    return Err(anyhow::anyhow!("创建任务的标题需为 1–120 个字符"));
                }
                if notes.chars().count() > 2000 {
                    return Err(anyhow::anyhow!("创建任务的备注不能超过 2000 个字符"));
                }
                if !matches!(priority.as_str(), "high" | "mid" | "low") {
                    return Err(anyhow::anyhow!("非法优先级"));
                }
                if let Some(due) = due_at {
                    chrono::DateTime::parse_from_rfc3339(due).map_err(|_| {
                        anyhow::anyhow!("截止时间不是合法的 ISO 8601")
                    })?;
                }
                if !self.projection.lists.iter().any(|list| &list.id == list_id) {
                    return Err(anyhow::anyhow!("unknown_list: 清单不存在"));
                }
                if !self.tags_exist(tag_ids) {
                    return Err(anyhow::anyhow!("unknown_tag: 标签不存在"));
                }
                let task_id = format!("task-{}", &Uuid::new_v4().to_string()[..8]);
                let now = chrono::Local::now().to_rfc3339();
                self.op("task", &task_id, "set", "__created", json!(now))?;
                let order = self.next_order;
                self.next_order += 1.0;
                for (field, value) in [
                    ("title", json!(title)),
                    ("notes", json!(notes)),
                    ("start_at", json!(now)),
                    ("due_at", json!(due_at)),
                    ("remind_at", Value::Null),
                    ("priority", json!(priority)),
                    ("status", json!("todo")),
                    ("list_id", json!(list_id)),
                    ("order", json!(order)),
                ] {
                    self.op("task", &task_id, "set", field, value)?;
                }
                for tag_id in tag_ids {
                    self.op("task", &task_id, "add", "__tag", json!(tag_id))?;
                }
                for text in subtasks {
                    let order = self.next_subtask_order(&task_id);
                    self.add_subtask(&task_id, text, order)?;
                }
                self.result.affected_task_ids.push(task_id);
            }
            AiOperation::UpdateFields {
                task_id, patch, ..
            } => {
                if !self.task_exists(task_id) {
                    return Err(anyhow::anyhow!("任务不存在或不属于当前工作区"));
                }
                for (field, value) in patch {
                    let field = map_field(field);
                    match field {
                        "title" => {
                            let text = value
                                .as_str()
                                .ok_or_else(|| anyhow::anyhow!("title 必须是字符串"))?;
                            if text.trim().is_empty() || text.chars().count() > 500 {
                                return Err(anyhow::anyhow!("title 需为 1–500 个字符"));
                            }
                        }
                        "notes" => {
                            if !value.is_string() && !value.is_null() {
                                return Err(anyhow::anyhow!("notes 必须是字符串"));
                            }
                            if let Some(text) = value.as_str() {
                                if text.chars().count() > 20_000 {
                                    return Err(anyhow::anyhow!("notes 不能超过 20000 个字符"));
                                }
                            }
                        }
                        "start_at" | "due_at" | "remind_at" => {
                            if !value.is_null() {
                                let text = value
                                    .as_str()
                                    .ok_or_else(|| anyhow::anyhow!("时间字段必须是字符串"))?;
                                chrono::DateTime::parse_from_rfc3339(text).map_err(|_| {
                                    anyhow::anyhow!("时间字段不是合法的 ISO 8601")
                                })?;
                            }
                        }
                        "priority" => {
                            if !matches!(value.as_str(), Some("high" | "mid" | "low")) {
                                return Err(anyhow::anyhow!("非法优先级"));
                            }
                        }
                        "list_id" => {
                            let list_id = value
                                .as_str()
                                .ok_or_else(|| anyhow::anyhow!("list_id 必须是字符串"))?;
                            if !self.projection.lists.iter().any(|list| list.id == list_id) {
                                return Err(anyhow::anyhow!("unknown_list: 清单不存在"));
                            }
                        }
                        _ => continue,
                    }
                    self.op("task", task_id, "set", field, value.clone())?;
                    if field == "remind_at" {
                        storage::clear_task_local(self.conn, task_id)?;
                    }
                }
                self.result.affected_task_ids.push(task_id.clone());
            }
            AiOperation::AddSubtasks { task_id, subtasks } => {
                if !self.task_exists(task_id) {
                    return Err(anyhow::anyhow!("任务不存在或不属于当前工作区"));
                }
                for text in subtasks {
                    let order = self.next_subtask_order(task_id);
                    self.add_subtask(task_id, text, order)?;
                }
                self.result.affected_task_ids.push(task_id.clone());
            }
            AiOperation::SetSubtaskDone {
                task_id,
                subtask_id,
                done,
            } => {
                if !self.task_exists(task_id) {
                    return Err(anyhow::anyhow!("任务不存在或不属于当前工作区"));
                }
                self.op(
                    "task",
                    task_id,
                    "set",
                    &format!("__subtask:{subtask_id}:done"),
                    json!(done),
                )?;
                self.result.affected_task_ids.push(task_id.clone());
            }
            AiOperation::AddTags { task_id, tag_ids } => {
                if !self.task_exists(task_id) {
                    return Err(anyhow::anyhow!("任务不存在或不属于当前工作区"));
                }
                if !self.tags_exist(tag_ids) {
                    return Err(anyhow::anyhow!("unknown_tag: 标签不存在"));
                }
                for tag_id in tag_ids {
                    self.op("task", task_id, "add", "__tag", json!(tag_id))?;
                }
                self.result.affected_task_ids.push(task_id.clone());
            }
            AiOperation::RemoveTags { task_id, tag_ids } => {
                if !self.task_exists(task_id) {
                    return Err(anyhow::anyhow!("任务不存在或不属于当前工作区"));
                }
                for tag_id in tag_ids {
                    self.op("task", task_id, "remove", "__tag", json!(tag_id))?;
                }
                self.result.affected_task_ids.push(task_id.clone());
            }
            AiOperation::SetStatus { task_id, status } => {
                if !matches!(status.as_str(), "todo" | "done") {
                    return Err(anyhow::anyhow!("非法状态"));
                }
                if !self.task_exists(task_id) {
                    return Err(anyhow::anyhow!("任务不存在或不属于当前工作区"));
                }
                self.op("task", task_id, "set", "status", json!(status))?;
                self.result.affected_task_ids.push(task_id.clone());
            }
            AiOperation::DeleteTask { task_id } => {
                if !self.task_exists(task_id) {
                    return Err(anyhow::anyhow!("任务不存在或不属于当前工作区"));
                }
                self.op("task", task_id, "set", "__deleted", json!(true))?;
                storage::clear_task_local(self.conn, task_id)?;
                self.result.affected_task_ids.push(task_id.clone());
            }
        }
        Ok(())
    }
}

#[tauri::command]
#[specta::specta]
pub fn ai_apply_plan(
    app: AppHandle,
    state: State<'_, AppState>,
    operations: Vec<AiOperation>,
) -> Result<AiApplyResult, String> {
    if operations.is_empty() {
        return Err("没有可写入的操作".to_string());
    }
    if operations.len() > MAX_OPERATIONS {
        return Err(format!("单批最多 {MAX_OPERATIONS} 个操作"));
    }

    let (settings, workspace_id) = {
        let guard = state
            .db
            .lock()
            .map_err(|_| "数据库连接锁失败，请重试".to_string())?;
        (storage::load_settings(&guard), active_workspace(&guard))
    };
    if operations.iter().any(is_destructive) && !settings.ai_allow_destructive {
        return Err("ai_destructive_disabled: 已关闭 AI 修改完成状态/删除任务".to_string());
    }
    let model = if settings.ai_model.trim().is_empty() {
        "ai".to_string()
    } else {
        settings.ai_model.clone()
    };

    let result = {
        let guard = state
            .db
            .lock()
            .map_err(|_| "数据库连接锁失败，请重试".to_string())?;
        storage::with_transaction(&guard, |conn| {
            let projection = build_projection(conn, &workspace_id)?;
            let device = storage::device_id(conn)?;
            let actor = storage::current_user_id(conn);
            let mut ctx = ApplyCtx {
                conn,
                device,
                actor,
                workspace: workspace_id.clone(),
                projection: &projection,
                model,
                next_order: next_task_order(conn, &workspace_id)?,
                subtask_orders: HashMap::new(),
                result: AiApplyResult {
                    op_ids: Vec::new(),
                    affected_task_ids: Vec::new(),
                },
            };
            for operation in &operations {
                ctx.apply(operation)?;
            }
            Ok(ctx.result)
        })
        .map_err(|error| error.to_string())?
    };

    emit_projection(&app, &workspace_id);
    Ok(result)
}

#[tauri::command]
#[specta::specta]
pub fn ai_parse_plan(
    state: State<'_, AppState>,
    text: String,
    workspace_id: Option<String>,
) -> Result<AiPlanResponse, String> {
    let clean = text.trim();
    if clean.is_empty() {
        return Err("请先粘贴模型输出".to_string());
    }
    let context = load_context(&state, workspace_id, false)?;
    parse_plan_text(clean, &context.plan)
}

/* ------------------------------- 上下文 ------------------------------- */

fn load_context(
    state: &AppState,
    workspace_id: Option<String>,
    require_key: bool,
) -> Result<AiContext, String> {
    let guard = state
        .db
        .lock()
        .map_err(|_| "数据库连接锁失败，请重试".to_string())?;
    let workspace = workspace_id
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .unwrap_or_else(|| active_workspace(&guard));
    let projection = build_projection(&guard, &workspace).map_err(|error| error.to_string())?;
    let settings = storage::load_settings(&guard);
    drop(guard);

    if !projection.workspaces.iter().any(|item| item.id == workspace) {
        return Err("workspace_not_found: 工作区不存在或已被删除".to_string());
    }
    if projection.lists.is_empty() {
        return Err("当前工作区还没有可用清单".to_string());
    }

    let api_key = if require_key {
        if !settings.ai_enabled {
            return Err("AI 助手已关闭，请先在设置中开启 API 直连".to_string());
        }
        Some(read_api_key()?)
    } else {
        None
    };

    Ok(AiContext {
        plan: plan_context(&projection, &settings),
        settings,
        api_key,
    })
}

fn plan_context(projection: &Projection, settings: &Settings) -> PlanContext {
    PlanContext {
        lists: projection
            .lists
            .iter()
            .map(|list| ListRef {
                id: list.id.clone(),
                name: list.name.clone(),
                smart: list.smart.unwrap_or(false),
            })
            .collect(),
        tags: projection
            .tags
            .iter()
            .map(|tag| TagRef {
                id: tag.id.clone(),
                name: tag.name.clone(),
            })
            .collect(),
        // 只把最近的任务作为可修改目标，避免提示词无限膨胀；AI 不得引用列表外的 id。
        tasks: projection
            .tasks
            .iter()
            .take(200)
            .map(|task| TaskRef {
                id: task.id.clone(),
                title: task.title.clone(),
                status: task.status.clone(),
                priority: task.priority.clone(),
                list_id: task.list_id.clone(),
                due_at: task.due_at.clone(),
                tag_ids: task.tag_ids.clone(),
                subtasks: task
                    .subtasks
                    .iter()
                    .map(|sub| SubtaskRef {
                        id: sub.id.clone(),
                        text: sub.text.clone(),
                        done: sub.done,
                    })
                    .collect(),
            })
            .collect(),
        default_priority: settings.default_priority.clone(),
    }
}

fn read_api_key() -> Result<String, String> {
    let key = keyring_entry()?
        .get_password()
        .map_err(|error| format!("读取 API 密钥失败: {error}"))?;
    let clean = key.trim().to_string();
    if clean.is_empty() {
        return Err("请先在设置中配置 API 密钥".to_string());
    }
    Ok(clean)
}

/* ------------------------------- 错误映射 ------------------------------- */

fn map_reqwest_error(error: reqwest::Error) -> String {
    if error.is_timeout() {
        "模型响应超时（60 秒），请稍后重试".to_string()
    } else if error.is_connect() {
        "无法连接模型端点，请检查 API 地址以及本地服务是否已启动".to_string()
    } else {
        format!("模型请求失败: {error}")
    }
}

fn http_error_message(status: reqwest::StatusCode, body: &str) -> String {
    let one_line = body.replace('\n', " ").replace('\r', " ");
    let snippet = truncate_chars(&one_line, 300);
    match status.as_u16() {
        401 | 403 => format!(
            "API 密钥无效或没有访问权限（HTTP {}）",
            status.as_u16()
        ),
        404 => "模型端点不存在（HTTP 404），请检查 API 端点是否需要包含 /v1".to_string(),
        429 => "请求过于频繁或额度不足（HTTP 429），请稍后重试".to_string(),
        500..=599 => format!(
            "模型服务暂时不可用（HTTP {}），请稍后重试",
            status.as_u16()
        ),
        _ => format!(
            "模型请求失败（HTTP {}）：{}",
            status.as_u16(),
            snippet
        ),
    }
}

fn truncate_chars(text: &str, max: usize) -> String {
    if text.chars().count() <= max {
        return text.to_string();
    }
    text.chars().take(max).collect()
}
