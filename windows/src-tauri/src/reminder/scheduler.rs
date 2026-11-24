//! 提醒调度的纯逻辑：不依赖 Tauri、SQLite 与物理时钟。
//!
//! 调用方传入 `now_ms` 与本地时区偏移，保证同一组输入得到同一组输出，
//! 便于用 `cargo test --lib --no-default-features` 直接覆盖边界。

/// 错过提醒的补发窗口：超过该窗口只标记为已触发，不再弹通知。
pub const CATCH_UP_WINDOW_MS: i64 = 60 * 60 * 1000;

/// 参与调度的任务快照。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReminderTask {
    pub task_id: String,
    pub title: String,
    pub status: String,
    pub remind_at: i64,
    pub due_at: Option<i64>,
    /// 已触发过的 `remind_at` 值（毫秒），用于幂等。
    pub last_fired_at: Option<i64>,
    /// 稍后提醒的目标时刻（毫秒）。
    pub snooze_until: Option<i64>,
}

/// 免打扰窗口（本地分钟数，0–1439）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DndWindow {
    pub enabled: bool,
    pub start_minutes: u32,
    pub end_minutes: u32,
}

impl DndWindow {
    /// 解析失败或起止相同视为未开启，避免把用户锁死在「全天免打扰」。
    pub fn from_settings(enabled: bool, start: &str, end: &str) -> Self {
        let Some(start_minutes) = parse_hhmm(start) else {
            return Self {
                enabled: false,
                start_minutes: 0,
                end_minutes: 0,
            };
        };
        let Some(end_minutes) = parse_hhmm(end) else {
            return Self {
                enabled: false,
                start_minutes: 0,
                end_minutes: 0,
            };
        };
        Self {
            enabled: enabled && start_minutes != end_minutes,
            start_minutes,
            end_minutes,
        }
    }

    /// `minutes` 为本地时间当天的分钟数。
    pub fn contains(&self, minutes: u32) -> bool {
        if !self.enabled {
            return false;
        }
        if self.start_minutes < self.end_minutes {
            minutes >= self.start_minutes && minutes < self.end_minutes
        } else {
            // 跨天窗口，例如 22:00–08:00。
            minutes >= self.start_minutes || minutes < self.end_minutes
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FiredReminder {
    pub task_id: String,
    pub title: String,
    pub remind_at: i64,
    pub overdue: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeferredReminder {
    pub task_id: String,
    pub remind_at: i64,
}

/// 一轮调度的结果。`drop` 里的项只更新本地标记，不弹通知。
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ReminderPlan {
    pub fire: Vec<FiredReminder>,
    pub defer: Vec<DeferredReminder>,
    pub drop: Vec<DeferredReminder>,
    /// 下一条待触发时刻，便于调用方计算睡眠间隔。
    pub next_at: Option<i64>,
}

/// 解析 `HH:mm` 为当天分钟数；越界或格式非法返回 `None`。
pub fn parse_hhmm(raw: &str) -> Option<u32> {
    let (hour, minute) = raw.trim().split_once(':')?;
    let hour: u32 = hour.trim().parse().ok()?;
    let minute: u32 = minute.trim().parse().ok()?;
    if hour > 23 || minute > 59 {
        return None;
    }
    Some(hour * 60 + minute)
}

/// 把 UTC 毫秒换算成本地当天分钟数。`offset_seconds` 为本地时区相对 UTC 的偏移。
pub fn local_minutes(now_ms: i64, offset_seconds: i32) -> u32 {
    let local_ms = now_ms + i64::from(offset_seconds) * 1000;
    let day_ms = local_ms.rem_euclid(86_400_000);
    (day_ms / 60_000) as u32
}

/// 计算生效触发时刻：提前量之前的时刻，与稍后提醒取较晚者。
pub fn effective_fire_at(remind_at: i64, lead_minutes: i64, snooze_until: Option<i64>) -> i64 {
    let lead = lead_minutes.max(0) * 60_000;
    let base = remind_at.saturating_sub(lead);
    match snooze_until {
        Some(until) => base.max(until),
        None => base,
    }
}

/// 生成一轮提醒计划。`now_ms` 为 UTC 毫秒，`offset_seconds` 为本地时区偏移。
pub fn plan(
    tasks: &[ReminderTask],
    now_ms: i64,
    offset_seconds: i32,
    lead_minutes: i64,
    dnd: DndWindow,
) -> ReminderPlan {
    let mut result = ReminderPlan::default();
    let current_minutes = local_minutes(now_ms, offset_seconds);

    for task in tasks {
        if task.status == "done" {
            continue;
        }
        // 同一条 remind_at 只触发一次；改提前量不会导致重复提醒。
        // 注意用「相等」而不是「大于等于」：远端把 remind_at 改早时，
        // 旧的 last_fired_at 会大于新值，用 >= 会让这条提醒永久失效。
        if task.last_fired_at == Some(task.remind_at) {
            continue;
        }

        let fire_at = effective_fire_at(task.remind_at, lead_minutes, task.snooze_until);
        if now_ms < fire_at {
            result.next_at = Some(match result.next_at {
                Some(current) => current.min(fire_at),
                None => fire_at,
            });
            continue;
        }

        let overdue = task.due_at.is_some_and(|due| due < now_ms);
        if now_ms > task.remind_at.saturating_add(CATCH_UP_WINDOW_MS) {
            result.drop.push(DeferredReminder {
                task_id: task.task_id.clone(),
                remind_at: task.remind_at,
            });
            continue;
        }

        if dnd.contains(current_minutes) {
            result.defer.push(DeferredReminder {
                task_id: task.task_id.clone(),
                remind_at: task.remind_at,
            });
            continue;
        }

        result.fire.push(FiredReminder {
            task_id: task.task_id.clone(),
            title: task.title.clone(),
            remind_at: task.remind_at,
            overdue,
        });
    }

    result
        .fire
        .sort_by(|a, b| a.remind_at.cmp(&b.remind_at).then_with(|| a.task_id.cmp(&b.task_id)));
    result
        .defer
        .sort_by(|a, b| a.remind_at.cmp(&b.remind_at).then_with(|| a.task_id.cmp(&b.task_id)));
    result
        .drop
        .sort_by(|a, b| a.remind_at.cmp(&b.remind_at).then_with(|| a.task_id.cmp(&b.task_id)));
    result
}

/// 本地时区偏移（秒）。运行时的唯一时钟观察点，测试统一传 0。
pub fn local_offset_seconds() -> i32 {
    use chrono::{Local, Offset};
    Local::now().offset().fix().local_minus_utc()
}

#[cfg(test)]
mod tests {
    use super::*;

    const MINUTE: i64 = 60_000;

    fn task(remind_at: i64) -> ReminderTask {
        ReminderTask {
            task_id: "task-1".into(),
            title: "写周报".into(),
            status: "todo".into(),
            remind_at,
            due_at: None,
            last_fired_at: None,
            snooze_until: None,
        }
    }

    fn no_dnd() -> DndWindow {
        DndWindow {
            enabled: false,
            start_minutes: 0,
            end_minutes: 0,
        }
    }

    #[test]
    fn parses_hhmm_and_rejects_invalid() {
        assert_eq!(parse_hhmm("22:00"), Some(22 * 60));
        assert_eq!(parse_hhmm("08:30"), Some(8 * 60 + 30));
        assert_eq!(parse_hhmm("24:00"), None);
        assert_eq!(parse_hhmm("7:5"), Some(7 * 60 + 5));
        assert_eq!(parse_hhmm("abc"), None);
    }

    #[test]
    fn dnd_supports_cross_midnight() {
        let window = DndWindow::from_settings(true, "22:00", "08:00");
        assert!(window.contains(22 * 60));
        assert!(window.contains(23 * 60 + 59));
        assert!(window.contains(0));
        assert!(window.contains(7 * 60 + 59));
        assert!(!window.contains(8 * 60));
        assert!(!window.contains(21 * 60 + 59));
    }

    #[test]
    fn dnd_disabled_when_range_is_empty_or_invalid() {
        assert!(!DndWindow::from_settings(true, "08:00", "08:00").enabled);
        assert!(!DndWindow::from_settings(true, "", "08:00").enabled);
        assert!(!DndWindow::from_settings(false, "22:00", "08:00").enabled);
    }

    #[test]
    fn lead_time_brings_fire_forward() {
        let now = 1_000 * MINUTE;
        let remind_at = now + 5 * MINUTE;
        let result = plan(&[task(remind_at)], now, 0, 10, no_dnd());
        assert_eq!(result.fire.len(), 1);
        assert!(!result.fire[0].overdue);
    }

    #[test]
    fn waits_when_not_due_and_reports_next_at() {
        let now = 1_000 * MINUTE;
        let remind_at = now + 30 * MINUTE;
        let result = plan(&[task(remind_at)], now, 0, 10, no_dnd());
        assert!(result.fire.is_empty());
        assert_eq!(result.next_at, Some(remind_at - 10 * MINUTE));
    }

    #[test]
    fn same_remind_at_only_fires_once() {
        let now = 1_000 * MINUTE;
        let mut item = task(now - MINUTE);
        item.last_fired_at = Some(item.remind_at);
        let result = plan(&[item], now, 0, 10, no_dnd());
        assert!(result.fire.is_empty());
        assert!(result.defer.is_empty());
        assert!(result.drop.is_empty());
    }

    #[test]
    fn moving_remind_at_earlier_is_not_suppressed_by_stale_marker() {
        // 旧标记来自更晚的 remind_at：改早之后必须重新提醒。
        let now = 1_000 * MINUTE;
        let mut item = task(now - MINUTE);
        item.last_fired_at = Some(now + 60 * MINUTE);
        let result = plan(&[item], now, 0, 0, no_dnd());
        assert_eq!(result.fire.len(), 1);
    }

    #[test]
    fn done_tasks_are_skipped() {
        let now = 1_000 * MINUTE;
        let mut item = task(now - MINUTE);
        item.status = "done".into();
        assert!(plan(&[item], now, 0, 10, no_dnd()).fire.is_empty());
    }

    #[test]
    fn snooze_delays_the_same_reminder() {
        let now = 1_000 * MINUTE;
        let mut item = task(now - MINUTE);
        item.snooze_until = Some(now + 10 * MINUTE);
        let result = plan(&[item], now, 0, 10, no_dnd());
        assert!(result.fire.is_empty());
        assert_eq!(result.next_at, Some(now + 10 * MINUTE));
    }

    #[test]
    fn dnd_defers_instead_of_dropping() {
        let now = 1_000 * MINUTE;
        let window = DndWindow {
            enabled: true,
            start_minutes: 0,
            end_minutes: 23 * 60 + 59,
        };
        let result = plan(&[task(now - MINUTE)], now, 0, 0, window);
        assert!(result.fire.is_empty());
        assert_eq!(result.defer.len(), 1);
    }

    #[test]
    fn stale_reminders_are_dropped_after_catch_up_window() {
        let now = 10_000 * MINUTE;
        let remind_at = now - CATCH_UP_WINDOW_MS - MINUTE;
        let result = plan(&[task(remind_at)], now, 0, 0, no_dnd());
        assert!(result.fire.is_empty());
        assert_eq!(result.drop.len(), 1);
    }

    #[test]
    fn overdue_flag_uses_due_at() {
        let now = 1_000 * MINUTE;
        let mut item = task(now - MINUTE);
        item.due_at = Some(now - 30 * MINUTE);
        let result = plan(&[item], now, 0, 0, no_dnd());
        assert!(result.fire[0].overdue);
    }

    #[test]
    fn plans_are_sorted_and_deterministic() {
        let now = 1_000 * MINUTE;
        let mut first = task(now - 2 * MINUTE);
        first.task_id = "b".into();
        let mut second = task(now - 3 * MINUTE);
        second.task_id = "a".into();
        let result = plan(&[first, second], now, 0, 0, no_dnd());
        assert_eq!(result.fire[0].task_id, "a");
        assert_eq!(result.fire[1].task_id, "b");
    }
}
