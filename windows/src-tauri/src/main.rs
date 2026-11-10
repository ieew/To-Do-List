#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(feature = "desktop")]
fn main() {
    todo_windows_lib::run()
}

/// 纯逻辑测试（`--no-default-features`）不构建桌面入口。
#[cfg(not(feature = "desktop"))]
fn main() {}
