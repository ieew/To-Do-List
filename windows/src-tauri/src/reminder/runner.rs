//! 桌面提醒循环：每 30 秒重算一次计划，睡眠/唤醒与改系统时间都由 tick 自然覆盖。

use super::scheduler::{self, DndWindow, ReminderTask};
use super::notifier;
use crate::storage;
use crate::AppState;
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
    let now = storage::now_ms();
    let offset = scheduler::local_offset_seconds();

    let (settings, workspace, locals) = {
        let Ok(conn) = state.db.lock() else {
            return;
        };
        (
            storage::load_settings(&conn),
            storage::active_workspace_id(&conn).unwrap_or_else(|| "ws-me".into()),
            storage::task_local_all(&conn).unwrap_or_default(),
        )
    };

    let Ok(projection) = crate::commands::build_projection_cached(state, &workspace) else {
        return;
    };
    let tasks = projection.tasks;

    let reminders: Vec<ReminderTask> = tasks
        .iter()
        .filter_map(|task| {
            let remind_at = task.remind_at.as_deref().and_then(parse_iso_ms)?;
            let local = locals.get(&task.id).copied().unwrap_or_default();
            Some(ReminderTask {
                task_id: task.id.clone(),
                title: task.title.clone(),
                status: task.status.clone(),
                remind_at,
                due_at: task.due_at.as_deref().and_then(parse_iso_ms),
                last_fired_at: local.last_fired_at,
                snooze_until: local.snooze_until,
            })
        })
        .collect();

    let dnd = DndWindow::from_settings(settings.dnd_enabled, &settings.dnd_start, &settings.dnd_end);
    let plan = scheduler::plan(
        &reminders,
        now,
        offset,
        settings.reminder_lead,
        dnd,
    );

    let mut to_notify = Vec::new();
    let mut flushed_count = 0usize;

    {
        let Ok(conn) = state.db.lock() else {
            return;
        };

        // 先落本地标记再发通知：通知失败最多少弹一次，不会重复轰炸。
        for item in &plan.drop {
            let _ = storage::mark_reminder_fired(&conn, &item.task_id, item.remind_at);
        }
        for item in &plan.fire {
            let _ = storage::mark_reminder_fired(&conn, &item.task_id, item.remind_at);
        }
        for item in &plan.defer {
            let _ = storage::add_deferred_reminder(&conn, &item.task_id, item.remind_at, now);
        }

        // 免打扰结束后合并补弹一次，避免逐条轰炸。
        if !dnd.contains(scheduler::local_minutes(now, offset)) {
            if let Ok(deferred) = storage::deferred_reminders(&conn) {
                if !deferred.is_empty() {
                    let cleared = storage::with_transaction(&conn, |tx| {
                        for (task_id, remind_at) in &deferred {
                            storage::mark_reminder_fired(tx, task_id, *remind_at)?;
                        }
                        storage::clear_deferred_reminders(tx)
                    });
                    if cleared.is_ok() {
                        flushed_count = deferred.len();
                    }
                }
            }
        }

        for item in plan.fire {
            let late = now > item.remind_at.saturating_add(LATE_THRESHOLD_MS);
            to_notify.push((item.title, item.overdue, late));
        }
    }

    if !settings.notifications {
        return;
    }
    for (title, overdue, late) in to_notify {
        notifier::notify_task(app, &title, overdue, late);
    }
    if flushed_count > 0 {
        notifier::notify_dnd_summary(app, flushed_count);
    }
}

fn parse_iso_ms(raw: &str) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(raw)
        .ok()
        .map(|value| value.timestamp_millis())
}
