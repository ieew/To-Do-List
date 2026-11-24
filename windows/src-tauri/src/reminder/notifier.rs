//! 系统通知：只负责把已决定的通知发出去，不参与调度与幂等。
//!
//! Windows 上直接调用 WinRT Toast：
//! - `tauri-plugin-notification` 在未打包运行时会设置 `app_id=com.todo.windows`，
//!   但本项目用 `tauri build --no-bundle`，从未注册过 AUMID，Toast 会被系统静默丢弃；
//!   插件还把 `show()` 放在后台线程里 `let _ =`，错误完全看不到。
//! - 这里显式注册 AUMID（HKCU），并在自定义 AUMID 失败时回落 PowerShell 的 AUMID，
//!   保证「至少能弹出来」；同时把结果返回给调用方，便于日志与重试。

use tauri::AppHandle;

#[cfg(windows)]
const AUMID: &str = "com.todo.windows";

/// 未打包应用弹 Toast 的前提：HKCU 下登记 AppUserModelId。
/// 失败不阻塞启动，只记录；`show` 会回落 PowerShell AUMID。
#[cfg(windows)]
pub fn ensure_toast_aumid() -> Result<(), String> {
    use winreg::enums::HKEY_CURRENT_USER;

    let exe = std::env::current_exe().map_err(|error| format!("获取 exe 路径失败: {error}"))?;
    let hkcu = winreg::RegKey::predef(HKEY_CURRENT_USER);
    let (key, _) = hkcu
        .create_subkey(r"Software\Classes\AppUserModelId\com.todo.windows")
        .map_err(|error| format!("创建 AUMID 注册表项失败: {error}"))?;
    key.set_value("DisplayName", &"待办清单")
        .map_err(|error| format!("写入 AUMID DisplayName 失败: {error}"))?;
    key.set_value("IconUri", &exe.to_string_lossy().to_string())
        .map_err(|error| format!("写入 AUMID IconUri 失败: {error}"))?;
    Ok(())
}

#[cfg(not(windows))]
pub fn ensure_toast_aumid() -> Result<(), String> {
    Ok(())
}

/// 本地任务提醒。
pub fn notify_task(
    app: &AppHandle,
    task_id: &str,
    workspace_id: Option<&str>,
    title: &str,
    overdue: bool,
    catch_up: bool,
) -> Result<(), String> {
    let heading = if catch_up { "补发提醒" } else { "任务提醒" };
    let body = if overdue {
        "任务已逾期，记得尽快处理"
    } else {
        "该处理这条任务了"
    };
    show(
        app,
        &format!("{heading} · {title}"),
        body,
        false,
        catch_up,
        Some(task_id),
        workspace_id,
    )
}

/// 免打扰结束后合并补弹。
pub fn notify_dnd_summary(app: &AppHandle, count: usize) -> Result<(), String> {
    show(
        app,
        "免打扰期间有新的提醒",
        &format!("共有 {count} 条提醒等待处理"),
        false,
        false,
        None,
        None,
    )
}

/// 服务端推送（`kind=notify`）。等级只影响声音/时长，不影响是否送达。
pub fn notify_remote(
    app: &AppHandle,
    task_id: Option<&str>,
    workspace_id: Option<&str>,
    title: &str,
    body: &str,
    level: u8,
) -> Result<(), String> {
    let silent = level <= 2;
    let long = level >= 6;
    show(app, title, body, silent, long, task_id, workspace_id)
}

#[cfg(windows)]
fn show(
    app: &AppHandle,
    title: &str,
    body: &str,
    silent: bool,
    long: bool,
    task_id: Option<&str>,
    workspace_id: Option<&str>,
) -> Result<(), String> {
    match show_windows(AUMID, app, title, body, silent, long, task_id, workspace_id) {
        Ok(()) => Ok(()),
        Err(error) => {
            crate::logging::warn(
                "notifier",
                format!("自定义 AUMID 发送失败（{error}），回落 PowerShell AUMID"),
            );
            show_windows(
                tauri_winrt_notification::Toast::POWERSHELL_APP_ID,
                app,
                title,
                body,
                silent,
                long,
                task_id,
                workspace_id,
            )
        }
    }
}

#[cfg(windows)]
fn show_windows(
    aumid: &str,
    app: &AppHandle,
    title: &str,
    body: &str,
    silent: bool,
    long: bool,
    task_id: Option<&str>,
    workspace_id: Option<&str>,
) -> Result<(), String> {
    use tauri::Emitter;
    use tauri_winrt_notification::{Duration, Sound, Toast};

    let mut toast = Toast::new(aumid).title(title).text1(body).duration(if long {
        Duration::Long
    } else {
        Duration::Short
    });
    toast = toast.sound(if silent { None } else { Some(Sound::Default) });

    if task_id.is_some() || workspace_id.is_some() {
        let handle = app.clone();
        let task = task_id.map(str::to_string);
        let workspace = workspace_id.map(str::to_string);
        toast = toast.on_activated(move |_| {
            // 点击通知：把主窗口拉回来，并让前端定位到任务。
            crate::windows::focus_main(&handle);
            let _ = handle.emit(
                "open-task",
                serde_json::json!({ "taskId": task, "workspaceId": workspace }),
            );
            Ok(())
        });
    }

    toast.show().map_err(|error| error.to_string())
}

/// 非 Windows 平台保留插件实现（本项目只发布 Windows，保留仅为可编译）。
#[cfg(not(windows))]
fn show(
    app: &AppHandle,
    title: &str,
    body: &str,
    _silent: bool,
    _long: bool,
    _task_id: Option<&str>,
    _workspace_id: Option<&str>,
) -> Result<(), String> {
    use tauri_plugin_notification::NotificationExt;
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|error| error.to_string())
}
