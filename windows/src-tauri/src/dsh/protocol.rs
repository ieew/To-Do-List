//! dsh 通道的纯逻辑：wire 结构与解析、剪贴板判定、通知等级。
//!
//! 这一层不依赖 Tauri / reqwest / MQTT，可以在 `--no-default-features` 下单测。

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::hash::{Hash, Hasher};

/// 服务端 `MAX_PAYLOAD_BYTES`；剪贴板文本按 UTF-8 字节计算。
pub const MAX_CLIP_BYTES: usize = 32 * 1024;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct MqttEndpoint {
    pub enabled: bool,
    pub online: bool,
    pub host: String,
    pub port: u16,
    pub tls: bool,
    pub username: String,
    pub topic_in: String,
    pub topic_out: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct RegisterDeviceResponse {
    pub device_id: String,
    #[allow(dead_code)]
    pub action: String,
    pub ticket: String,
    pub ticket_expires_at: i64,
    pub mqtt: MqttEndpoint,
}

#[derive(Clone, Debug, Deserialize)]
pub struct DshMessage {
    pub seq: i64,
    pub msg_id: String,
    pub kind: String,
    pub payload: Value,
    #[allow(dead_code)]
    pub device_id: Option<String>,
    #[allow(dead_code)]
    pub created_at: i64,
    #[allow(dead_code)]
    pub expires_at: i64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct PullResponse {
    pub messages: Vec<DshMessage>,
    pub next_seq: i64,
    pub has_more: bool,
    #[allow(dead_code)]
    pub latest_seq: i64,
    #[allow(dead_code)]
    pub server_ms: i64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct AckResponse {
    #[allow(dead_code)]
    pub device_id: String,
    pub acked: i64,
    #[allow(dead_code)]
    pub requested: i64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct ClipResponse {
    #[allow(dead_code)]
    pub msg_id: String,
    #[allow(dead_code)]
    pub stored: bool,
    pub delivered: i64,
    #[allow(dead_code)]
    pub nudged: bool,
    #[allow(dead_code)]
    pub seq: i64,
}

/// MQTT 主题上的三种载荷：nudge（推送唤醒）、clip（剪贴板直传）与 sync（数据变更）。
#[derive(Clone, Debug, PartialEq)]
pub enum MqttPayload {
    Nudge { seq: i64 },
    Clip { id: String, text: String, fp: String },
    /// 其它设备提交了 op：收到后立即跑一次 `sync/pull`。不落库、不动 dsh 游标。
    Sync { seq: i64, workspaces: Vec<String> },
}

/// 剪贴板 payload 有三种形态：
/// - `/v1/dsh/clip` 直传时 MQTT 载荷是字符串 `p`；
/// - 落库模式 pull 回来是对象 `{"text": "...", "fp": "..."}`；
/// - `push(kind=clip)` 直传又是任意 JSON。
pub fn clip_text(payload: &Value) -> Option<String> {
    match payload {
        Value::String(text) => Some(text.clone()),
        Value::Object(map) => map
            .get("text")
            .and_then(Value::as_str)
            .map(str::to_string),
        Value::Null | Value::Bool(_) | Value::Number(_) | Value::Array(_) => None,
    }
}

pub fn clip_fp(payload: &Value) -> String {
    payload
        .as_object()
        .and_then(|map| map.get("fp"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

/// 客户端指纹：只用于抑制「远端写入 → 又被本机当成新复制上传」的回流。
/// 不要求跨进程稳定，服务端也只透传。
pub fn fingerprint(text: &str) -> String {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    text.hash(&mut hasher);
    format!("{:016x}", hasher.finish())
}

/// 服务端级别 0–7；`payload.level` 优先，老格式 `silent=true` 视为 1，缺省 3。
pub fn level_of(payload: &Value) -> u8 {
    let map = payload.as_object();
    if let Some(level) = map
        .and_then(|value| value.get("level"))
        .and_then(Value::as_u64)
    {
        return level.min(7) as u8;
    }
    if map
        .and_then(|value| value.get("silent"))
        .and_then(Value::as_bool)
        == Some(true)
    {
        return 1;
    }
    3
}

pub fn clip_within_limit(text: &str) -> bool {
    !text.is_empty() && text.len() <= MAX_CLIP_BYTES
}

/// 是否需要把本机剪贴板上行：空文本、超限、与上次相同都跳过。
pub fn should_upload(last_seen: Option<&str>, text: &str) -> bool {
    clip_within_limit(text) && last_seen != Some(text)
}

/// 服务端提醒与本地提醒的去重键：`task_id + remind_at`。
pub fn reminder_already_fired(last_fired_at: Option<i64>, remind_at_ms: i64) -> bool {
    last_fired_at == Some(remind_at_ms)
}

pub fn parse_rfc3339_ms(raw: &str) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(raw)
        .ok()
        .map(|value| value.timestamp_millis())
}

pub fn parse_mqtt_payload(bytes: &[u8]) -> Option<MqttPayload> {
    let value: Value = serde_json::from_slice(bytes).ok()?;
    match value.get("t")?.as_str()? {
        "nudge" => Some(MqttPayload::Nudge {
            seq: value.get("seq").and_then(Value::as_i64).unwrap_or(0),
        }),
        "clip" => Some(MqttPayload::Clip {
            id: value
                .get("id")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
            text: clip_text(value.get("p")?)?,
            fp: value
                .get("fp")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string(),
        }),
        "sync" => Some(MqttPayload::Sync {
            seq: value.get("seq").and_then(Value::as_i64).unwrap_or(0),
            workspaces: value
                .get("ws")
                .and_then(Value::as_array)
                .map(|items| {
                    items
                        .iter()
                        .filter_map(Value::as_str)
                        .map(str::to_string)
                        .collect()
                })
                .unwrap_or_default(),
        }),
        _ => None,
    }
}

/// 服务端 `kind=notify` 的 payload 解析结果。
#[derive(Clone, Debug, Default)]
pub struct RemoteNotice {
    pub title: String,
    pub body: String,
    pub task_id: Option<String>,
    pub workspace_id: Option<String>,
    pub remind_at_ms: Option<i64>,
    pub is_reminder: bool,
    pub level: u8,
}

pub fn parse_notice(payload: &Value) -> RemoteNotice {
    let map = payload.as_object();
    let text = |key: &str| {
        map.and_then(|value| value.get(key))
            .and_then(Value::as_str)
            .map(str::to_string)
    };
    let remind_at_ms = text("remind_at").as_deref().and_then(parse_rfc3339_ms);
    RemoteNotice {
        title: text("title").unwrap_or_else(|| "待办提醒".into()),
        body: text("body").unwrap_or_default(),
        task_id: text("task_id"),
        workspace_id: text("workspace_id"),
        remind_at_ms,
        is_reminder: text("type").as_deref() == Some("reminder") || remind_at_ms.is_some(),
        level: level_of(payload),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn clip_payload_accepts_string_and_object() {
        assert_eq!(
            clip_text(&json!("hello")).as_deref(),
            Some("hello")
        );
        assert_eq!(
            clip_text(&json!({"text": "world", "fp": "abc"})).as_deref(),
            Some("world")
        );
        assert_eq!(clip_text(&json!({"foo": 1})), None);
        assert_eq!(clip_text(&json!(null)), None);
    }

    #[test]
    fn level_prefers_payload_level_then_silent() {
        assert_eq!(level_of(&json!({"level": 0})), 0);
        assert_eq!(level_of(&json!({"level": 9})), 7);
        assert_eq!(level_of(&json!({"silent": true})), 1);
        assert_eq!(level_of(&json!({})), 3);
    }

    #[test]
    fn upload_skips_empty_oversize_and_unchanged() {
        assert!(!should_upload(None, ""));
        assert!(!should_upload(Some("same"), "same"));
        assert!(should_upload(Some("old"), "new"));
        let too_large = "a".repeat(MAX_CLIP_BYTES + 1);
        assert!(!should_upload(None, &too_large));
        let exact = "a".repeat(MAX_CLIP_BYTES);
        assert!(should_upload(None, &exact));
    }

    #[test]
    fn reminder_dedupe_uses_exact_value() {
        assert!(reminder_already_fired(Some(1000), 1000));
        assert!(!reminder_already_fired(Some(2000), 1000));
        assert!(!reminder_already_fired(None, 1000));
    }

    #[test]
    fn parses_mqtt_nudge_and_clip() {
        let nudge = parse_mqtt_payload(br#"{"t":"nudge","seq":42,"n":2}"#);
        assert_eq!(nudge, Some(MqttPayload::Nudge { seq: 42 }));

        let clip = parse_mqtt_payload(br#"{"t":"clip","id":"m1","p":"hi","fp":"f1"}"#);
        assert_eq!(
            clip,
            Some(MqttPayload::Clip {
                id: "m1".into(),
                text: "hi".into(),
                fp: "f1".into(),
            })
        );

        let bad = parse_mqtt_payload(br#"{"t":"other"}"#);
        assert_eq!(bad, None);
    }

    #[test]
    fn parses_data_change_signal() {
        // 其它设备提交 op 后，服务端发 sync 信号，带全局 server_seq 与受影响工作区。
        assert_eq!(
            parse_mqtt_payload(br#"{"t":"sync","seq":42,"ws":["ws-1"]}"#),
            Some(MqttPayload::Sync {
                seq: 42,
                workspaces: vec!["ws-1".into()],
            })
        );
        // 旧服务端只发 {"t":"sync"}：也要能识别
        assert_eq!(
            parse_mqtt_payload(br#"{"t":"sync"}"#),
            Some(MqttPayload::Sync {
                seq: 0,
                workspaces: Vec::new(),
            })
        );
    }

    #[test]
    fn parses_reminder_notice() {
        let payload = json!({
            "title": "写周报",
            "body": "任务到期提醒",
            "type": "reminder",
            "task_id": "t1",
            "workspace_id": "ws1",
            "remind_at": "2026-11-24T10:00:00+08:00",
            "level": 6
        });
        let notice = parse_notice(&payload);
        assert!(notice.is_reminder);
        assert_eq!(notice.title, "写周报");
        assert_eq!(notice.task_id.as_deref(), Some("t1"));
        assert_eq!(notice.workspace_id.as_deref(), Some("ws1"));
        assert_eq!(notice.level, 6);
        assert!(notice.remind_at_ms.is_some());
    }

    #[test]
    fn missing_type_with_remind_at_is_treated_as_reminder() {
        let notice = parse_notice(&json!({"remind_at": "2026-11-24T10:00:00Z"}));
        assert!(notice.is_reminder);
    }

    #[test]
    fn invalid_remind_at_does_not_disable_delivery() {
        let notice = parse_notice(&json!({"type": "reminder", "remind_at": "not-a-date"}));
        assert!(notice.is_reminder);
        assert!(notice.remind_at_ms.is_none());
    }
}
