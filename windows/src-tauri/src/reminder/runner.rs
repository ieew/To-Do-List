//! 桌面提醒循环：每 30 秒重算一次计划，睡眠/唤醒与改系统时间都由 tick 自然覆盖。

use super::scheduler::{self, DndWindow, ReminderTask};
use super::notifier;
use crate::logging;
use crate::storage;
use crate::AppState;
use std::collections::{HashMap, HashSet};
use std::time::Duration;
use tauri::{AppHandle, Manager};

const TICK: Duration = Duration::from_secs(30);
/// 触发时间晚于计划 5 分钟以上时按「补发提醒」措辞。
const LATE_THRESHOLD_MS: i64 = 5 * 60 * 1000;

pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        loop {
            {
                let state = app.state::<AppState>();
                tick(&app, &state);
            }
            let state = app.state::<AppState>();
            tokio::select! {
                _ = tokio::time::sleep(TICK) => {}
                _ = state.reminder_wake.notified() => {}
            }
        }
    });
}

/// 本地写入或设置变更后立即唤醒一次，不必等满 30 秒。
pub fn wake(app: &AppHandle) {
    if let Some(state) = app.try_state::<AppState>() {
        state.reminder_wake.notify_one();
    }
}

fn tick(app: &AppHandle, state: &AppState) {
    let started = storage::now_ms();
    let now = started;
    let offset = scheduler::local_offset_seconds();

    let (settings, workspace, locals) = {
        let Ok(conn) = state.db.lock() else {
            logging::warn("reminder", "读取数据库锁失败，跳过本轮");
            return;
        };
        (
            storage::load_settings(&conn),
            storage::active_workspace_id(&conn).unwrap_or_else(|| "ws-me".into()),
            storage::task_local_all(&conn).unwrap_or_default(),
        )
    };

    // 提醒必须覆盖全部工作区，否则切到别的工作区后旧提醒会静默失效。
    // 投影缓存按 workspace 分开保存，这里逐工作区取一遍再按 task_id 去重。
    let Ok(active) = crate::commands::build_projection_cached(state, &workspace) else {
        logging::warn("reminder", "构建投影失败，跳过本轮");
        return;
    };
    let mut workspace_ids: Vec<(String, String)> = active
        .workspaces
        .iter()
        .map(|item| (item.id.clone(), item.name.clone()))
        .collect();
    if !workspace_ids.iter().any(|(id, _)| id == &workspace) {
        workspace_ids.push((workspace.clone(), "默认".into()));
    }

    let mut reminders: Vec<ReminderTask> = Vec::new();
    let mut task_meta: HashMap<String, (String, String)> = HashMap::new();
    let mut seen: HashSet<String> = HashSet::new();
    let workspace_total = workspace_ids.len();

    for (ws_id, ws_name) in workspace_ids {
        let projection = if ws_id == workspace {
            active.clone()
        } else {
            match crate::commands::build_projection_cached(state, &ws_id) {
                Ok(value) => value,
                Err(error) => {
                    logging::warn("reminder", format!("工作区 {ws_id} 投影失败: {error}"));
                    continue;
                }
            }
        };

        for task in projection.tasks {
            if !seen.insert(task.id.clone()) {
                continue;
            }
            let Some(remind_at) = task.remind_at.as_deref().and_then(parse_iso_ms) else {
                continue;
            };
            let local = locals.get(&task.id).copied().unwrap_or_default();
            task_meta.insert(task.id.clone(), (task.title.clone(), ws_name.clone()));
            reminders.push(ReminderTask {
                task_id: task.id,
                title: task.title,
                status: task.status,
                remind_at,
                due_at: task.due_at.as_deref().and_then(parse_iso_ms),
                last_fired_at: local.last_fired_at,
                snooze_until: local.snooze_until,
            });
        }
    }

    let dnd = DndWindow::from_settings(settings.dnd_enabled, &settings.dnd_start, &settings.dnd_end);
    let plan = scheduler::plan(&reminders, now, offset, settings.reminder_lead, dnd);

    let mut to_notify: Vec<(String, Option<String>, String, i64, bool, bool)> = Vec::new();
    let mut deferred_flush: Vec<(String, i64)> = Vec::new();
    let mut marked_now: Vec<(String, i64)> = Vec::new();

    {
        let Ok(conn) = state.db.lock() else {
            logging::warn("reminder", "写入数据库锁失败，跳过本轮");
            return;
        };

        for item in &plan.drop {
            let _ = storage::mark_reminder_fired(&conn, &item.task_id, item.remind_at);
        }
        for item in &plan.defer {
            let _ = storage::add_deferred_reminder(&conn, &item.task_id, item.remind_at, now);
        }

        if !settings.notifications {
            // 通知关闭时仍推进标记，避免重新打开后一次性轰炸；只是不展示。
            for item in &plan.fire {
                let _ = storage::mark_reminder_fired(&conn, &item.task_id, item.remind_at);
            }
        } else {
            for item in &plan.fire {
                let late = now > item.remind_at.saturating_add(LATE_THRESHOLD_MS);
                let (_, workspace_name) = task_meta
                    .get(&item.task_id)
                    .cloned()
                    .unwrap_or((item.title.clone(), "默认".into()));
                to_notify.push((
                    item.task_id.clone(),
                    Some(workspace_name),
                    item.title.clone(),
                    item.remind_at,
                    item.overdue,
                    late,
                ));
            }
        }

        // 免打扰结束后合并补弹一次，避免逐条轰炸。
        if !dnd.contains(scheduler::local_minutes(now, offset)) {
            if let Ok(deferred) = storage::deferred_reminders(&conn) {
                if !deferred.is_empty() {
                    if settings.notifications {
                        deferred_flush = deferred;
                    } else {
                        let cleared = storage::with_transaction(&conn, |tx| {
                            for (task_id, remind_at) in &deferred {
                                storage::mark_reminder_fired(tx, task_id, *remind_at)?;
                            }
                            storage::clear_deferred_reminders(tx)
                        });
                        if cleared.is_ok() {
                            marked_now.extend(deferred.iter().cloned());
                        }
                    }
                }
            }
        }
    }

    if settings.notifications {
        for (task_id, workspace_id, title, remind_at, overdue, late) in to_notify {
            match notifier::notify_task(
                app,
                &task_id,
                workspace_id.as_deref(),
                &title,
                overdue,
                late,
            ) {
                Ok(()) => marked_now.push((task_id, remind_at)),
                Err(error) => {
                    // 展示失败时不落标记：下一轮还会重试同一条提醒。
                    logging::error(
                        "reminder",
                        format!("任务 {task_id} 通知发送失败，将在下一轮重试: {error}"),
                    );
                }
            }
        }

        if !deferred_flush.is_empty() {
            match notifier::notify_dnd_summary(app, deferred_flush.len()) {
                Ok(()) => {
                    if let Ok(conn) = state.db.lock() {
                        let flushed = storage::with_transaction(&conn, |tx| {
                            for (task_id, remind_at) in &deferred_flush {
                                storage::mark_reminder_fired(tx, task_id, *remind_at)?;
                            }
                            storage::clear_deferred_reminders(tx)
                        });
                        if flushed.is_ok() {
                            marked_now.extend(deferred_flush.iter().cloned());
                        }
                    }
                }
                Err(error) => {
                    logging::error("reminder", format!("免打扰汇总通知发送失败: {error}"));
                }
            }
        }
    }

    if !marked_now.is_empty() {
        if let Ok(conn) = state.db.lock() {
            for (task_id, remind_at) in &marked_now {
                let _ = storage::mark_reminder_fired(&conn, task_id, *remind_at);
            }
        }
    }

    if !plan.fire.is_empty()
        || !plan.defer.is_empty()
        || !plan.drop.is_empty()
        || !marked_now.is_empty()
    {
        logging::info(
            "reminder",
            format!(
                "tick: 工作区={} 任务={} fire={} defer={} drop={} 展示成功={} 耗时={}ms",
                workspace_total,
                reminders.len(),
                plan.fire.len(),
                plan.defer.len(),
                plan.drop.len(),
                marked_now.len(),
                storage::now_ms() - started,
            ),
        );
    }
}

fn parse_iso_ms(raw: &str) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(raw)
        .ok()
        .map(|value| value.timestamp_millis())
}
