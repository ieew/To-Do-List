//! dsh 后台循环：注册设备 → 连 MQTT → 收 nudge/clip → HTTP pull/ack → 剪贴板上行。
//!
//! 设计约束（与 `docs/ime-push-client.md` 一致）：
//! - 顺序固定为「应用 → ack → 推进游标」；ack 失败时保留游标，靠 `dsh_seen` 防重复。
//! - MQTT 只做唤醒；权威数据永远来自 pull。
//! - 剪贴板默认不落库，离线即丢；上行必须用设备票据，服务端据此排除来源。

use super::client::DshApi;
use super::clipboard;
use super::mqtt::{self, MqttEvent};
use super::protocol::{self, DshMessage};
use crate::core_domain::Settings;
use crate::logging;
use crate::reminder::scheduler::{self, DndWindow};
use crate::storage;
use crate::sync::engine;
use crate::AppState;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::time::Duration;
use tauri::{AppHandle, Manager};
use tokio::sync::mpsc;
use tauri::async_runtime::JoinHandle;

const TICK: Duration = Duration::from_millis(1000);
const PULL_INTERVAL_CONNECTED_SECS: i64 = 120;
const PULL_INTERVAL_DEGRADED_SECS: i64 = 30;
const TICKET_REFRESH_MS: i64 = 8 * 60 * 1000;
const PULL_LIMIT: usize = 100;
const PULL_MAX_PAGES: usize = 20;
const SEEN_RETENTION_MS: i64 = 7 * 24 * 3600 * 1000;

#[derive(Clone, Debug, Serialize, Deserialize)]
struct DeviceMeta {
    server_url: String,
    user_id: String,
    device_id: String,
}

pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        run(app).await;
    });
}

pub fn request_sync(state: &AppState) {
    if let Ok(mut runtime) = state.dsh.runtime.lock() {
        runtime.force_pull = true;
    }
    state.dsh.wake();
}

pub fn request_reconnect(state: &AppState) {
    if let Ok(mut runtime) = state.dsh.runtime.lock() {
        runtime.force_register = true;
        runtime.force_pull = true;
        runtime.mqtt_reconnect = true;
    }
    state.dsh.wake();
}

async fn run(app: AppHandle) {
    let mut clipboard_last: Option<String> = None;
    let mut clip_initialized = false;
    let mut mqtt_handle: Option<JoinHandle<()>> = None;
    let mut mqtt_rx: Option<mpsc::Receiver<MqttEvent>> = None;
    let mut mqtt_backoff = 2u64;
    let mut mqtt_retry_at = 0i64;
    let mut next_pull = 0i64;
    let mut next_refresh = 0i64;

    loop {
        let state = app.state::<AppState>();
        let signed_in = is_signed_in(&state);
        let settings = settings_of(&state);
        if let Ok(mut runtime) = state.dsh.runtime.lock() {
            runtime.status.signed_in = signed_in;
            runtime.status.enabled = signed_in && (settings.push_enabled || settings.clip_enabled);
        }

        if !signed_in {
            abort_mqtt(&mut mqtt_handle, &mut mqtt_rx);
            clip_initialized = false;
            clipboard_last = None;
            if let Ok(mut runtime) = state.dsh.runtime.lock() {
                runtime.status.signed_in = false;
                runtime.status.enabled = false;
                runtime.status.mqtt_connected = false;
                runtime.status.degraded = false;
            }
            state.dsh.emit(&app);
            state.dsh.notify.notified().await;
            continue;
        }

        if !settings.push_enabled && !settings.clip_enabled {
            abort_mqtt(&mut mqtt_handle, &mut mqtt_rx);
            if let Ok(mut runtime) = state.dsh.runtime.lock() {
                runtime.status.enabled = false;
                runtime.status.mqtt_connected = false;
                runtime.status.degraded = false;
            }
            state.dsh.emit(&app);
            sleep_or_wake(&state, Duration::from_secs(3)).await;
            continue;
        }

        let now = storage::now_ms();
        let force_register = state
            .dsh
            .runtime
            .lock()
            .map(|runtime| runtime.force_register)
            .unwrap_or(true);
        if force_register {
            // 手动重连/票据失效：先断开旧连接，注册成功后再用新票据重连。
            abort_mqtt(&mut mqtt_handle, &mut mqtt_rx);
        }
        let need_register = state
            .dsh
            .runtime
            .lock()
            .map(|runtime| {
                runtime.force_register
                    || runtime.ticket.is_none()
                    || runtime.ticket_expires_at <= now + 60_000
            })
            .unwrap_or(true);

        if need_register {
            match register_device(&app, &state).await {
                Ok(()) => {
                    next_refresh = storage::now_ms() + TICKET_REFRESH_MS;
                    next_pull = 0;
                    let reconnect = state
                        .dsh
                        .runtime
                        .lock()
                        .map(|mut runtime| {
                            let value = runtime.mqtt_reconnect;
                            runtime.mqtt_reconnect = false;
                            value
                        })
                        .unwrap_or(false);
                    if reconnect {
                        mqtt_backoff = 2;
                        mqtt_retry_at = 0;
                    }
                }
                Err(error) => {
                    set_error(&state, error.clone());
                    logging::warn("dsh", format!("设备注册失败: {error}"));
                    state.dsh.emit(&app);
                    sleep_or_wake(&state, Duration::from_secs(5)).await;
                    continue;
                }
            }
        }

        // 启动 MQTT 任务（票据过期后 worker 会先重新注册，再在这里用新票重连）。
        if mqtt_handle.is_none() && storage::now_ms() >= mqtt_retry_at {
            let endpoint = state
                .dsh
                .runtime
                .lock()
                .ok()
                .and_then(|runtime| runtime.endpoint.clone());
            let ticket = state
                .dsh
                .runtime
                .lock()
                .ok()
                .and_then(|runtime| runtime.ticket.clone())
                .unwrap_or_default();
            if let Some(endpoint) = endpoint {
                if endpoint.enabled && !endpoint.tls && !ticket.is_empty() {
                    let (tx, rx) = mpsc::channel(32);
                    mqtt_rx = Some(rx);
                    let task_endpoint = endpoint.clone();
                    mqtt_handle = Some(tauri::async_runtime::spawn(async move {
                        if let Err(error) = mqtt::run(task_endpoint, ticket, tx).await {
                            logging::warn("dsh", error);
                        }
                    }));
                }
            }
        }

        if settings.clip_enabled {
            if !clip_initialized {
                // 开启同步的瞬间只记录基线，避免把已有的旧剪贴板内容上传。
                clipboard_last = clipboard::read_text(&app);
                clip_initialized = true;
            }
        } else {
            clip_initialized = false;
            clipboard_last = None;
        }

        tokio::select! {
            _ = tokio::time::sleep(TICK) => {}
            _ = state.dsh.notify.notified() => {
                // 手动同步/登录/设置变更：下一轮立即 pull。
                next_pull = 0;
            }
            event = recv_mqtt(&mut mqtt_rx) => {
                match event {
                    Some(MqttEvent::Connected) => {
                        mqtt_backoff = 2;
                        mqtt_retry_at = 0;
                        next_pull = 0;
                        // 断线期间可能错过 sync 信号：连上先补一次 op 同步。
                        state.sync.wake();
                        if let Ok(mut runtime) = state.dsh.runtime.lock() {
                            runtime.status.mqtt_enabled = true;
                            runtime.status.mqtt_connected = true;
                            runtime.status.degraded = false;
                            runtime.status.last_error = None;
                        }
                        logging::info("dsh", "MQTT 已连接");
                        state.dsh.emit(&app);
                    }
                    Some(MqttEvent::Nudge) => {
                        next_pull = 0;
                    }
                    Some(MqttEvent::Sync { seq, workspaces }) => {
                        // 其它设备写了 op：立刻唤醒同步 worker，不等到下个轮询周期。
                        state.sync.wake();
                        logging::info(
                            "dsh",
                            format!(
                                "收到数据变更信号 seq={seq} ws={}，立即同步",
                                workspaces.join(",")
                            ),
                        );
                    }
                    Some(MqttEvent::Clip { id, text, fp }) => {
                        apply_clip_event(&app, &state, &id, &text, &fp).await;
                    }
                    None => {
                        // 任务结束（连接断开/退避）：清掉句柄，等下一次重连。
                        mqtt_handle = None;
                        mqtt_rx = None;
                        let now = storage::now_ms();
                        mqtt_retry_at = now + (mqtt_backoff as i64) * 1000;
                        mqtt_backoff = (mqtt_backoff * 2).min(60);
                        if let Ok(mut runtime) = state.dsh.runtime.lock() {
                            runtime.status.mqtt_connected = false;
                            runtime.status.degraded = true;
                        }
                        state.dsh.emit(&app);
                    }
                }
            }
        }

        let now = storage::now_ms();
        if now >= next_refresh {
            if let Err(error) = register_device(&app, &state).await {
                logging::warn("dsh", format!("票据续期失败: {error}"));
                set_error(&state, error);
            }
            next_refresh = storage::now_ms() + TICKET_REFRESH_MS;
        }

        if now >= next_pull
            || state
                .dsh
                .runtime
                .lock()
                .map(|runtime| runtime.force_pull)
                .unwrap_or(false)
        {
            if let Err(error) = pull_round(&app, &state).await {
                logging::warn("dsh", format!("pull 失败: {error}"));
                set_error(&state, error);
            }
            let connected = state
                .dsh
                .runtime
                .lock()
                .map(|runtime| runtime.status.mqtt_connected)
                .unwrap_or(false);
            let interval = if connected {
                PULL_INTERVAL_CONNECTED_SECS
            } else {
                PULL_INTERVAL_DEGRADED_SECS
            };
            next_pull = storage::now_ms() + interval * 1000;
            if let Ok(mut runtime) = state.dsh.runtime.lock() {
                runtime.force_pull = false;
            }
        }

        poll_clipboard(&app, &state, &settings, &mut clipboard_last).await;
        state.dsh.emit(&app);
    }
}

async fn sleep_or_wake(state: &AppState, duration: Duration) {
    tokio::select! {
        _ = tokio::time::sleep(duration) => {}
        _ = state.dsh.notify.notified() => {}
    }
}

async fn recv_mqtt(rx: &mut Option<mpsc::Receiver<MqttEvent>>) -> Option<MqttEvent> {
    match rx {
        Some(receiver) => receiver.recv().await,
        None => std::future::pending().await,
    }
}

fn abort_mqtt(
    handle: &mut Option<JoinHandle<()>>,
    rx: &mut Option<mpsc::Receiver<MqttEvent>>,
) {
    if let Some(handle) = handle.take() {
        handle.abort();
    }
    *rx = None;
}

fn is_signed_in(state: &AppState) -> bool {
    state
        .sync
        .runtime
        .lock()
        .map(|runtime| runtime.signed_in)
        .unwrap_or(false)
}

fn settings_of(state: &AppState) -> Settings {
    state
        .db
        .lock()
        .map(|conn| storage::load_settings(&conn))
        .unwrap_or_default()
}

fn set_error(state: &AppState, error: String) {
    if let Ok(mut runtime) = state.dsh.runtime.lock() {
        runtime.status.last_error = Some(error);
    }
}

fn device_meta(state: &AppState) -> Option<DeviceMeta> {
    let conn = state.db.lock().ok()?;
    storage::meta_get(&conn, "dsh_device").and_then(|raw| serde_json::from_str(&raw).ok())
}

fn save_device_meta(state: &AppState, meta: &DeviceMeta) {
    if let Ok(conn) = state.db.lock() {
        if let Ok(raw) = serde_json::to_string(meta) {
            let _ = storage::meta_set(&conn, "dsh_device", &raw);
        }
    }
}

fn cursor(state: &AppState) -> i64 {
    state
        .db
        .lock()
        .ok()
        .and_then(|conn| storage::meta_get(&conn, "dsh_cursor"))
        .and_then(|raw| raw.parse::<i64>().ok())
        .unwrap_or(0)
}

fn set_cursor(state: &AppState, value: i64) {
    if let Ok(conn) = state.db.lock() {
        let _ = storage::meta_set(&conn, "dsh_cursor", &value.to_string());
    }
}

/// 注册/续期设备。调用方负责在失败时退避；成功后 ticket 只在内存里。
pub async fn register_device(app: &AppHandle, state: &AppState) -> Result<(), String> {
    let Some((_, session)) = engine::authorized_session(state)
        .await
        .map_err(|error| error.message)?
    else {
        return Err("未登录".into());
    };
    let api = DshApi::new(&session.account.server_url).map_err(|error| error.message)?;
    let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Windows".to_string());
    let existing = device_meta(state).filter(|meta| {
        meta.server_url == session.account.server_url && meta.user_id == session.account.user_id
    });
    let target = existing.as_ref().map(|meta| meta.device_id.clone());

    let response = match api
        .register_device(
            &session.tokens.access_token,
            &name,
            "windows",
            target.as_deref(),
        )
        .await
    {
        Ok(response) => response,
        Err(error) if error.status == 404 || error.status == 409 => {
            // 设备属于别的账户或被吊销：换一个新 device_id。
            api.register_device(&session.tokens.access_token, &name, "windows", None)
                .await
                .map_err(|error| error.message)?
        }
        Err(error) => return Err(error.message),
    };

    save_device_meta(
        state,
        &DeviceMeta {
            server_url: session.account.server_url.clone(),
            user_id: session.account.user_id.clone(),
            device_id: response.device_id.clone(),
        },
    );

    if let Ok(mut runtime) = state.dsh.runtime.lock() {
        runtime.device_id = Some(response.device_id.clone());
        runtime.ticket = Some(response.ticket.clone());
        runtime.ticket_expires_at = response.ticket_expires_at;
        runtime.endpoint = Some(response.mqtt.clone());
        runtime.force_register = false;
        runtime.status.device_id = Some(response.device_id.clone());
        runtime.status.mqtt_enabled = response.mqtt.enabled;
        runtime.status.signed_in = true;
        runtime.status.enabled = true;
        // MQTT 还没连上（或服务端关闭/TLS 不支持）时先按降级展示。
        runtime.status.degraded = !response.mqtt.enabled || response.mqtt.tls;
        runtime.status.last_error = None;
    }
    logging::info(
        "dsh",
        format!(
            "设备已注册: {} (mqtt {}:{} enabled={} tls={})",
            response.device_id, response.mqtt.host, response.mqtt.port, response.mqtt.enabled,
            response.mqtt.tls
        ),
    );
    state.dsh.emit(app);
    Ok(())
}

async fn pull_round(app: &AppHandle, state: &AppState) -> Result<(), String> {
    let (device_id, ticket, expires_at) = state
        .dsh
        .runtime
        .lock()
        .map(|runtime| {
            (
                runtime.device_id.clone(),
                runtime.ticket.clone(),
                runtime.ticket_expires_at,
            )
        })
        .map_err(|_| "dsh 状态锁失败".to_string())?;
    let (Some(device_id), Some(ticket)) = (device_id, ticket) else {
        if let Ok(mut runtime) = state.dsh.runtime.lock() {
            runtime.force_register = true;
        }
        return Err("设备尚未注册".into());
    };
    if expires_at <= storage::now_ms() {
        if let Ok(mut runtime) = state.dsh.runtime.lock() {
            runtime.force_register = true;
        }
        return Err("设备票据已过期".into());
    }

    let server_url = state
        .db
        .lock()
        .ok()
        .and_then(|conn| crate::sync::session::account(&conn))
        .map(|account| account.server_url)
        .ok_or_else(|| "未登录".to_string())?;
    let api = DshApi::new(&server_url).map_err(|error| error.message)?;

    let mut since = cursor(state);
    let mut pages = 0usize;
    loop {
        let page = api
            .pull_as_device(&device_id, &ticket, since, PULL_LIMIT)
            .await
            .map_err(|error| error.message)?;

        if page.messages.is_empty() {
            break;
        }

        let task_states = load_task_states(state, &page.messages);
        let mut acked: Vec<i64> = Vec::new();
        let mut last_ok = since;
        let mut failure: Option<String> = None;

        for message in &page.messages {
            match apply_message(app, state, message, &task_states).await {
                Ok(()) => {
                    acked.push(message.seq);
                    last_ok = message.seq;
                }
                Err(error) => {
                    failure = Some(error);
                    break;
                }
            }
        }

        if !acked.is_empty() {
            api.ack_as_device(&device_id, &ticket, &acked)
                .await
                .map_err(|error| error.message)?;
            since = last_ok;
            set_cursor(state, since);
        }

        if let Some(error) = failure {
            return Err(error);
        }

        since = page.next_seq;
        set_cursor(state, since);
        pages += 1;
        if !page.has_more || pages >= PULL_MAX_PAGES {
            break;
        }
    }

    if let Ok(conn) = state.db.lock() {
        let _ = storage::dsh_seen_prune(&conn, storage::now_ms() - SEEN_RETENTION_MS);
    }
    if let Ok(mut runtime) = state.dsh.runtime.lock() {
        runtime.status.last_pull_at = Some(storage::now_ms());
        runtime.status.last_error = None;
    }
    Ok(())
}

#[derive(Clone, Debug)]
struct LocalTaskState {
    status: String,
    remind_at: Option<i64>,
}

/// 提醒类消息需要知道本地任务的完成状态与当前提醒时间：
/// - 已完成/已删除的任务不再弹；
/// - `remind_at` 已被改掉的旧服务端消息直接丢弃（避免改早后收到旧时间的补发）。
fn load_task_states(state: &AppState, messages: &[DshMessage]) -> HashMap<String, LocalTaskState> {
    let mut workspaces: HashSet<String> = HashSet::new();
    for message in messages {
        if message.kind == "clip" {
            continue;
        }
        let notice = protocol::parse_notice(&message.payload);
        if let Some(workspace) = notice.workspace_id {
            workspaces.insert(workspace);
        }
    }
    if workspaces.is_empty() {
        if let Some(active) = state
            .db
            .lock()
            .ok()
            .and_then(|conn| storage::active_workspace_id(&conn))
        {
            workspaces.insert(active);
        }
    }

    let mut states = HashMap::new();
    for workspace in workspaces {
        if let Ok(projection) = crate::commands::build_projection_cached(state, &workspace) {
            for task in projection.tasks {
                states.insert(
                    task.id,
                    LocalTaskState {
                        status: task.status,
                        remind_at: task.remind_at.as_deref().and_then(protocol::parse_rfc3339_ms),
                    },
                );
            }
        }
    }
    states
}

async fn apply_message(
    app: &AppHandle,
    state: &AppState,
    message: &DshMessage,
    task_states: &HashMap<String, LocalTaskState>,
) -> Result<(), String> {
    let seen = state
        .db
        .lock()
        .ok()
        .map(|conn| storage::dsh_seen_contains(&conn, &message.msg_id).unwrap_or(false))
        .unwrap_or(false);
    if seen {
        return Ok(());
    }

    let settings = settings_of(state);
    if message.kind == "clip" {
        if settings.clip_enabled {
            if let Some(text) = protocol::clip_text(&message.payload) {
                apply_remote_clip(app, state, &text)?;
            }
        }
        mark_seen(state, &message.msg_id);
        return Ok(());
    }

    if !settings.push_enabled || !settings.notifications {
        mark_seen(state, &message.msg_id);
        return Ok(());
    }

    let notice = protocol::parse_notice(&message.payload);

    if notice.is_reminder {
        if let Some(task_id) = notice.task_id.as_deref() {
            match task_states.get(task_id) {
                Some(local) if local.status == "done" => {
                    mark_seen(state, &message.msg_id);
                    return Ok(());
                }
                Some(local) => {
                    if let Some(message_at) = notice.remind_at_ms {
                        if local.remind_at != Some(message_at) {
                            // 本地 remind_at 已改变，这条是旧时间的补发。
                            mark_seen(state, &message.msg_id);
                            return Ok(());
                        }
                    }
                }
                // 投影已加载但找不到任务：多半已被删除，不再弹过期提醒。
                None if !task_states.is_empty() => {
                    mark_seen(state, &message.msg_id);
                    return Ok(());
                }
                _ => {}
            }
        }

        if let Some(remind_at) = notice.remind_at_ms {
            let local = match notice.task_id.as_deref() {
                Some(task_id) => state
                    .db
                    .lock()
                    .ok()
                    .and_then(|conn| storage::task_local_get(&conn, task_id).ok())
                    .unwrap_or_default(),
                None => Default::default(),
            };
            if protocol::reminder_already_fired(local.last_fired_at, remind_at) {
                mark_seen(state, &message.msg_id);
                return Ok(());
            }

            let now = storage::now_ms();
            if in_dnd(&settings) {
                if let (Some(task_id), Ok(conn)) = (notice.task_id.as_deref(), state.db.lock()) {
                    let _ = storage::add_deferred_reminder(&conn, task_id, remind_at, now);
                }
                mark_seen(state, &message.msg_id);
                return Ok(());
            }

            let title = format!("任务提醒 · {}", notice.title);
            let body = if notice.body.is_empty() {
                "任务到期提醒".to_string()
            } else {
                notice.body.clone()
            };
            crate::reminder::notifier::notify_remote(
                app,
                notice.task_id.as_deref(),
                notice.workspace_id.as_deref(),
                &title,
                &body,
                notice.level,
            )?;
            if let (Some(task_id), Ok(conn)) = (notice.task_id.as_deref(), state.db.lock()) {
                let _ = storage::mark_reminder_fired(&conn, task_id, remind_at);
            }
            mark_seen(state, &message.msg_id);
            return Ok(());
        }
    }

    let title = if notice.is_reminder {
        format!("任务提醒 · {}", notice.title)
    } else {
        notice.title.clone()
    };
    let body = if notice.body.is_empty() {
        "你有新的推送".to_string()
    } else {
        notice.body.clone()
    };
    if in_dnd(&settings) {
        mark_seen(state, &message.msg_id);
        return Ok(());
    }
    crate::reminder::notifier::notify_remote(
        app,
        notice.task_id.as_deref(),
        notice.workspace_id.as_deref(),
        &title,
        &body,
        notice.level,
    )?;
    mark_seen(state, &message.msg_id);
    Ok(())
}

/// MQTT 直传的剪贴板没有 seq/ack，只需要应用并记录指纹防回流。
async fn apply_clip_event(app: &AppHandle, state: &AppState, id: &str, text: &str, _fp: &str) {
    let settings = settings_of(state);
    if !settings.clip_enabled {
        return;
    }
    if let Err(error) = apply_remote_clip(app, state, text) {
        logging::warn("dsh", format!("应用远端剪贴板失败: {error}"));
        return;
    }
    if !id.is_empty() {
        mark_seen(state, id);
    }
}

fn apply_remote_clip(app: &AppHandle, state: &AppState, text: &str) -> Result<(), String> {
    if !protocol::clip_within_limit(text) {
        return Ok(());
    }
    if clipboard::read_text(app).as_deref() == Some(text) {
        return Ok(());
    }
    clipboard::write_text(app, text)?;
    if let Ok(mut runtime) = state.dsh.runtime.lock() {
        runtime.status.last_clip_received_at = Some(storage::now_ms());
        runtime.clip_last_applied = Some(text.to_string());
    }
    logging::info(
        "dsh",
        format!("已写入远端剪贴板（{} 字节）", text.len()),
    );
    Ok(())
}

fn in_dnd(settings: &Settings) -> bool {
    let window = DndWindow::from_settings(
        settings.dnd_enabled,
        &settings.dnd_start,
        &settings.dnd_end,
    );
    window.contains(scheduler::local_minutes(
        storage::now_ms(),
        scheduler::local_offset_seconds(),
    ))
}

fn mark_seen(state: &AppState, msg_id: &str) {
    if let Ok(conn) = state.db.lock() {
        let _ = storage::dsh_seen_insert(&conn, msg_id, storage::now_ms());
    }
}

async fn poll_clipboard(
    app: &AppHandle,
    state: &AppState,
    settings: &Settings,
    last_seen: &mut Option<String>,
) {
    if !settings.clip_enabled {
        return;
    }
    let Some(text) = clipboard::read_text(app) else {
        return;
    };
    if last_seen.as_deref() == Some(text.as_str()) {
        return;
    }

    // 远端刚写入的内容：只更新本地水位，不再上传回去。
    let remote_applied = state
        .dsh
        .runtime
        .lock()
        .ok()
        .and_then(|mut runtime| runtime.clip_last_applied.take());
    if remote_applied.as_deref() == Some(text.as_str()) {
        *last_seen = Some(text);
        return;
    }

    if !protocol::clip_within_limit(&text) {
        *last_seen = Some(text.clone());
        logging::warn(
            "dsh",
            format!("剪贴板文本超过 32KiB，已跳过（{} 字节）", text.len()),
        );
        return;
    }

    let (device_id, ticket, expires_at) = match state.dsh.runtime.lock() {
        Ok(runtime) => (
            runtime.device_id.clone(),
            runtime.ticket.clone(),
            runtime.ticket_expires_at,
        ),
        Err(_) => return,
    };
    let (Some(device_id), Some(ticket)) = (device_id, ticket) else {
        // 票据还没就绪（正在注册/重连）：不推进水位，下一轮再试。
        return;
    };
    if expires_at <= storage::now_ms() {
        if let Ok(mut runtime) = state.dsh.runtime.lock() {
            runtime.force_register = true;
        }
        return;
    }

    let server_url = state
        .db
        .lock()
        .ok()
        .and_then(|conn| crate::sync::session::account(&conn))
        .map(|account| account.server_url)
        .unwrap_or_default();
    let api = match DshApi::new(&server_url) {
        Ok(api) => api,
        Err(error) => {
            logging::warn("dsh", format!("剪贴板上行初始化失败: {}", error.message));
            return;
        }
    };

    // 从这里开始记录水位：上行失败（429/网络）按设计不重试。
    *last_seen = Some(text.clone());
    let fp = protocol::fingerprint(&text);
    let msg_id = format!("win-clip-{}", uuid::Uuid::new_v4());
    match api
        .clip_as_device(&device_id, &ticket, &text, &fp, &msg_id)
        .await
    {
        Ok(response) => {
            if let Ok(mut runtime) = state.dsh.runtime.lock() {
                runtime.status.last_clip_sent_at = Some(storage::now_ms());
                runtime.status.last_error = None;
            }
            logging::info(
                "dsh",
                format!(
                    "剪贴板上行成功：{} 字节，送达 {} 台设备",
                    text.len(),
                    response.delivered
                ),
            );
        }
        Err(error) if error.status == 401 || error.status == 403 => {
            if let Ok(mut runtime) = state.dsh.runtime.lock() {
                runtime.force_register = true;
            }
            logging::warn("dsh", format!("剪贴板票据失效，准备重新注册: {error}"));
        }
        Err(error) => {
            logging::warn("dsh", format!("剪贴板上行失败（不重试）: {error}"));
        }
    }
}

/// 设置页「发送当前剪贴板」。
pub async fn send_clipboard_now(app: &AppHandle) -> Result<String, String> {
    let state = app.state::<AppState>();
    let text = clipboard::read_text(app).ok_or_else(|| "剪贴板里没有文本".to_string())?;
    if !protocol::clip_within_limit(&text) {
        return Err("剪贴板文本为空或超过 32KiB".into());
    }

    let need_register = {
        let runtime = state
            .dsh
            .runtime
            .lock()
            .map_err(|_| "dsh 状态锁失败".to_string())?;
        runtime.ticket.is_none() || runtime.ticket_expires_at <= storage::now_ms()
    };
    if need_register {
        register_device(app, &state).await?;
    }

    let (device_id, ticket) = {
        let runtime = state
            .dsh
            .runtime
            .lock()
            .map_err(|_| "dsh 状态锁失败".to_string())?;
        (
            runtime.device_id.clone(),
            runtime.ticket.clone(),
        )
    };
    let (device_id, ticket) = match (device_id, ticket) {
        (Some(device_id), Some(ticket)) => (device_id, ticket),
        _ => return Err("设备票据不可用，请先登录并等待通道连接".into()),
    };

    let server_url = state
        .db
        .lock()
        .ok()
        .and_then(|conn| crate::sync::session::account(&conn))
        .map(|account| account.server_url)
        .ok_or_else(|| "未登录".to_string())?;
    let api = DshApi::new(&server_url).map_err(|error| error.message)?;
    let fp = protocol::fingerprint(&text);
    let msg_id = format!("win-clip-{}", uuid::Uuid::new_v4());
    let response = api
        .clip_as_device(&device_id, &ticket, &text, &fp, &msg_id)
        .await
        .map_err(|error| error.message)?;
    if let Ok(mut runtime) = state.dsh.runtime.lock() {
        runtime.status.last_clip_sent_at = Some(storage::now_ms());
        runtime.clip_last_applied = Some(text.clone());
    }
    Ok(format!(
        "已发送 {} 字节，送达 {} 台设备",
        text.len(),
        response.delivered
    ))
}

/// 设置页「发送测试通知」：走 push 广播，自己也会收到。
pub async fn send_test_notification(app: &AppHandle) -> Result<(), String> {
    let state = app.state::<AppState>();
    let Some((_api, session)) = engine::authorized_session(&state)
        .await
        .map_err(|error| error.message)?
    else {
        return Err("未登录".into());
    };
    let dsh = DshApi::new(&session.account.server_url).map_err(|error| error.message)?;
    let msg_id = format!("win-test-{}", uuid::Uuid::new_v4());
    dsh.push_notify(
        &session.tokens.access_token,
        "测试通知",
        "来自 Windows 客户端的测试推送",
        3,
        &msg_id,
    )
    .await
    .map_err(|error| error.message)?;
    Ok(())
}
