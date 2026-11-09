//! AI 任务规划命令层：OpenAI 兼容 API 直连 + 网页版提示词桥接。
//!
//! 纯提示词、JSON 提取与 schema 归一化逻辑在 `todo-ai-core` crate 中，便于独立测试。
//! 本模块只负责读取本地上下文、访问 keyring、发起 HTTP 请求和映射错误。

use crate::commands::{active_workspace, build_projection, keyring_entry};
use crate::core_domain::{Projection, Settings};
use crate::storage;
use crate::AppState;
use serde::Serialize;
use serde_json::Value;
use std::time::Duration;
use tauri::State;
use todo_ai_core::{
    build_api_messages, build_web_prompt, chat_completions_url, extract_chat_content,
    parse_plan_text, AiPlanResponse, ListRef, PlanContext, TagRef,
};

const REQUEST_TIMEOUT_SECS: u64 = 60;

struct AiContext {
    plan: PlanContext,
    settings: Settings,
    api_key: Option<String>,
}

#[derive(Serialize)]
struct ChatRequest<'a> {
    model: &'a str,
    messages: Vec<ChatRequestMessage<'a>>,
    temperature: f32,
    stream: bool,
}

#[derive(Serialize)]
struct ChatRequestMessage<'a> {
    role: &'a str,
    content: &'a str,
}

/* ------------------------------- 命令 ------------------------------- */

#[tauri::command]
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
    let api_key = context
        .api_key
        .clone()
        .ok_or_else(|| "请先在设置中配置 API 密钥".to_string())?;
    let model = context.settings.ai_model.trim().to_string();
    if model.is_empty() {
        return Err("请先在设置中填写模型名".to_string());
    }
    let endpoint = chat_completions_url(&context.settings.ai_endpoint)?;
    let (system, user) = build_api_messages(&context.plan, &clean);
    let request = ChatRequest {
        model: &model,
        messages: vec![
            ChatRequestMessage {
                role: "system",
                content: &system,
            },
            ChatRequestMessage {
                role: "user",
                content: &user,
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
    let content = extract_chat_content(&payload)?;
    parse_plan_text(&content, &context.plan)
}

#[tauri::command]
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
