//! 后台同步循环：登录后按 `settings.sync_interval` 轮询，本地写入或手动同步用 Notify 立即唤醒。

use super::{emit_state, engine, publish, SyncStatus};
use crate::storage;
use crate::AppState;
use std::time::Duration;
use tauri::{AppHandle, Manager};

pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        let mut backoff = 1u64;
        let mut first = true;
        let mut retry_after: Option<u64> = None;
        loop {
            let state = app.state::<AppState>();
            let signed_in = match state.sync.runtime.lock() {
                Ok(runtime) => runtime.signed_in,
                Err(_) => false,
            };
            if !signed_in {
                first = true;
                retry_after = None;
                state.sync.notify.notified().await;
                continue;
            }

            let interval_secs = state
                .db
                .lock()
                .map(|conn| storage::load_settings(&conn).sync_interval.max(1) as u64 * 60)
                .unwrap_or(300);

            if let Some(delay) = retry_after.take() {
                // 退避到期后立即重试，不再叠加一个轮询间隔；手动同步可提前唤醒。
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(delay)) => {}
                    _ = state.sync.notify.notified() => {}
                }
            } else if first {
                // 启动时先同步一次，不必等满一个轮询间隔。
                first = false;
            } else {
                tokio::select! {
                    _ = state.sync.notify.notified() => {}
                    _ = tokio::time::sleep(Duration::from_secs(interval_secs)) => {}
                }
            }

            match engine::run_once(&state).await {
                Ok(outcome) => {
                    backoff = 1;
                    publish(&app, &state, &outcome);
                }
                Err(error) => {
                    if let Ok(mut runtime) = state.sync.runtime.lock() {
                        runtime.last_error = Some(error.message.clone());
                        runtime.status = if error.code == "auth_required" {
                            SyncStatus::AuthRequired
                        } else if error.is_retryable() {
                            SyncStatus::Retrying
                        } else {
                            SyncStatus::Offline
                        };
                    }
                    emit_state(&app, &state);

                    if error.code == "auth_required" {
                        // 等用户重新登录，不做无意义的重试。
                        backoff = 1;
                        continue;
                    }
                    retry_after = Some(if error.is_retryable() { backoff } else { 30 });
                    backoff = (backoff * 2).min(60);
                }
            }
        }
    });
}
