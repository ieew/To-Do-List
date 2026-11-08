use serde::Serialize;

/// 桌面端壳层信息。当前 UI 原型使用内存投影，后续由 SQLite + reducer 替换。
#[derive(Serialize)]
struct AppInfo {
    name: &'static str,
    version: &'static str,
    architecture: &'static str,
    data_mode: &'static str,
}

#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        name: "待办清单",
        version: env!("CARGO_PKG_VERSION"),
        architecture: std::env::consts::ARCH,
        data_mode: "in-memory-prototype",
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![app_info])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
