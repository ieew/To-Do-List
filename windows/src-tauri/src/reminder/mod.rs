//! 提醒子系统：本地调度逻辑 + 桌面计时循环 + 系统通知。

pub mod scheduler;

#[cfg(feature = "desktop")]
mod notifier;
#[cfg(feature = "desktop")]
mod runner;

#[cfg(feature = "desktop")]
pub use runner::{start, wake};
