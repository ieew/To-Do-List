//! `/v1/dsh/*` 的 HTTP 客户端。
//!
//! - 设备注册 / 测试推送用登录态 Bearer（由 `engine::authorized_session` 自动续期）；
//! - pull / ack / 剪贴板上行都用设备票据（`X-Dsh-Device-Id` + `X-Dsh-Ticket`），
//!   既不占用 access token 刷新，也能让服务端把本机识别为剪贴板来源并排除回显。

use super::protocol::{
    AckResponse, ClipResponse, PullResponse, RegisterDeviceResponse,
};
use crate::sync::client::{normalize_base, parse_error, ApiError};
use reqwest::{Client, RequestBuilder};
use serde::de::DeserializeOwned;
use serde_json::{json, Value};
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const TOTAL_TIMEOUT: Duration = Duration::from_secs(20);

#[derive(Clone, Debug)]
pub struct DshApi {
    base_url: String,
    http: Client,
}

impl DshApi {
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

    pub async fn register_device(
        &self,
        access_token: &str,
        name: &str,
        platform: &str,
        device_id: Option<&str>,
    ) -> Result<RegisterDeviceResponse, ApiError> {
        let mut body = json!({ "name": name, "platform": platform });
        if let Some(device_id) = device_id {
            body["device_id"] = Value::String(device_id.to_string());
        }
        self.send(
            self.http
                .post(self.url("/dsh/devices/register"))
                .bearer_auth(access_token)
                .json(&body),
        )
        .await
    }

    /// pull/ack 优先用设备票据：不依赖 access token 刷新，也不会和同步循环抢刷新令牌。
    pub async fn pull_as_device(
        &self,
        device_id: &str,
        ticket: &str,
        since: i64,
        limit: usize,
    ) -> Result<PullResponse, ApiError> {
        self.send(
            self.http
                .get(self.url("/dsh/pull"))
                .header("X-Dsh-Device-Id", device_id)
                .header("X-Dsh-Ticket", ticket)
                .query(&[
                    ("since", since.to_string()),
                    ("limit", limit.to_string()),
                ]),
        )
        .await
    }

    pub async fn ack_as_device(
        &self,
        device_id: &str,
        ticket: &str,
        seqs: &[i64],
    ) -> Result<AckResponse, ApiError> {
        self.send(
            self.http
                .post(self.url("/dsh/ack"))
                .header("X-Dsh-Device-Id", device_id)
                .header("X-Dsh-Ticket", ticket)
                .json(&json!({ "seqs": seqs })),
        )
        .await
    }

    /// 上行剪贴板：设备票据让服务端把本机当作来源并从投递中排除。
    pub async fn clip_as_device(
        &self,
        device_id: &str,
        ticket: &str,
        text: &str,
        fp: &str,
        msg_id: &str,
    ) -> Result<ClipResponse, ApiError> {
        self.send(
            self.http
                .post(self.url("/dsh/clip"))
                .header("X-Dsh-Device-Id", device_id)
                .header("X-Dsh-Ticket", ticket)
                .json(&json!({ "text": text, "fp": fp, "msg_id": msg_id })),
        )
        .await
    }

    /// 测试按钮用：发一条通知给自己（服务端会广播到全部设备）。
    pub async fn push_notify(
        &self,
        access_token: &str,
        title: &str,
        body: &str,
        level: u8,
        msg_id: &str,
    ) -> Result<Value, ApiError> {
        self.send(
            self.http
                .post(self.url("/dsh/push"))
                .bearer_auth(access_token)
                .json(&json!({
                    "kind": "notify",
                    "payload": { "title": title, "body": body },
                    "level": level,
                    "msg_id": msg_id,
                })),
        )
        .await
    }
}
