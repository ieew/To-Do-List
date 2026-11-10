//! 线上（wire）格式与本地 `Op` 的双向映射。
//!
//! 服务端信封是 snake_case + 嵌套 `hlc` + `source_meta`；客户端领域层保持扁平
//! camelCase。同步层是唯一做转换的地方，`core_domain` 不感知 wire 格式。

use crate::core_domain::Op;
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireHlc {
    pub physical_ms: i64,
    pub counter: u64,
    pub node_id: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct WireSourceMeta {
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub prompt_hash: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WireOp {
    pub op_id: String,
    pub workspace_id: String,
    pub entity_type: String,
    pub entity_id: String,
    pub actor_id: String,
    pub device_id: String,
    pub operation: String,
    pub field: String,
    pub value: Value,
    pub hlc: WireHlc,
    pub source: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_meta: Option<WireSourceMeta>,
    /// 仅 pull 返回。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seq: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub received_at: Option<i64>,
}

impl WireOp {
    pub fn to_local(&self) -> Op {
        Op {
            op_id: self.op_id.clone(),
            workspace_id: self.workspace_id.clone(),
            entity_type: self.entity_type.clone(),
            entity_id: self.entity_id.clone(),
            actor_id: self.actor_id.clone(),
            device_id: self.device_id.clone(),
            operation: self.operation.clone(),
            field: self.field.clone(),
            value: self.value.clone(),
            physical_ms: self.hlc.physical_ms,
            counter: self.hlc.counter,
            node_id: self.hlc.node_id.clone(),
            source: self.source.clone(),
            model: self
                .source_meta
                .as_ref()
                .and_then(|meta| meta.model.clone()),
        }
    }
}

pub fn to_wire(op: &Op) -> WireOp {
    WireOp {
        op_id: op.op_id.clone(),
        workspace_id: op.workspace_id.clone(),
        entity_type: op.entity_type.clone(),
        entity_id: op.entity_id.clone(),
        actor_id: op.actor_id.clone(),
        device_id: op.device_id.clone(),
        operation: op.operation.clone(),
        field: op.field.clone(),
        value: op.value.clone(),
        hlc: WireHlc {
            physical_ms: op.physical_ms,
            counter: op.counter,
            node_id: op.node_id.clone(),
        },
        source: op.source.clone(),
        source_meta: Some(WireSourceMeta {
            model: op.model.clone(),
            prompt_hash: None,
        }),
        seq: None,
        received_at: None,
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
pub struct WireError {
    pub code: String,
    pub message: String,
    #[serde(default)]
    pub retryable: bool,
    #[serde(default)]
    pub server_hlc: Option<WireHlc>,
    #[serde(default)]
    pub details: Option<Value>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct WireErrorEnvelope {
    pub error: WireError,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sample() -> Op {
        Op {
            op_id: "op-1".into(),
            workspace_id: "ws-1".into(),
            entity_type: "task".into(),
            entity_id: "task-1".into(),
            actor_id: "u-1".into(),
            device_id: "win-1".into(),
            operation: "set".into(),
            field: "title".into(),
            value: json!("买菜"),
            physical_ms: 1_700_000_000_000,
            counter: 3,
            node_id: "win-1".into(),
            source: "human".into(),
            model: Some("qwen".into()),
        }
    }

    #[test]
    fn wire_uses_nested_hlc_and_snake_case() {
        let wire = serde_json::to_value(to_wire(&sample())).expect("序列化失败");
        assert_eq!(wire["physical_ms"], Value::Null);
        assert_eq!(wire["hlc"]["physical_ms"], json!(1_700_000_000_000_i64));
        assert_eq!(wire["hlc"]["counter"], json!(3));
        assert_eq!(wire["source_meta"]["model"], json!("qwen"));
        assert_eq!(wire["source_meta"]["prompt_hash"], Value::Null);
        assert!(wire.get("physicalMs").is_none());
    }

    #[test]
    fn round_trip_preserves_domain_fields() {
        let original = sample();
        let parsed: WireOp =
            serde_json::from_value(serde_json::to_value(to_wire(&original)).expect("序列化失败"))
                .expect("反序列化失败");
        let restored = parsed.to_local();
        assert_eq!(restored.op_id, original.op_id);
        assert_eq!(restored.value, original.value);
        assert_eq!(restored.physical_ms, original.physical_ms);
        assert_eq!(restored.counter, original.counter);
        assert_eq!(restored.node_id, original.node_id);
        assert_eq!(restored.model, original.model);
    }

    #[test]
    fn parses_pull_extras_and_ignores_unknown_fields() {
        let raw = json!({
            "op_id": "op-1",
            "workspace_id": "ws-1",
            "entity_type": "task",
            "entity_id": "task-1",
            "actor_id": "u-1",
            "device_id": "win-1",
            "operation": "set",
            "field": "title",
            "value": "x",
            "hlc": { "physical_ms": 1, "counter": 0, "node_id": "n" },
            "source": "human",
            "source_meta": { "model": null, "prompt_hash": null },
            "seq": 42,
            "received_at": 99
        });
        let op: WireOp = serde_json::from_value(raw).expect("解析失败");
        assert_eq!(op.seq, Some(42));
        assert_eq!(op.received_at, Some(99));
        assert_eq!(op.to_local().model, None);
    }
}
