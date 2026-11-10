//! 同步引擎：先 push 再 pull，逐 op 幂等、整批权限拒绝、指数退避由外层 worker 负责。

use super::client::{Api, ApiError};
use super::session::{self, Session, Tokens};
use super::wire::WireError;
use super::{SyncNoticeDto, SyncStatus};
use crate::core_domain::Op;
use crate::storage::{self, PulledOp};
use crate::AppState;

const PUSH_BATCH: usize = 100;
const PULL_LIMIT: usize = 200;
const PULL_MAX_PAGES: usize = 20;
/// access token 剩余不足该值时提前刷新。
const REFRESH_AHEAD_MS: i64 = 60_000;

#[derive(Default, Clone, Debug)]
pub struct RoundOutcome {
    pub pushed: usize,
    pub pulled: usize,
    pub changed: bool,
    pub notices: Vec<SyncNoticeDto>,
}

#[derive(Default)]
struct PushSummary {
    synced: usize,
    rejected: usize,
    notices: Vec<SyncNoticeDto>,
}

fn db_error() -> ApiError {
    ApiError::local("数据库连接锁失败，请重试")
}

fn local(error: impl std::fmt::Display) -> ApiError {
    ApiError::local(error.to_string())
}

/// 执行一轮完整同步；同一时刻只允许一个 round 在跑。
pub async fn run_once(state: &AppState) -> Result<RoundOutcome, ApiError> {
    let _guard = state.sync.round.lock().await;

    let signed_in = match state.sync.runtime.lock() {
        Ok(runtime) => runtime.signed_in,
        Err(_) => return Err(db_error()),
    };
    if !signed_in {
        if let Ok(mut runtime) = state.sync.runtime.lock() {
            runtime.status = SyncStatus::SignedOut;
        }
        return Ok(RoundOutcome::default());
    }
    if let Ok(mut runtime) = state.sync.runtime.lock() {
        runtime.status = SyncStatus::Syncing;
    }

    let result = run_round(state).await;

    if let Ok(mut runtime) = state.sync.runtime.lock() {
        match &result {
            Ok(_) => {
                runtime.status = SyncStatus::Synced;
                runtime.last_synced_at = Some(storage::now_ms());
                runtime.last_error = None;
            }
            Err(error) => {
                runtime.last_error = Some(error.message.clone());
                runtime.status = if error.code == "auth_required" {
                    SyncStatus::AuthRequired
                } else if error.is_retryable() {
                    SyncStatus::Retrying
                } else {
                    SyncStatus::Offline
                };
            }
        }
    }
    result
}

async fn run_round(state: &AppState) -> Result<RoundOutcome, ApiError> {
    let (mut session, device) = {
        let conn = state.db.lock().map_err(|_| db_error())?;
        let session = session::load_session(&conn)
            .map_err(local)?
            .ok_or_else(ApiError::auth_required)?;
        let device = storage::device_id(&conn).map_err(local)?;
        (session, device)
    };

    let api = Api::new(&session.account.server_url)?;
    ensure_fresh_token(&api, &mut session, &device).await?;

    let mut outcome = RoundOutcome::default();

    let push = push_all(state, &api, &mut session, &device).await?;
    outcome.pushed = push.synced;
    outcome.changed |= push.synced > 0 || push.rejected > 0;
    outcome.notices.extend(push.notices);

    let pull = pull_all(state, &api, &mut session).await?;
    outcome.pulled = pull.0;
    outcome.changed |= pull.1;
    outcome.notices.extend(pull.2);

    outcome.changed |= fix_active_workspace(state)?;

    Ok(outcome)
}

async fn ensure_fresh_token(
    api: &Api,
    session: &mut Session,
    device: &str,
) -> Result<(), ApiError> {
    let now = storage::now_ms();
    if session.tokens.access_expires_at - now > REFRESH_AHEAD_MS {
        return Ok(());
    }
    refresh_tokens(api, session, device).await
}

async fn refresh_tokens(api: &Api, session: &mut Session, device: &str) -> Result<(), ApiError> {
    let response = api
        .refresh(&session.tokens.refresh_token, device)
        .await
        .map_err(|error| {
            if error.is_refresh_invalid() || error.is_unauthorized() {
                ApiError::auth_required()
            } else {
                error
            }
        })?;
    session.tokens = Tokens::from(&response.tokens);
    session::save_tokens(&session.account, &session.tokens).map_err(local)
}

async fn push_all(
    state: &AppState,
    api: &Api,
    session: &mut Session,
    device: &str,
) -> Result<PushSummary, ApiError> {
    let mut summary = PushSummary::default();
    let mut batch_size = PUSH_BATCH;
    let mut auth_retried = false;

    loop {
        let batch = {
            let conn = state.db.lock().map_err(|_| db_error())?;
            storage::pending_ops(&conn, batch_size).map_err(local)?
        };
        if batch.is_empty() {
            break;
        }

        match push_batch(state, api, session, device, &batch).await {
            Ok(result) => {
                summary.synced += result.synced;
                summary.rejected += result.rejected;
                summary.notices.extend(result.notices);
                auth_retried = false;
                // 队列已清空或本批未满，下一轮再查一次即可。
                if batch.len() < batch_size {
                    continue;
                }
            }
            Err(error) if error.status == 401 && !auth_retried => {
                refresh_tokens(api, session, device).await?;
                auth_retried = true;
            }
            Err(error) if error.status == 413 && batch.len() > 1 => {
                batch_size = (batch.len() / 2).max(1);
            }
            Err(error) if matches!(error.status, 400 | 403) => {
                // 整批被拒绝：保留在本地供导出，但不再重试，也不参与投影。
                mark_rejected_all(state, &batch, &format!("{}: {}", error.code, error.message))?;
                summary.rejected += batch.len();
                if let Some(notice) =
                    notice_from_parts(&error.code, &error.message, error.retryable)
                {
                    summary.notices.push(notice);
                }
                break;
            }
            Err(error) => return Err(error),
        }
    }

    Ok(summary)
}

async fn push_batch(
    state: &AppState,
    api: &Api,
    session: &Session,
    device: &str,
    ops: &[Op],
) -> Result<PushSummary, ApiError> {
    let response = api.push(&session.tokens.access_token, device, ops).await?;

    if let Some(hlc) = &response.server_hlc {
        observe(state, hlc.physical_ms, hlc.counter);
    }

    let mut synced: Vec<(String, i64)> = Vec::new();
    let mut rejected: Vec<(String, String)> = Vec::new();
    let mut notices = Vec::new();

    if response.results.is_empty() {
        if let Some(error) = &response.error {
            let reason = format!("{}: {}", error.code, error.message);
            for op in ops {
                rejected.push((op.op_id.clone(), reason.clone()));
            }
            if let Some(notice) = notice_from_parts(&error.code, &error.message, error.retryable) {
                notices.push(notice);
            }
        } else {
            return Err(ApiError::local("推送响应缺少 results，请重试"));
        }
    } else {
        for op in ops {
            match response
                .results
                .iter()
                .find(|result| result.op_id == op.op_id)
            {
                Some(result) if matches!(result.status.as_str(), "accepted" | "duplicate") => {
                    match result.seq {
                        Some(seq) => synced.push((op.op_id.clone(), seq)),
                        None => rejected.push((op.op_id.clone(), "服务端未返回 seq".to_string())),
                    }
                }
                Some(result) => {
                    let reason = result
                        .error
                        .as_ref()
                        .map(|error| format!("{}: {}", error.code, error.message))
                        .unwrap_or_else(|| result.status.clone());
                    rejected.push((op.op_id.clone(), reason));
                    if let Some(error) = &result.error {
                        if let Some(notice) = notice_from_wire(error) {
                            notices.push(notice);
                        }
                    }
                }
                None => rejected.push((op.op_id.clone(), "服务端未返回该 op 的结果".to_string())),
            }
        }
    }

    apply_push_results(state, &synced, &rejected)?;
    Ok(PushSummary {
        synced: synced.len(),
        rejected: rejected.len(),
        notices,
    })
}

fn apply_push_results(
    state: &AppState,
    synced: &[(String, i64)],
    rejected: &[(String, String)],
) -> Result<(), ApiError> {
    let conn = state.db.lock().map_err(|_| db_error())?;
    storage::with_transaction(&conn, |tx| {
        storage::mark_ops_synced(tx, synced)?;
        for (op_id, reason) in rejected {
            storage::mark_op_rejected(tx, op_id, reason)?;
        }
        Ok(())
    })
    .map_err(local)
}

fn mark_rejected_all(state: &AppState, ops: &[Op], reason: &str) -> Result<(), ApiError> {
    let conn = state.db.lock().map_err(|_| db_error())?;
    storage::with_transaction(&conn, |tx| {
        for op in ops {
            storage::mark_op_rejected(tx, &op.op_id, reason)?;
        }
        Ok(())
    })
    .map_err(local)
}

async fn pull_all(
    state: &AppState,
    api: &Api,
    session: &mut Session,
) -> Result<(usize, bool, Vec<SyncNoticeDto>), ApiError> {
    let mut pulled = 0usize;
    let mut changed = false;
    let notices = Vec::new();
    let mut pages = 0usize;
    let mut auth_retried = false;

    loop {
        let cursor = {
            let conn = state.db.lock().map_err(|_| db_error())?;
            storage::cursor(&conn)
        };
        let response = match api
            .pull(&session.tokens.access_token, cursor, PULL_LIMIT)
            .await
        {
            Ok(response) => response,
            Err(error) if error.status == 401 && !auth_retried => {
                refresh_tokens(api, session, &device_id(state)?).await?;
                auth_retried = true;
                continue;
            }
            Err(error) => return Err(error),
        };

        let items: Vec<PulledOp> = response
            .ops
            .iter()
            .filter_map(|wire| {
                let seq = wire.seq?;
                Some(PulledOp {
                    op: wire.to_local(),
                    seq,
                    received_at: wire.received_at,
                })
            })
            .collect();

        let next_seq = response.next_seq;
        let inserted = {
            let conn = state.db.lock().map_err(|_| db_error())?;
            storage::with_transaction(&conn, |tx| {
                let inserted = storage::insert_pulled_ops(tx, &items)?;
                storage::set_cursor(tx, next_seq)?;
                Ok(inserted)
            })
            .map_err(local)?
        };

        observe(
            state,
            response.server_hlc.physical_ms,
            response.server_hlc.counter,
        );

        pulled += items.len();
        changed |= inserted > 0;
        pages += 1;

        if !response.has_more || pages >= PULL_MAX_PAGES {
            break;
        }
    }

    Ok((pulled, changed, notices))
}

fn device_id(state: &AppState) -> Result<String, ApiError> {
    let conn = state.db.lock().map_err(|_| db_error())?;
    storage::device_id(&conn).map_err(local)
}

fn observe(state: &AppState, physical_ms: i64, counter: u64) {
    if let Ok(conn) = state.db.lock() {
        let _ = storage::observe_hlc(&conn, physical_ms, counter);
    }
}

fn fix_active_workspace(state: &AppState) -> Result<bool, ApiError> {
    let conn = state.db.lock().map_err(|_| db_error())?;
    let active = storage::active_workspace_id(&conn);
    let projection =
        storage::build_projection(&conn, active.as_deref().unwrap_or_default()).map_err(local)?;
    let resolved = projection.active_workspace_id;
    if resolved.is_empty() {
        return Ok(false);
    }
    if active.as_deref() != Some(resolved.as_str()) {
        storage::pref_set(&conn, "active_workspace_id", &resolved).map_err(local)?;
        return Ok(true);
    }
    Ok(false)
}

fn notice_from_wire(error: &WireError) -> Option<SyncNoticeDto> {
    notice_from_parts(&error.code, &error.message, error.retryable)
}

fn notice_from_parts(code: &str, message: &str, retryable: bool) -> Option<SyncNoticeDto> {
    let (kind, text) = match code {
        "workspace_access_revoked" | "forbidden" => {
            ("permission", "权限已变更，此操作未生效".to_string())
        }
        "hlc_too_far_ahead" => (
            "clock_skew",
            "本机时间偏差较大，已按服务器时间校正".to_string(),
        ),
        "actor_mismatch" | "unauthorized" | "token_expired" => ("auth", message.to_string()),
        "invalid_op_envelope"
        | "payload_too_large"
        | "invalid_value"
        | "unknown_field"
        | "invalid_operation"
        | "entity_not_created"
        | "entity_workspace_mismatch"
        | "unknown_list"
        | "unknown_user" => ("error", format!("{code}：{message}")),
        _ => return None,
    };
    Some(SyncNoticeDto {
        kind: kind.to_string(),
        code: code.to_string(),
        message: text,
        retryable,
        at: storage::now_ms(),
    })
}

/// 登录/注册后或成员 REST 成功后立刻全量拉取一次。
/// 与后台轮次共用 `round` 锁，避免并发推进游标。
pub async fn pull_initial(state: &AppState) -> Result<RoundOutcome, ApiError> {
    let _guard = state.sync.round.lock().await;
    let (mut session, device) = {
        let conn = state.db.lock().map_err(|_| db_error())?;
        let session = session::load_session(&conn)
            .map_err(local)?
            .ok_or_else(ApiError::auth_required)?;
        let device = storage::device_id(&conn).map_err(local)?;
        (session, device)
    };
    let api = Api::new(&session.account.server_url)?;
    ensure_fresh_token(&api, &mut session, &device).await?;
    let (pulled, changed, notices) = pull_all(state, &api, &mut session).await?;
    let changed = changed || fix_active_workspace(state)?;
    Ok(RoundOutcome {
        pushed: 0,
        pulled,
        changed,
        notices,
    })
}

/// 供成员等 REST 命令复用：未登录返回 None，登录态会自动刷新即将过期的 access token。
pub async fn authorized_session(state: &AppState) -> Result<Option<(Api, Session)>, ApiError> {
    let (session, device) = {
        let conn = state.db.lock().map_err(|_| db_error())?;
        let session = session::load_session(&conn).map_err(local)?;
        let device = storage::device_id(&conn).map_err(local)?;
        (session, device)
    };
    let Some(mut session) = session else {
        return Ok(None);
    };
    let api = Api::new(&session.account.server_url)?;
    ensure_fresh_token(&api, &mut session, &device).await?;
    Ok(Some((api, session)))
}
