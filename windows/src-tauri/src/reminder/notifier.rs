//! 系统通知：只负责把已决定的通知发出去，不参与调度与幂等。

use tauri::AppHandle;
use tauri_plugin_notification::NotificationExt;

pub fn notify_task(app: &AppHandle, title: &str, overdue: bool, catch_up: bool) {
    let heading = if catch_up { "补发提醒" } else { "任务提醒" };
    let body = if overdue {
        "任务已逾期，记得尽快处理"
    } else {
        "该处理这条任务了"
    };
    let _ = app
        .notification()
        .builder()
        .title(format!("{heading} · {title}"))
        .body(body)
        .show();
}

pub fn notify_dnd_summary(app: &AppHandle, count: usize) {
    let _ = app
        .notification()
        .builder()
        .title("免打扰期间有新的提醒")
        .body(format!("共有 {count} 条提醒等待处理"))
        .show();
}
