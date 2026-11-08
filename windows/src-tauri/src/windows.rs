//! 多窗口生命周期：主窗口与便利贴是独立 WebviewWindow。
//! 前端只发命令；窗口的创建、计数、上下文与互斥由 Rust 侧管理。

use crate::{AppState, StickyContext};
use tauri::{AppHandle, Manager, State, WebviewUrl, WebviewWindowBuilder};

fn remove_context(app: &AppHandle, label: &str) {
    if let Some(state) = app.try_state::<AppState>() {
        if let Ok(mut contexts) = state.sticky_contexts.lock() {
            contexts.remove(label);
        }
    }
}

fn show_main(app: &AppHandle) {
    if let Some(main) = app.get_webview_window("main") {
        let _ = main.show();
        let _ = main.set_focus();
    }
}

fn close_window(app: &AppHandle, label: &str) {
    if let Some(window) = app.get_webview_window(label) {
        let _ = window.close();
    }
    remove_context(app, label);
}

#[tauri::command]
pub fn open_sticky_window(
    app: AppHandle,
    state: State<'_, AppState>,
    mode: String,
    task_id: Option<String>,
) -> Result<String, String> {
    let is_main = mode == "main";
    let label = if is_main {
        "sticky-main".to_string()
    } else {
        let existing = app
            .webview_windows()
            .keys()
            .filter(|label| label.starts_with("sticky-") && label.as_str() != "sticky-main")
            .count();
        if existing >= 5 {
            return Err("sticky_limit_reached: 同时最多 5 个浮动便利贴".to_string());
        }
        format!("sticky-{}", existing + 1)
    };

    if let Some(window) = app.get_webview_window(&label) {
        let _ = window.show();
        let _ = window.set_focus();
        return Ok(label);
    }

    let (width, height) = if is_main {
        (340.0, 440.0)
    } else {
        (304.0, 368.0)
    };

    {
        let mut contexts = state
            .sticky_contexts
            .lock()
            .map_err(|_| "便利贴上下文锁失败".to_string())?;
        contexts.insert(
            label.clone(),
            StickyContext {
                mode: mode.clone(),
                focus_task_id: task_id.clone(),
            },
        );
    }

    let mut builder = WebviewWindowBuilder::new(&app, &label, WebviewUrl::App("sticky.html".into()))
        .title("便利贴")
        .inner_size(width, height)
        .min_inner_size(280.0, 320.0)
        .decorations(false)
        .transparent(true)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(true);

    if let Ok(Some(monitor)) = app.primary_monitor() {
        let scale = monitor.scale_factor();
        let screen_w = monitor.size().width as f64 / scale;
        let screen_h = monitor.size().height as f64 / scale;
        let offset = if is_main {
            0.0
        } else {
            let existing = app
                .webview_windows()
                .keys()
                .filter(|label| label.starts_with("sticky-") && label.as_str() != "sticky-main")
                .count() as f64;
            existing * 24.0
        };
        let x = (screen_w - width - 24.0 - offset).max(0.0);
        let y = (screen_h - height - 48.0 - offset).max(0.0);
        builder = builder.position(x, y);
    }

    builder
        .build()
        .map_err(|error| format!("创建便利贴窗口失败: {error}"))?;

    if is_main {
        if let Some(main) = app.get_webview_window("main") {
            let _ = main.hide();
        }
    }

    Ok(label)
}

#[tauri::command]
pub fn close_sticky_window(app: AppHandle, label: String) -> Result<(), String> {
    close_window(&app, &label);
    if label == "sticky-main" {
        show_main(&app);
    }
    Ok(())
}

#[tauri::command]
pub fn show_main_window(app: AppHandle) -> Result<(), String> {
    close_window(&app, "sticky-main");
    show_main(&app);
    Ok(())
}

#[tauri::command]
pub fn get_sticky_context(
    state: State<'_, AppState>,
    label: String,
) -> Result<Option<StickyContext>, String> {
    let contexts = state
        .sticky_contexts
        .lock()
        .map_err(|_| "便利贴上下文锁失败".to_string())?;
    Ok(contexts.get(&label).cloned())
}

#[tauri::command]
pub fn list_sticky_windows(app: AppHandle) -> Vec<String> {
    let mut labels: Vec<String> = app
        .webview_windows()
        .keys()
        .filter(|label| label.starts_with("sticky-"))
        .cloned()
        .collect();
    labels.sort();
    labels
}

pub fn close_all_stickies(app: &AppHandle) {
    let labels: Vec<String> = app
        .webview_windows()
        .keys()
        .filter(|label| label.starts_with("sticky-"))
        .cloned()
        .collect();
    for label in labels {
        close_window(app, &label);
    }
}
