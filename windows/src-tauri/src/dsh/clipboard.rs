//! 系统剪贴板读写。读写都走已注册的 clipboard-manager 插件（arboard）。

use tauri::AppHandle;
use tauri_plugin_clipboard_manager::ClipboardExt;

pub fn read_text(app: &AppHandle) -> Option<String> {
    match app.clipboard().read_text() {
        Ok(text) if !text.is_empty() => Some(text),
        _ => None,
    }
}

pub fn write_text(app: &AppHandle, text: &str) -> Result<(), String> {
    app.clipboard()
        .write_text(text)
        .map_err(|error| format!("写入系统剪贴板失败: {error}"))
}
