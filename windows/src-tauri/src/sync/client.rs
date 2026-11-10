//! 服务端 HTTP 客户端。只依赖 reqwest，不接触 SQLite 与 UI。

use super::wire::{to_wire, WireErrorEnvelope, WireHlc, WireOp};
use crate::core_domain::Op;
use reqwest::{Client, RequestBuilder};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const TOTAL_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Clone, Debug)]
pub struct ApiError {
    pub status: u16,
    pub code: String,
    pub message: String,
    pub retryable: bool,
    pub server_hlc: Option<WireHlc>,
    pub network: bool,
}

impl ApiError {
    pub fn network(error: impl std::fmt::Display) -> Self {
        Self {
            status: 0,
            code: "network_error".into(),
            message: format!("无法连接服务端：{error}"),
            retryable: true,
            server_hlc: None,
            network: true,
        }
    }

    pub fn auth_required() -> Self {
        Self {
            status: 401,
            code: "auth_required".into(),
            message: "登录状态已失效，请重新登录".into(),
            retryable: false,
            server_hlc: None,
            network: false,
        }
    }

    pub fn local(message: impl Into<String>) -> Self {
        Self {
            status: 0,
            code: "local_error".into(),
            message: message.into(),
            retryable: false,
            server_hlc: None,
            network: false,
        }
    }

    pub fn is_unauthorized(&self) -> bool {
        self.status == 401
    }

    pub fn is_refresh_invalid(&self) -> bool {
        self.code == "invalid_refresh_token"
    }

    pub fn is_retryable(&self) -> bool {
        self.network || self.retryable || self.status >= 500
    }
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct RegisterBody {
    pub username: String,
    pub password: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub workspace_name: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct UserOut {
    pub user_id: String,
    pub username: String,
    pub display_name: String,
    #[serde(default)]
    pub email: Option<String>,
    #[allow(dead_code)]
    pub created_at: String,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct TokenPair {
    #[allow(dead_code)]
    pub token_type: String,
    pub access_token: String,
    pub access_expires_at: i64,
    pub refresh_token: String,
    pub refresh_expires_at: i64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WorkspaceSummary {
    pub workspace_id: String,
    #[allow(dead_code)]
    pub name: String,
    #[allow(dead_code)]
    pub role: String,
    #[allow(dead_code)]
    pub member_count: i64,
    #[allow(dead_code)]
    pub created_at: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct RegisterResponse {
    pub user: UserOut,
    pub default_workspace_id: String,
    pub tokens: TokenPair,
}

#[derive(Clone, Debug, Deserialize)]
pub struct LoginResponse {
    pub user: UserOut,
    pub workspaces: Vec<WorkspaceSummary>,
    pub tokens: TokenPair,
}

#[derive(Clone, Debug, Deserialize)]
pub struct RefreshResponse {
    pub tokens: TokenPair,
}

#[derive(Clone, Debug, Deserialize)]
pub struct PushResult {
    pub op_id: String,
    pub status: String,
    #[serde(default)]
    pub seq: Option<i64>,
    #[serde(default)]
    pub error: Option<super::wire::WireError>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct PushEnvelope {
    #[serde(default)]
    pub server_hlc: Option<WireHlc>,
    #[serde(default)]
    pub server_seq: Option<i64>,
    #[serde(default)]
    pub results: Vec<PushResult>,
    #[serde(default)]
    pub error: Option<super::wire::WireError>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct PullResponse {
    pub ops: Vec<WireOp>,
    pub next_seq: i64,
    pub has_more: bool,
    #[allow(dead_code)]
    pub server_seq: i64,
    pub server_hlc: WireHlc,
}

#[derive(Clone, Debug, Deserialize)]
pub struct MemberOut {
    pub user_id: String,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub initial: String,
    #[serde(default)]
    pub color: String,
    #[serde(default)]
    pub email: Option<String>,
    #[serde(default)]
    pub role: String,
    #[allow(dead_code)]
    #[serde(default)]
    pub joined_at: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct MembersResponse {
    #[allow(dead_code)]
    pub workspace_id: String,
    pub members: Vec<MemberOut>,
}

#[derive(Clone, Debug)]
pub struct Api {
    pub base_url: String,
    http: Client,
}

/// 用户可能粘贴 `http://host:8000/` 或 `http://host:8000/v1`，统一成根地址。
pub fn normalize_base(raw: &str) -> String {
    let mut value = raw.trim().trim_end_matches('/').to_string();
    if value.is_empty() {
        value = "http://127.0.0.1:8000".to_string();
    }
    if let Some(stripped) = value.strip_suffix("/v1") {
        value = stripped.trim_end_matches('/').to_string();
    }
    value
}

impl Api {
    pub fn new(server_url: &str) -> Result<Self, ApiError> {
        let http = Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(TOTAL_TIMEOUT)
            .build()
            .map_err(ApiError::network)?;
        Ok(Self {
            base_url: normalize_base(server_url),
            http,
        })
    }

    fn url(&self, path: &str) -> String {
        format!("{}/v1{path}", self.base_url)
    }

    async fn send<T: DeserializeOwned>(&self, request: RequestBuilder) -> Result<T, ApiError> {
        let response = request.send().await.map_err(ApiError::network)?;
        let status = response.status().as_u16();
        let text = response.text().await.map_err(ApiError::network)?;
        if !(200..300).contains(&status) {
            return Err(parse_error(status, &text));
        }
        serde_json::from_str::<T>(&text).map_err(|error| ApiError {
            status,
            code: "invalid_response".into(),
            message: format!("服务端返回格式不正确：{error}"),
            retryable: false,
            server_hlc: None,
            network: false,
        })
    }

    async fn send_empty(&self, request: RequestBuilder) -> Result<(), ApiError> {
        let response = request.send().await.map_err(ApiError::network)?;
        let status = response.status().as_u16();
        let text = response.text().await.map_err(ApiError::network)?;
        if !(200..300).contains(&status) {
            return Err(parse_error(status, &text));
        }
        Ok(())
    }

    pub async fn health(&self) -> Result<serde_json::Value, ApiError> {
        self.send(self.http.get(self.url("/health"))).await
    }

    pub async fn register(&self, body: &RegisterBody) -> Result<RegisterResponse, ApiError> {
        self.send(self.http.post(self.url("/auth/register")).json(body))
            .await
    }

    pub async fn login(
        &self,
        username: &str,
        password: &str,
        device_id: &str,
    ) -> Result<LoginResponse, ApiError> {
        self.send(self.http.post(self.url("/auth/login")).json(&json!({
            "username": username,
            "password": password,
            "device_id": device_id,
        })))
        .await
    }

    pub async fn refresh(
        &self,
        refresh_token: &str,
        device_id: &str,
    ) -> Result<RefreshResponse, ApiError> {
        self.send(self.http.post(self.url("/auth/refresh")).json(&json!({
            "refresh_token": refresh_token,
            "device_id": device_id,
        })))
        .await
    }

    pub async fn logout(&self, refresh_token: &str) -> Result<(), ApiError> {
        self.send_empty(self.http.post(self.url("/auth/logout")).json(&json!({
            "refresh_token": refresh_token,
        })))
        .await
    }

    pub async fn push(
        &self,
        access_token: &str,
        device_id: &str,
        ops: &[Op],
    ) -> Result<PushEnvelope, ApiError> {
        let payload = json!({
            "device_id": device_id,
            "ops": ops.iter().map(to_wire).collect::<Vec<_>>(),
        });
        self.send(
            self.http
                .post(self.url("/sync/push"))
                .bearer_auth(access_token)
                .json(&payload),
        )
        .await
    }

    pub async fn pull(
        &self,
        access_token: &str,
        since_seq: i64,
        limit: usize,
    ) -> Result<PullResponse, ApiError> {
        self.send(
            self.http
                .get(self.url("/sync/pull"))
                .bearer_auth(access_token)
                .query(&[
                    ("since_seq", since_seq.to_string()),
                    ("limit", limit.to_string()),
                ]),
        )
        .await
    }

    pub async fn members(
        &self,
        access_token: &str,
        workspace_id: &str,
    ) -> Result<MembersResponse, ApiError> {
        self.send(
            self.http
                .get(self.url("/workspace/members"))
                .bearer_auth(access_token)
                .query(&[("workspace_id", workspace_id)]),
        )
        .await
    }

    pub async fn invite(
        &self,
        access_token: &str,
        workspace_id: &str,
        account: &str,
        role: &str,
    ) -> Result<serde_json::Value, ApiError> {
        self.send(
            self.http
                .post(self.url("/workspace/invite"))
                .bearer_auth(access_token)
                .json(&json!({
                    "workspace_id": workspace_id,
                    "account": account,
                    "role": role,
                })),
        )
        .await
    }

    pub async fn set_role(
        &self,
        access_token: &str,
        workspace_id: &str,
        user_id: &str,
        role: &str,
    ) -> Result<serde_json::Value, ApiError> {
        self.send(
            self.http
                .patch(self.url("/workspace/role"))
                .bearer_auth(access_token)
                .json(&json!({
                    "workspace_id": workspace_id,
                    "user_id": user_id,
                    "role": role,
                })),
        )
        .await
    }

    pub async fn remove_member(
        &self,
        access_token: &str,
        workspace_id: &str,
        user_id: &str,
    ) -> Result<(), ApiError> {
        self.send_empty(
            self.http
                .post(self.url("/workspace/remove"))
                .bearer_auth(access_token)
                .json(&json!({
                    "workspace_id": workspace_id,
                    "user_id": user_id,
                })),
        )
        .await
    }
}

fn parse_error(status: u16, text: &str) -> ApiError {
    if let Ok(envelope) = serde_json::from_str::<WireErrorEnvelope>(text) {
        return ApiError {
            status,
            code: envelope.error.code,
            message: envelope.error.message,
            retryable: envelope.error.retryable,
            server_hlc: envelope.error.server_hlc,
            network: false,
        };
    }
    ApiError {
        status,
        code: if status == 429 {
            "rate_limited".into()
        } else {
            "http_error".into()
        },
        message: format!("服务端返回 HTTP {status}"),
        retryable: status == 429 || status >= 500,
        server_hlc: None,
        network: false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalize_accepts_trailing_slash_and_v1() {
        assert_eq!(
            normalize_base("http://127.0.0.1:8000/"),
            "http://127.0.0.1:8000"
        );
        assert_eq!(
            normalize_base(" http://127.0.0.1:8000/v1/ "),
            "http://127.0.0.1:8000"
        );
        assert_eq!(normalize_base(""), "http://127.0.0.1:8000");
    }

    #[test]
    fn parses_error_envelope() {
        let error = parse_error(
            403,
            r#"{"error":{"code":"workspace_access_revoked","message":"权限已变更","retryable":false}}"#,
        );
        assert_eq!(error.code, "workspace_access_revoked");
        assert_eq!(error.status, 403);
        assert!(!error.is_retryable());
    }
}
