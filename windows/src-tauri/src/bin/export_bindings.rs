//! 生成前端 IPC bindings。仅在需要刷新 `src/lib/ipc-bindings.generated.ts` 时运行：
//!
//! ```text
//! npm run gen:bindings
//! ```

#[cfg(feature = "desktop")]
fn main() {
    let path = match std::env::var_os("IPC_BINDINGS_OUT") {
        Some(custom) => std::path::PathBuf::from(custom),
        None => std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../src/lib/ipc-bindings.generated.ts"),
    };
    todo_windows_lib::specta_builder()
        .export(specta_typescript::Typescript::default(), &path)
        .expect("导出 IPC bindings 失败");
    println!("wrote {}", path.display());
}

#[cfg(not(feature = "desktop"))]
fn main() {
    eprintln!("export_bindings 需要 desktop feature");
}
