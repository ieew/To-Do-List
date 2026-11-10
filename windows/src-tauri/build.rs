//! Tauri 构建脚本。
//!
//! MinGW 的 `windres` 在中文路径下无法读取 `.ico` 文件，会导致 release 构建失败。
//! 这里把图标复制到系统临时目录（ASCII 路径），再通过 `TAURI_CONFIG` 覆盖
//! `bundle.icon`，让资源编译阶段始终使用 ASCII 路径。

use std::env;
use std::fs;
use std::path::Path;

const ICONS: &[&str] = &[
    "icons/32x32.png",
    "icons/128x128.png",
    "icons/128x128@2x.png",
    "icons/icon.icns",
    "icons/icon.ico",
];

fn main() {
    println!("cargo:rerun-if-changed=icons/icon.ico");
    if env::var_os("CARGO_FEATURE_DESKTOP").is_none() {
        // 纯逻辑测试（--no-default-features）不需要 Tauri 构建期资源。
        return;
    }
    if let Err(error) = prepare_ascii_icons() {
        println!("cargo:warning=无法准备 ASCII 图标路径: {error}");
    }
    tauri_build::build()
}

fn prepare_ascii_icons() -> Result<(), String> {
    let manifest_dir =
        env::var("CARGO_MANIFEST_DIR").map_err(|error| format!("读取项目目录失败: {error}"))?;
    let destination_dir = env::temp_dir().join("todo-build-icons");
    fs::create_dir_all(&destination_dir)
        .map_err(|error| format!("创建临时图标目录失败: {error}"))?;

    let mut ascii_icons: Vec<String> = Vec::new();
    for relative in ICONS {
        let source = Path::new(&manifest_dir).join(relative);
        if !source.is_file() {
            continue;
        }
        let file_name = Path::new(relative)
            .file_name()
            .ok_or_else(|| format!("图标文件名无效: {relative}"))?;
        let destination = destination_dir.join(file_name);
        fs::copy(&source, &destination).map_err(|error| {
            format!(
                "复制图标 {} 到 {} 失败: {error}",
                source.display(),
                destination.display()
            )
        })?;
        ascii_icons.push(destination.to_string_lossy().replace('\\', "/"));
    }

    if ascii_icons.is_empty() {
        return Ok(());
    }

    let mut config = env::var("TAURI_CONFIG")
        .ok()
        .and_then(|raw| serde_json::from_str::<serde_json::Value>(&raw).ok())
        .unwrap_or_else(|| serde_json::json!({}));
    if !config.is_object() {
        config = serde_json::json!({});
    }

    let root = config
        .as_object_mut()
        .ok_or_else(|| "TAURI_CONFIG 不是 JSON 对象".to_string())?;
    let bundle = root
        .entry("bundle".to_string())
        .or_insert_with(|| serde_json::json!({}));
    if !bundle.is_object() {
        *bundle = serde_json::json!({});
    }
    bundle
        .as_object_mut()
        .ok_or_else(|| "bundle 配置不是 JSON 对象".to_string())?
        .insert("icon".to_string(), serde_json::json!(ascii_icons));

    let serialized = serde_json::to_string(&config)
        .map_err(|error| format!("序列化 TAURI_CONFIG 失败: {error}"))?;
    env::set_var("TAURI_CONFIG", serialized);
    Ok(())
}
