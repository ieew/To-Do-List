//! 会话与令牌存储。访问令牌 / 刷新令牌只进 Windows 凭据管理器，
//! 非敏感的账户快照（服务端地址、用户名、昵称）存进本地 preferences。

use super::client::TokenPair;
use crate::storage;
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

const KEYRING_SERVICE: &str = "com.todo.windows.session";

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountRecord {
    pub server_url: String,
    pub user_id: String,
    pub username: String,
    pub display_name: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Tokens {
    pub access_token: String,
    pub refresh_token: String,
    pub access_expires_at: i64,
    pub refresh_expires_at: i64,
}

impl From<&TokenPair> for Tokens {
    fn from(pair: &TokenPair) -> Self {
        Self {
            access_token: pair.access_token.clone(),
            refresh_token: pair.refresh_token.clone(),
            access_expires_at: pair.access_expires_at,
            refresh_expires_at: pair.refresh_expires_at,
        }
    }
}

#[derive(Clone, Debug)]
pub struct Session {
    pub account: AccountRecord,
    pub tokens: Tokens,
}

/// 集成测试用：设置 `TODO_SYNC_MEMORY_TOKENS=1` 时令牌只存进程内存，不碰凭据管理器。
fn memory_mode() -> bool {
    std::env::var("TODO_SYNC_MEMORY_TOKENS").is_ok()
}

fn memory_store() -> &'static Mutex<HashMap<String, String>> {
    static STORE: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    STORE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn keyring_user(account: &AccountRecord) -> String {
    format!("{}|{}", account.server_url, account.user_id)
}

fn keyring_entry(account: &AccountRecord) -> Result<keyring::Entry, String> {
    keyring::Entry::new(KEYRING_SERVICE, &keyring_user(account))
        .map_err(|error| format!("无法访问系统凭据管理器: {error}"))
}

pub fn save_account(conn: &Connection, account: &AccountRecord) -> Result<(), String> {
    let raw = serde_json::to_string(account).map_err(|error| error.to_string())?;
    storage::set_sync_account(conn, Some(&raw)).map_err(|error| error.to_string())
}

pub fn account(conn: &Connection) -> Option<AccountRecord> {
    storage::sync_account(conn).and_then(|raw| serde_json::from_str(&raw).ok())
}

pub fn save_tokens(account: &AccountRecord, tokens: &Tokens) -> Result<(), String> {
    let raw = serde_json::to_string(tokens).map_err(|error| error.to_string())?;
    if memory_mode() {
        memory_store()
            .lock()
            .map_err(|_| "令牌内存存储锁失败".to_string())?
            .insert(keyring_user(account), raw);
        return Ok(());
    }
    keyring_entry(account)?
        .set_password(&raw)
        .map_err(|error| format!("保存登录令牌失败: {error}"))
}

pub fn load_tokens(account: &AccountRecord) -> Result<Option<Tokens>, String> {
    if memory_mode() {
        let guard = memory_store()
            .lock()
            .map_err(|_| "令牌内存存储锁失败".to_string())?;
        return Ok(guard
            .get(&keyring_user(account))
            .and_then(|raw| serde_json::from_str(raw).ok()));
    }
    match keyring_entry(account)?.get_password() {
        Ok(raw) => Ok(serde_json::from_str(&raw).ok()),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(format!("读取登录令牌失败: {error}")),
    }
}

pub fn clear_tokens(account: &AccountRecord) -> Result<(), String> {
    if memory_mode() {
        if let Ok(mut guard) = memory_store().lock() {
            guard.remove(&keyring_user(account));
        }
        return Ok(());
    }
    let entry = keyring_entry(account)?;
    if let Err(error) = entry.delete_credential() {
        let text = error.to_string().to_lowercase();
        if !text.contains("no entry") && !text.contains("not found") && !text.contains("不存在")
        {
            return Err(format!("清除登录令牌失败: {error}"));
        }
    }
    Ok(())
}

/// 读取「账户快照 + 令牌」；任缺一视为未登录。
pub fn load_session(conn: &Connection) -> Result<Option<Session>, String> {
    let Some(account) = account(conn) else {
        return Ok(None);
    };
    let Some(tokens) = load_tokens(&account)? else {
        return Ok(None);
    };
    Ok(Some(Session { account, tokens }))
}

pub fn signed_in(conn: &Connection) -> bool {
    load_session(conn)
        .map(|session| session.is_some())
        .unwrap_or(false)
}
