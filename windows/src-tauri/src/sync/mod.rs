//! 客户端同步层：会话、wire 映射、HTTP 客户端、同步引擎与后台循环。

pub mod client;
pub mod engine;
pub mod session;
pub mod wire;
#[cfg(feature = "desktop")]
pub mod worker;

use crate::storage;
#[cfg(feature = "desktop")]
use crate::AppState;
use rusqlite::Connection;
use serde::Serialize;
use std::sync::Mutex;
#[cfg(feature = "desktop")]
use tauri::{AppHandle, Emitter};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SyncStatus {
    SignedOut,
    Syncing,
    Synced,
    Offline,
    Retrying,
    AuthRequired,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncStateDto {
    pub status: SyncStatus,
    pub account: Option<session::AccountRecord>,
    pub pending_count: i64,
    pub rejected_count: i64,
    pub last_synced_at: Option<i64>,
    pub last_error: Option<String>,
    pub cursor: i64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncNoticeDto {
    /// `clock_skew` / `permission` / `auth` / `error`
    pub kind: String,
    pub code: String,
    pub message: String,
    pub retryable: bool,
    pub at: i64,
}

pub struct Runtime {
    pub signed_in: bool,
    pub status: SyncStatus,
    pub last_synced_at: Option<i64>,
    pub last_error: Option<String>,
    pub notice: Option<SyncNoticeDto>,
    pub last_notice_key: Option<String>,
}

impl Runtime {
    fn new(conn: &Connection) -> Self {
        match session::load_session(conn) {
            Ok(Some(_)) => Self {
                signed_in: true,
                status: SyncStatus::Offline,
                last_synced_at: None,
                last_error: None,
                notice: None,
                last_notice_key: None,
            },
            Ok(None) => Self {
                signed_in: false,
                status: SyncStatus::SignedOut,
                last_synced_at: None,
                last_error: None,
                notice: None,
                last_notice_key: None,
            },
            Err(error) => Self {
                signed_in: false,
                status: SyncStatus::SignedOut,
                last_synced_at: None,
                last_error: Some(error),
                notice: None,
                last_notice_key: None,
            },
        }
    }
}

pub struct SyncState {
    pub runtime: Mutex<Runtime>,
    pub notify: tokio::sync::Notify,
    pub round: tokio::sync::Mutex<()>,
}

impl SyncState {
    pub fn new(conn: &Connection) -> Self {
        Self {
            runtime: Mutex::new(Runtime::new(conn)),
            notify: tokio::sync::Notify::new(),
            round: tokio::sync::Mutex::new(()),
        }
    }

    pub fn wake(&self) {
        self.notify.notify_one();
    }

    pub fn set_signed_in(&self, signed_in: bool) {
        if let Ok(mut runtime) = self.runtime.lock() {
            runtime.signed_in = signed_in;
            runtime.status = if signed_in {
                SyncStatus::Offline
            } else {
                SyncStatus::SignedOut
            };
            if !signed_in {
                runtime.last_error = None;
                runtime.notice = None;
            }
        }
        if signed_in {
            self.wake();
        }
    }
}

pub fn snapshot(conn: &Connection, state: &SyncState) -> SyncStateDto {
    let runtime = state.runtime.lock().expect("同步状态锁失败");
    SyncStateDto {
        status: runtime.status,
        account: if runtime.signed_in {
            session::account(conn)
        } else {
            None
        },
        pending_count: storage::pending_count(conn).unwrap_or(0),
        rejected_count: storage::rejected_count(conn).unwrap_or(0),
        last_synced_at: runtime.last_synced_at,
        last_error: runtime.last_error.clone(),
        cursor: storage::cursor(conn),
    }
}

#[cfg(feature = "desktop")]
pub fn snapshot_for(state: &AppState) -> SyncStateDto {
    match state.db.lock() {
        Ok(conn) => snapshot(&conn, &state.sync),
        Err(_) => SyncStateDto {
            status: SyncStatus::Offline,
            account: None,
            pending_count: 0,
            rejected_count: 0,
            last_synced_at: None,
            last_error: Some("数据库连接锁失败".into()),
            cursor: 0,
        },
    }
}

/// 把本轮同步结果广播给所有窗口：投影、同步状态、一次性通知。
#[cfg(feature = "desktop")]
pub fn publish(app: &AppHandle, state: &AppState, outcome: &engine::RoundOutcome) {
    if outcome.changed {
        let _ = app.emit(
            "projection-updated",
            serde_json::json!({ "workspaceId": null }),
        );
    }
    let dto = snapshot_for(state);
    let _ = app.emit("sync-status-changed", &dto);

    for notice in &outcome.notices {
        let key = format!("{}:{}", notice.kind, notice.code);
        let fresh = match state.sync.runtime.lock() {
            Ok(mut runtime) => {
                if runtime.last_notice_key.as_deref() == Some(key.as_str()) {
                    false
                } else {
                    runtime.last_notice_key = Some(key);
                    runtime.notice = Some(notice.clone());
                    true
                }
            }
            Err(_) => false,
        };
        if fresh {
            let _ = app.emit("sync-notice", notice);
        }
    }
}

#[cfg(feature = "desktop")]
pub fn emit_state(app: &AppHandle, state: &AppState) {
    let _ = app.emit("sync-status-changed", snapshot_for(state));
}
