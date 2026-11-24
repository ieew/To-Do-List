//! 极简文件日志：release 版没有控制台，排查提醒/推送问题只能靠落盘日志。
//!
//! - 位置：`<数据目录>/logs/todo-windows-YYYY-MM-DD.log`，按本地日期滚动。
//! - 禁止记录剪贴板原文等敏感内容；只允许记录长度、指纹前缀、消息 id。
//! - 初始化失败不阻塞启动，退回 stderr。

use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use chrono::Local;

struct Logger {
    dir: PathBuf,
    day: String,
    file: File,
}

static LOGGER: OnceLock<Mutex<Logger>> = OnceLock::new();

fn open_file(dir: &Path, day: &str) -> std::io::Result<File> {
    fs::create_dir_all(dir)?;
    OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join(format!("todo-windows-{day}.log")))
}

/// 初始化日志目录。重复调用是幂等的。
pub fn init(data_dir: &Path) {
    let dir = data_dir.join("logs");
    let day = Local::now().format("%Y-%m-%d").to_string();
    let Ok(file) = open_file(&dir, &day) else {
        return;
    };
    let logger = Logger { dir, day, file };
    let _ = LOGGER.set(Mutex::new(logger));
    info("logging", "日志已初始化");
}

fn write_line(level: &str, target: &str, message: &str) {
    let now = Local::now();
    let day = now.format("%Y-%m-%d").to_string();
    let line = format!(
        "[{}] [{level}] [{target}] {message}\n",
        now.format("%Y-%m-%d %H:%M:%S%.3f")
    );

    #[cfg(debug_assertions)]
    eprint!("{line}");

    let Some(lock) = LOGGER.get() else {
        return;
    };
    let Ok(mut logger) = lock.lock() else {
        return;
    };
    // 跨天时换文件：简单按日期重开，不做删除/归档。
    if logger.day != day {
        if let Ok(file) = open_file(&logger.dir, &day) {
            logger.file = file;
            logger.day = day;
        }
    }
    let _ = logger.file.write_all(line.as_bytes());
    let _ = logger.file.flush();
}

pub fn info(target: &str, message: impl AsRef<str>) {
    write_line("INFO", target, message.as_ref());
}

pub fn warn(target: &str, message: impl AsRef<str>) {
    write_line("WARN", target, message.as_ref());
}

pub fn error(target: &str, message: impl AsRef<str>) {
    write_line("ERROR", target, message.as_ref());
}

/// 日志目录（设置页「打开日志目录」使用）。
pub fn log_dir(data_dir: &Path) -> PathBuf {
    data_dir.join("logs")
}
