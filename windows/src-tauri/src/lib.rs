#[cfg(feature = "desktop")]
mod ai;
#[cfg(feature = "desktop")]
mod commands;
pub mod core_domain;
pub mod storage;
pub mod sync;
#[cfg(feature = "desktop")]
mod windows;

use rusqlite::Connection;
use serde::Serialize;
use std::collections::HashMap;
use std::sync::Mutex;
#[cfg(feature = "desktop")]
use tauri::window::Color;
#[cfg(feature = "desktop")]
use tauri::Manager;

/// 便利贴窗口的创建上下文。独立 Webview 通过 label 反查模式与聚焦任务。
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StickyContext {
    pub mode: String,
    pub focus_task_id: Option<String>,
}

pub struct AppState {
    pub db: Mutex<Connection>,
    pub sticky_contexts: Mutex<HashMap<String, StickyContext>>,
    /// 数据目录，用于登录前备份本地库。
    pub data_dir: std::path::PathBuf,
    pub sync: sync::SyncState,
}

#[cfg(feature = "desktop")]
#[derive(Serialize)]
struct AppInfo {
    name: &'static str,
    version: &'static str,
    architecture: &'static str,
    data_mode: &'static str,
}

#[cfg(feature = "desktop")]
#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        name: "待办清单",
        version: env!("CARGO_PKG_VERSION"),
        architecture: std::env::consts::ARCH,
        data_mode: "rust-sqlite-oplog",
    }
}

#[cfg(feature = "desktop")]
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            // 数据默认放在「软件同目录/data」；若该目录不可写（如安装在 Program Files），
            // 回退到系统应用数据目录，并把旧版本库搬迁过来。
            let legacy_dir = app.path().app_data_dir().ok();
            let data_dir = storage::resolve_data_dir(legacy_dir.as_deref());
            storage::migrate_legacy_database(&data_dir, legacy_dir.as_deref())?;
            println!("数据目录: {}", data_dir.display());
            let conn = storage::open(&data_dir.join(storage::DB_FILE))?;
            storage::seed_if_empty(&conn)?;
            let sync_state = sync::SyncState::new(&conn);

            // 主窗口在配置里是隐藏创建的，等前端首帧就绪后再显示，避免 WebView2
            // 初始化期间用户看到一个空白窗口。这里先把底色同步成当前主题，
            // 保证「显示窗口 → 首帧绘制」之间不会闪白或闪错颜色。
            if let Some(main) = app.get_webview_window("main") {
                let theme = storage::load_settings(&conn).theme;
                let background = if theme == "dark" {
                    Color(15, 23, 42, 255) // #0f172a
                } else {
                    Color(248, 250, 252, 255) // #f8fafc
                };
                let _ = main.set_background_color(Some(background));

                // 兜底：前端脚本若加载失败，主窗口会一直隐藏。3 秒后强制显示，
                // 至少让用户看到 HTML 里的启动占位，而不是「双击没反应」。
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    std::thread::sleep(std::time::Duration::from_millis(3000));
                    if let Some(window) = handle.get_webview_window("main") {
                        if matches!(window.is_visible(), Ok(false)) {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                });
            }

            app.manage(AppState {
                db: Mutex::new(conn),
                sticky_contexts: Mutex::new(HashMap::new()),
                data_dir: data_dir.clone(),
                sync: sync_state,
            });

            sync::worker::start(app.handle().clone());

            // 仅调试构建：设置 TODO_VERIFY_WINDOWS=1 时自动创建便利贴窗口，
            // 把实际 WebviewWindow label 写入临时文件后退出，用于验证双窗口。
            #[cfg(debug_assertions)]
            if std::env::var("TODO_VERIFY_WINDOWS").is_ok() {
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    std::thread::sleep(std::time::Duration::from_millis(2500));
                    let state = handle.state::<AppState>();
                    let _ = tauri::async_runtime::block_on(windows::open_sticky_window(
                        handle.clone(),
                        state,
                        "main".to_string(),
                        None,
                    ));
                    std::thread::sleep(std::time::Duration::from_millis(3000));
                    let mut labels: Vec<String> = handle.webview_windows().keys().cloned().collect();
                    labels.sort();

                    let main_dom = handle
                        .get_webview_window("main")
                        .and_then(|window| {
                            let _ = window.eval(
                                "location.hash = 'MAIN_OK:' + document.querySelectorAll('.task-row').length",
                            );
                            std::thread::sleep(std::time::Duration::from_millis(400));
                            window.url().ok().map(|url| url.to_string())
                        })
                        .unwrap_or_else(|| "MAIN_MISSING".to_string());

                    let sticky_dom = handle
                        .get_webview_window("sticky-main")
                        .and_then(|window| {
                            let _ = window.eval(
                                "location.hash = document.querySelector('.sticky-loading.error') ? 'STICKY_ERROR' : (document.querySelector('.sticky-note') ? 'STICKY_OK:' + document.querySelectorAll('.sticky-task').length : 'STICKY_LOADING')",
                            );
                            std::thread::sleep(std::time::Duration::from_millis(600));
                            window.url().ok().map(|url| url.to_string())
                        })
                        .unwrap_or_else(|| "STICKY_MISSING".to_string());

                    let report = format!(
                        "windows={}\n{}\nmainDom={}\nstickyDom={}\n",
                        labels.len(),
                        labels.join("\n"),
                        main_dom,
                        sticky_dom
                    );
                    let _ = std::fs::write(
                        std::env::temp_dir().join("todo-verify-windows.txt"),
                        report,
                    );
                    handle.exit(0);
                });
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            app_info,
            commands::bootstrap,
            commands::set_active_workspace,
            commands::create_workspace,
            commands::rename_workspace,
            commands::delete_workspace,
            commands::create_task,
            commands::update_task_field,
            commands::delete_task,
            commands::reorder_task,
            commands::add_subtask,
            commands::toggle_subtask,
            commands::remove_subtask,
            commands::get_history,
            commands::undo_op,
            commands::update_member_role,
            commands::remove_member,
            commands::invite_member,
            commands::save_settings,
            commands::auth_register,
            commands::auth_login,
            commands::auth_logout,
            commands::sync_now,
            commands::sync_preflight,
            commands::create_list,
            commands::update_list_field,
            commands::delete_list,
            commands::create_tag,
            commands::update_tag_field,
            commands::delete_tag,
            commands::ai_key_status,
            commands::set_ai_key,
            commands::clear_ai_key,
            ai::ai_build_prompt,
            ai::ai_generate_tasks,
            ai::ai_parse_plan,
            windows::open_sticky_window,
            windows::close_sticky_window,
            windows::show_main_window,
            windows::hide_main_window,
            windows::get_sticky_context,
            windows::list_sticky_windows,
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                if window.label() == "main" {
                    windows::close_all_stickies(window.app_handle());
                } else if window.label() == "sticky-main" {
                    // 用户直接关闭主形态便利贴时，恢复到主窗口。
                    // 主窗口仍可见说明是退出流程，不重新显示。
                    if let Some(main) = window.app_handle().get_webview_window("main") {
                        let visible = main.is_visible().unwrap_or(false);
                        if !visible {
                            let _ = main.show();
                            let _ = main.set_focus();
                        }
                    }
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
