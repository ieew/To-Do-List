"""确定性 reducer：字段级 LWW + 集合 OR-Set（add-wins）。

与 `windows/src-tauri/src/core_domain.rs` 的 `State::apply` 逐条对齐；
不接受数据库/网络/时钟依赖，op 顺序由 `(physical_ms, counter, node_id, op_id)` 全序决定。
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any

from app.core_domain.hlc import OpKey
from app.core_domain.op import Op, canonical_json

FieldKey = tuple[str, str, str]
EntityKey = tuple[str, str]


@dataclass(slots=True)
class _Cell:
    value: Any
    key: OpKey
    op_id: str


@dataclass(slots=True)
class _Element:
    add: OpKey | None = None
    remove: OpKey | None = None

    def alive(self) -> bool:
        if self.add is None:
            return False
        if self.remove is None:
            return True
        return self.add >= self.remove


@dataclass(slots=True)
class EntityMeta:
    first_ms: int
    source: str
    model: str | None


def field_key(entity_type: str, entity_id: str, field_name: str) -> FieldKey:
    return (entity_type, entity_id, field_name)


class State:
    """把 op 序列折叠为「字段单元格 + 集合元素 + 实体元信息」。"""

    def __init__(self) -> None:
        self.fields: dict[FieldKey, _Cell] = {}
        self.collections: dict[FieldKey, dict[str, _Element]] = {}
        self.meta: dict[EntityKey, EntityMeta] = {}
        self.order: list[Op] = []

    def apply_all(self, ops: Iterable[Op]) -> None:
        for op in sorted(ops, key=lambda item: item.key):
            self.apply(op)

    def apply(self, op: Op) -> None:
        key = field_key(op.entity_type, op.entity_id, op.field)
        op_key = op.key

        entity = (op.entity_type, op.entity_id)
        existing_meta = self.meta.get(entity)
        if existing_meta is None:
            self.meta[entity] = EntityMeta(
                first_ms=op.physical_ms, source=op.source, model=op.model
            )
        elif op.physical_ms < existing_meta.first_ms:
            existing_meta.first_ms = op.physical_ms

        if op.field == "__created":
            meta = self.meta.get(entity)
            if meta is not None:
                meta.source = op.source
                meta.model = op.model

        if op.operation in {"add", "remove"}:
            element = op.value if isinstance(op.value, str) else canonical_json(op.value)
            bucket = self.collections.setdefault(key, {})
            state = bucket.setdefault(element, _Element())
            if op.operation == "add":
                if state.add is None or op_key > state.add:
                    state.add = op_key
            elif state.remove is None or op_key > state.remove:
                state.remove = op_key
        else:
            current = self.fields.get(key)
            if current is None or op_key > current.key:
                self.fields[key] = _Cell(value=op.value, key=op_key, op_id=op.op_id)

        self.order.append(op)

    # ---- 读取辅助 ----

    def raw(self, entity_type: str, entity_id: str, field_name: str) -> Any:
        cell = self.fields.get(field_key(entity_type, entity_id, field_name))
        return None if cell is None else cell.value

    def text(self, entity_type: str, entity_id: str, field_name: str) -> str | None:
        value = self.raw(entity_type, entity_id, field_name)
        if value is None:
            return None
        if isinstance(value, str):
            return value
        return canonical_json(value)

    def flag(self, entity_type: str, entity_id: str, field_name: str) -> bool:
        value = self.raw(entity_type, entity_id, field_name)
        return value is True

    def number(self, entity_type: str, entity_id: str, field_name: str, fallback: float) -> float:
        value = self.raw(entity_type, entity_id, field_name)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return fallback
        return float(value)

    def deleted(self, entity_type: str, entity_id: str) -> bool:
        return self.flag(entity_type, entity_id, "__deleted")

    def elements(self, entity_type: str, entity_id: str, field_name: str) -> list[str]:
        bucket = self.collections.get(field_key(entity_type, entity_id, field_name), {})
        return sorted(element for element, state in bucket.items() if state.alive())

    def meta_for(self, entity_type: str, entity_id: str) -> EntityMeta | None:
        return self.meta.get((entity_type, entity_id))

    def field_physical_ms(self, entity_type: str, entity_id: str, field_name: str) -> int | None:
        cell = self.fields.get(field_key(entity_type, entity_id, field_name))
        return None if cell is None else cell.key[0]

    def entities(self, entity_type: str) -> list[str]:
        return [entity_id for (current_type, entity_id) in self.meta if current_type == entity_type]
