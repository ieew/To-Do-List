"""op 数据结构与字段/值域校验（纯函数，不依赖框架与数据库）。"""

from __future__ import annotations

import json
import math
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from app.core_domain.errors import OpRejected
from app.core_domain.hlc import Hlc, OpKey, op_key

ENTITY_TYPES = frozenset({"workspace", "list", "tag", "task", "membership", "user"})
OPERATIONS = frozenset({"set", "add", "remove"})
SOURCES = frozenset({"human", "ai", "import", "system"})

SUBTASK_KINDS = frozenset({"text", "done", "order"})

# 字段 -> 值域种类。集合字段单独定义。
_SCALAR_RULES: dict[str, dict[str, str]] = {
    "workspace": {
        "__created": "rfc3339",
        "name": "workspace_name",
        "__deleted": "bool",
    },
    "list": {
        "__created": "rfc3339",
        "workspace_id": "workspace_ref",
        "name": "name60",
        "color": "color",
        "order": "number",
        "__deleted": "bool",
    },
    "tag": {
        "__created": "rfc3339",
        "workspace_id": "workspace_ref",
        "name": "name60",
        "bg": "color",
        "fg": "color",
        "show_in_sidebar": "bool",
        "__deleted": "bool",
    },
    "task": {
        "__created": "rfc3339",
        "__deleted": "bool",
        "title": "title",
        "notes": "notes",
        "start_at": "datetime_or_null",
        "due_at": "datetime_or_null",
        "remind_at": "datetime_or_null",
        "priority": "priority",
        "status": "status",
        "list_id": "list_ref",
        "order": "number",
        "__undo_of": "id64",
    },
    "membership": {
        "__role": "role",
        "__removed": "bool",
    },
    "user": {
        "name": "name24",
        "initial": "initial",
        "color": "color",
        "email": "email",
    },
}

_COLLECTIONS: dict[str, frozenset[str]] = {
    "task": frozenset({"__tag", "__subtask"}),
}

_EDITOR_MUTABLE_LIST_FIELDS = frozenset({"name", "color", "order"})
_EDITOR_MUTABLE_TAG_FIELDS = frozenset({"name", "bg", "fg", "show_in_sidebar"})

_SUBTASK_RULES = {"text": "subtask_text", "done": "bool", "order": "number"}


@dataclass(slots=True)
class Op:
    op_id: str
    workspace_id: str
    entity_type: str
    entity_id: str
    actor_id: str
    device_id: str
    operation: str
    field: str
    value: Any
    physical_ms: int
    counter: int
    node_id: str
    source: str
    model: str | None = None
    prompt_hash: str | None = None
    seq: int | None = None
    received_at: int | None = None

    @property
    def hlc(self) -> Hlc:
        return Hlc(self.physical_ms, self.counter, self.node_id)

    @property
    def key(self) -> OpKey:
        return op_key(self.physical_ms, self.counter, self.node_id, self.op_id)

    def to_wire(self, *, with_seq: bool = True) -> dict[str, Any]:
        body: dict[str, Any] = {
            "op_id": self.op_id,
            "workspace_id": self.workspace_id,
            "entity_type": self.entity_type,
            "entity_id": self.entity_id,
            "actor_id": self.actor_id,
            "device_id": self.device_id,
            "operation": self.operation,
            "field": self.field,
            "value": self.value,
            "hlc": self.hlc.to_wire(),
            "source": self.source,
            "source_meta": {"model": self.model, "prompt_hash": self.prompt_hash},
        }
        if with_seq:
            body["seq"] = self.seq
            body["received_at"] = self.received_at
        return body

    @classmethod
    def from_wire(cls, data: dict[str, Any]) -> Op:
        hlc = data.get("hlc") or {}
        meta = data.get("source_meta") or {}
        return cls(
            op_id=str(data["op_id"]),
            workspace_id=str(data["workspace_id"]),
            entity_type=str(data["entity_type"]),
            entity_id=str(data["entity_id"]),
            actor_id=str(data["actor_id"]),
            device_id=str(data["device_id"]),
            operation=str(data["operation"]),
            field=str(data["field"]),
            value=data.get("value"),
            physical_ms=int(hlc["physical_ms"]),
            counter=int(hlc["counter"]),
            node_id=str(hlc["node_id"]),
            source=str(data["source"]),
            model=meta.get("model"),
            prompt_hash=meta.get("prompt_hash"),
            seq=data.get("seq"),
            received_at=data.get("received_at"),
        )


@dataclass(slots=True)
class RejectedOp:
    op_id: str
    code: str
    message: str
    server_hlc: dict[str, Any] | None = field(default=None)


def is_collection_field(entity_type: str, field_name: str) -> bool:
    return field_name in _COLLECTIONS.get(entity_type, frozenset())


def is_subtask_field(field_name: str) -> bool:
    return field_name.startswith("__subtask:")


def parse_subtask_field(field_name: str) -> tuple[str, str] | None:
    """解析 `__subtask:<subtask_id>:<kind>`；非法格式返回 None。"""
    parts = field_name.split(":")
    if len(parts) != 3 or parts[0] != "__subtask":
        return None
    subtask_id, kind = parts[1], parts[2]
    if not 1 <= len(subtask_id) <= 64 or kind not in SUBTASK_KINDS:
        return None
    return subtask_id, kind


def editor_can_write(entity_type: str, field_name: str) -> bool:
    if entity_type == "task":
        return True
    if entity_type == "list":
        return field_name in _EDITOR_MUTABLE_LIST_FIELDS
    if entity_type == "tag":
        return field_name in _EDITOR_MUTABLE_TAG_FIELDS
    return False


def validate_semantics(
    op: Op,
    *,
    list_exists: Callable[[str], bool] | None = None,
) -> None:
    """校验实体形状、字段白名单、操作类型与值域；不满足时抛 OpRejected。"""
    if op.entity_type == "workspace" and op.entity_id != op.workspace_id:
        raise OpRejected("invalid_value", message="工作区实体的 entity_id 必须等于 workspace_id")
    if op.entity_type == "membership":
        prefix, _, user_part = op.entity_id.partition(":")
        if prefix != op.workspace_id or not user_part:
            raise OpRejected(
                "invalid_value", message="成员实体的 entity_id 必须是 <workspace_id>:<user_id>"
            )

    field_name = op.field
    if op.operation in {"add", "remove"}:
        if not is_collection_field(op.entity_type, field_name):
            raise OpRejected("invalid_operation")
        if not _is_id(op.value, 64):
            raise OpRejected("invalid_value", message="集合元素必须是 1–64 字符的 id")
        return

    if is_subtask_field(field_name):
        parsed = parse_subtask_field(field_name)
        if parsed is None:
            raise OpRejected("unknown_field")
        _, kind = parsed
        _check_value(_SUBTASK_RULES[kind], op.value, op)
        return

    if is_collection_field(op.entity_type, field_name):
        raise OpRejected("invalid_operation", message="集合字段只能使用 add / remove")

    rules = _SCALAR_RULES.get(op.entity_type)
    if rules is None or field_name not in rules:
        raise OpRejected("unknown_field")
    _check_value(rules[field_name], op.value, op)

    if field_name == "list_id" and list_exists is not None and not list_exists(str(op.value)):
        raise OpRejected("unknown_list")


def _check_value(kind: str, value: Any, op: Op) -> None:
    if kind == "rfc3339":
        if not _is_rfc3339(value):
            raise OpRejected("invalid_value", message="时间必须是带时区的 RFC 3339 字符串")
    elif kind == "datetime_or_null":
        if value is not None and not _is_rfc3339(value):
            raise OpRejected("invalid_value", message="时间必须是带时区的 RFC 3339 字符串或 null")
    elif kind == "bool":
        if not isinstance(value, bool):
            raise OpRejected("invalid_value", message="该字段必须是布尔值")
    elif kind == "number":
        if not _is_number(value):
            raise OpRejected("invalid_value", message="序号必须是有限数字")
    elif kind == "priority":
        if value not in {"high", "mid", "low"}:
            raise OpRejected("invalid_value", message="优先级必须是 high / mid / low")
    elif kind == "status":
        if value not in {"todo", "done"}:
            raise OpRejected("invalid_value", message="状态必须是 todo / done")
    elif kind == "role":
        if value not in {"owner", "editor", "viewer"}:
            raise OpRejected("invalid_value", message="角色必须是 owner / editor / viewer")
    elif kind == "workspace_ref":
        if not isinstance(value, str) or value != op.workspace_id:
            raise OpRejected("invalid_value", message="workspace_id 字段必须与 op 一致")
    elif kind == "list_ref":
        if not _is_id(value, 64):
            raise OpRejected("invalid_value", message="清单 id 必须是 1–64 字符")
    elif kind == "id64":
        if not _is_id(value, 64):
            raise OpRejected("invalid_value", message="id 必须是 1–64 字符")
    elif kind == "color":
        if not _is_bounded_str(value, 1, 32):
            raise OpRejected("invalid_value", message="颜色值不合法")
    elif kind == "email":
        if not isinstance(value, str) or len(value) > 254:
            raise OpRejected("invalid_value", message="邮箱长度不合法")
    elif kind == "initial":
        if not _is_bounded_str(value, 1, 2):
            raise OpRejected("invalid_value", message="首字母需为 1–2 个字符")
    elif kind == "notes":
        if not isinstance(value, str) or len(value) > 20_000:
            raise OpRejected("invalid_value", message="备注最长 20000 字符")
    elif kind == "title":
        if not _is_bounded_str(value, 1, 500, strip=True):
            raise OpRejected("invalid_value", message="任务标题需为 1–500 字符")
    elif kind == "workspace_name":
        if not _is_bounded_str(value, 1, 24, strip=True):
            raise OpRejected("invalid_value", message="工作区名称需为 1–24 个字符")
    elif kind == "name60":
        if not _is_bounded_str(value, 1, 60, strip=True):
            raise OpRejected("invalid_value", message="名称需为 1–60 个字符")
    elif kind == "name24":
        if not _is_bounded_str(value, 1, 24, strip=True):
            raise OpRejected("invalid_value", message="名称需为 1–24 个字符")
    elif kind == "subtask_text":
        if not isinstance(value, str) or len(value) > 2_000:
            raise OpRejected("invalid_value", message="子任务文本最长 2000 字符")
    else:  # pragma: no cover - 规则表与实现保持同步
        raise OpRejected("invalid_value", message=f"未支持的值域类型: {kind}")


def _is_id(value: Any, max_len: int) -> bool:
    return isinstance(value, str) and 1 <= len(value) <= max_len


def _is_bounded_str(value: Any, min_len: int, max_len: int, *, strip: bool = False) -> bool:
    if not isinstance(value, str):
        return False
    text = value.strip() if strip else value
    return min_len <= len(text) <= max_len


def _is_number(value: Any) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    return math.isfinite(value) and abs(value) <= 1_000_000_000


def _is_rfc3339(value: Any) -> bool:
    if not isinstance(value, str) or not 1 <= len(value) <= 64:
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def canonical_json(value: Any) -> str:
    """用于集合元素与审计字段的稳定序列化，与 serde_json 紧凑输出对齐。"""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
