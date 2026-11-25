#[cfg(feature = "desktop")]
mod ai;
#[cfg(feature = "desktop")]
mod commands;
pub mod core_domain;
pub mod dsh;
pub mod logging;
pub mod reminder;
pub mod storage;
pub mod sync;
#[cfg(feature = "desktop")]
mod windows;

use rusqlite::Connection;
use serde::Serialize;
use std::collections::HashMap;
use std::sync::atomic::AtomicBool;
use std::sync::Mutex;
#[cfg(feature = "desktop")]
use tauri::menu::{Menu, MenuItem};
#[cfg(feature = "desktop")]
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
#[cfg(feature = "desktop")]
use tauri::{Emitter, Manager};

/// 便利贴窗口的创建上下文。独立 Webview 通过 label 反查模式与聚焦任务。
#[derive(Clone, Debug, Serialize, specta::Type)]
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
    /// 提醒循环的即时唤醒信号（本地写入 / 设置变更 / 稍后提醒）。
    pub reminder_wake: tokio::sync::Notify,
    /// 按 workspace 缓存的增量投影状态；被拒 op 或清库时整体失效。
    pub projection_cache: Mutex<HashMap<String, core_domain::ProjectionCache>>,
    /// 托盘「退出」置位后，关闭窗口不再拦成隐藏。
    pub quitting: AtomicBool,
    /// dsh 推送/剪贴板通道的运行时状态与唤醒信号。
    pub dsh: dsh::DshState,
}

impl AppState {
    /// 被拒 op 或清库后整体失效缓存：这两类变化无法用「追加 op」表达。
    pub fn clear_projection_cache(&self) {
        if let Ok(mut cache) = self.projection_cache.lock() {
            cache.clear();
        }
    }
}

#[cfg(feature = "desktop")]
#[derive(Serialize, specta::Type)]
struct AppInfo {
    name: &'static str,
    version: &'static str,
    architecture: &'static str,
    data_mode: &'static str,
}

#[cfg(feature = "desktop")]
#[tauri::command]
#[specta::specta]
fn app_info() -> AppInfo {
    AppInfo {
        name: "待办清单",
        version: env!("CARGO_PKG_VERSION"),
        architecture: std::env::consts::ARCH,
        data_mode: "rust-sqlite-oplog",
    }
}

/// 命令注册与 TypeScript bindings 生成的唯一来源。
#[cfg(feature = "desktop")]
pub fn specta_builder() -> tauri_specta::Builder<tauri::Wry> {
    tauri_specta::Builder::<tauri::Wry>::new()
        // 本项目的时间戳/序号都是 epoch 毫秒或小整数，远小于 2^53，
        // 转成 TS number 不会丢精度。
        .dangerously_cast_bigints_to_number()
        .commands(tauri_specta::collect_commands![
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
        commands::undo_ops,
        commands::update_member_role,
        commands::remove_member,
        commands::invite_member,
        commands::save_settings,
        commands::snooze_reminder,
        commands::set_autostart,
        commands::dsh_status,
        commands::dsh_sync_now,
        commands::dsh_reconnect,
        commands::dsh_send_clipboard,
        commands::dsh_test_notification,
        commands::open_logs_folder,
        commands::auth_register,
        commands::auth_login,
        commands::auth_logout,
        commands::sync_now,
        commands::sync_preflight,
        commands::retry_rejected,
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
        ai::ai_plan,
        ai::ai_build_intent_prompt,
        ai::ai_parse_intent,
        ai::ai_apply_plan,
        windows::open_sticky_window,
        windows::open_quick_add_window,
        windows::close_sticky_window,
        windows::show_main_window,
        windows::hide_main_window,
        windows::get_sticky_context,
        windows::list_sticky_windows,
    ])
}

/// 托盘菜单与左键恢复：关闭到托盘后用户唯一的回归入口。
#[cfg(feature = "desktop")]
fn setup_tray(app: &tauri::AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
    let sticky = MenuItem::with_id(app, "sticky", "打开便利贴", true, None::<&str>)?;
    let quick_add = MenuItem::with_id(app, "quick-add", "添加任务", true, None::<&str>)?;
    let sync = MenuItem::with_id(app, "sync", "立即同步", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &sticky, &quick_add, &sync, &quit])?;

    let mut builder = TrayIconBuilder::with_id("main-tray")
        .tooltip("待办清单")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => {
                if let Some(main) = app.get_webview_window("main") {
                    let _ = main.show();
                    let _ = main.unminimize();
                    let _ = main.set_focus();
                }
            }
            "sticky" => {
                let _ = app.emit("tray-command", serde_json::json!({ "command": "sticky" }));
            }
            "quick-add" => {
                let _ = app.emit(
                    "tray-command",
                    serde_json::json!({ "command": "quick-add" }),
                );
            }
            "sync" => {
                if let Some(state) = app.try_state::<AppState>() {
                    state.sync.wake();
                }
            }
            "quit" => {
                if let Some(state) = app.try_state::<AppState>() {
                    state
                        .quitting
                        .store(true, std::sync::atomic::Ordering::SeqCst);
                }
                windows::close_all_stickies(app);
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                let app = tray.app_handle();
                if let Some(main) = app.get_webview_window("main") {
                    let _ = main.show();
                    let _ = main.unminimize();
                    let _ = main.set_focus();
                }
            }
        });

    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}

#[cfg(feature = "desktop")]
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let specta = specta_builder();
    tauri::Builder::default()
        // 单实例必须最先注册：第二次启动时聚焦已有主窗口，而不是再开一个进程。
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(main) = app.get_webview_window("main") {
                let _ = main.show();
                let _ = main.unminimize();
                let _ = main.set_focus();
            }
        }))
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            // 数据默认放在「软件同目录/data」；若该目录不可写（如安装在 Program Files），
            // 回退到系统应用数据目录，并把旧版本库搬迁过来。
            let legacy_dir = app.path().app_data_dir().ok();
            let data_dir = storage::resolve_data_dir(legacy_dir.as_deref());
            storage::migrate_legacy_database(&data_dir, legacy_dir.as_deref())?;
            println!("数据目录: {}", data_dir.display());
            logging::init(&data_dir);
            if let Err(error) = reminder::notifier::ensure_toast_aumid() {
                logging::warn("notifier", format!("注册 Toast AUMID 失败: {error}"));
            }
            let conn = storage::open(&data_dir.join(storage::DB_FILE))?;
            storage::seed_if_empty(&conn)?;
            let sync_state = sync::SyncState::new(&conn);

            // 主窗口在配置里是隐藏创建的，等前端首帧就绪后再显示，避免 WebView2
            // 初始化期间用户看到一个空白窗口。
            //
            // 注意：这里**不能**调用 `set_background_color(None)`。
            // tauri-runtime-wry 2.12 会把 `None` 映射成不透明白 (`255,255,255,255`)，
            // 于是透明窗口四周那圈留白会被涂成白色，表现为窗口边缘多出一圈"毛边"。
            // 透明由 tauri.conf.json 的 `transparent: true` 决定：创建时 wry 已把
            // WebView2 默认底色设为全透明，不需要也不应该再改。
            if app.get_webview_window("main").is_some() {
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
                dsh: dsh::DshState::new(),
                sync: sync_state,
                reminder_wake: tokio::sync::Notify::new(),
                projection_cache: Mutex::new(HashMap::new()),
                quitting: AtomicBool::new(false),
            });

            if let Err(error) = setup_tray(app.handle()) {
                eprintln!("创建托盘失败: {error}");
            }

            sync::worker::start(app.handle().clone());
            reminder::start(app.handle().clone());
            dsh::worker::start(app.handle().clone());

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
        .invoke_handler(specta.invoke_handler())
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let app = window.app_handle();
                let quitting = app
                    .try_state::<AppState>()
                    .map(|state| state.quitting.load(std::sync::atomic::Ordering::SeqCst))
                    .unwrap_or(false);

                if window.label() == "main" {
                    if quitting {
                        windows::close_all_stickies(app);
                        return;
                    }
                    let close_to_tray = app
                        .try_state::<AppState>()
                        .and_then(|state| {
                            state
                                .db
                                .lock()
                                .ok()
                                .map(|conn| storage::load_settings(&conn).close_to_tray)
                        })
                        .unwrap_or(false);
                    if close_to_tray {
                        // 关闭到托盘：保留进程与提醒循环，托盘左键可恢复。
                        api.prevent_close();
                        let _ = window.hide();
                        return;
                    }
                    windows::close_all_stickies(app);
                } else if window.label() == "sticky-main" && !quitting {
                    // 用户直接关闭主形态便利贴时，恢复到主窗口。
                    // 主窗口仍可见说明是退出流程，不重新显示。
                    if let Some(main) = app.get_webview_window("main") {
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
