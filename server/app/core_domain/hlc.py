"""混合逻辑时钟与全序比较。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

OpKey = tuple[int, int, str, str]


@dataclass(frozen=True, order=True, slots=True)
class Hlc:
    """推导顺序固定为 (physical_ms, counter, node_id)。"""

    physical_ms: int
    counter: int
    node_id: str

    def to_wire(self) -> dict[str, Any]:
        return {
            "physical_ms": self.physical_ms,
            "counter": self.counter,
            "node_id": self.node_id,
        }

    @classmethod
    def from_mapping(cls, data: Any) -> Hlc:
        return cls(
            physical_ms=int(data["physical_ms"]),
            counter=int(data["counter"]),
            node_id=str(data["node_id"]),
        )


def op_key(physical_ms: int, counter: int, node_id: str, op_id: str) -> OpKey:
    return (physical_ms, counter, node_id, op_id)


def next_hlc(
    *,
    prev_physical_ms: int,
    prev_counter: int,
    now_ms: int,
    node_id: str,
    floor: tuple[int, int] | None = None,
) -> Hlc:
    """推进 HLC：物理分量取 max(now, prev, floor)，计数在相同物理毫秒上递增。"""
    physical = max(now_ms, prev_physical_ms, floor[0] if floor else 0)
    if physical == prev_physical_ms:
        counter = prev_counter + 1
    else:
        counter = 0
    if floor is not None and (physical, counter) <= floor:
        physical = floor[0]
        counter = floor[1] + 1
    return Hlc(physical_ms=physical, counter=counter, node_id=node_id)
