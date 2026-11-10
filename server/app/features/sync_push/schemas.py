"""同步推送切片的请求模型与信封校验。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from app.core_domain.op import Op

EntityType = Literal["workspace", "list", "tag", "task", "membership", "user"]
OperationType = Literal["set", "add", "remove"]
SourceType = Literal["human", "ai", "import", "system"]


class HlcIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    physical_ms: int = Field(ge=0)
    counter: int = Field(ge=0)
    node_id: str = Field(min_length=1, max_length=64)


class SourceMetaIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str | None = Field(default=None, max_length=128)
    prompt_hash: str | None = Field(default=None, max_length=128)


class OpIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    op_id: str = Field(min_length=1, max_length=64)
    workspace_id: str = Field(min_length=1, max_length=64)
    entity_type: EntityType
    entity_id: str = Field(min_length=1, max_length=128)
    actor_id: str = Field(min_length=1, max_length=64)
    device_id: str = Field(min_length=1, max_length=64)
    operation: OperationType
    field: str = Field(min_length=1, max_length=128)
    value: Any
    hlc: HlcIn
    source: SourceType
    source_meta: SourceMetaIn | None = None

    def to_op(self) -> Op:
        return Op(
            op_id=self.op_id,
            workspace_id=self.workspace_id,
            entity_type=self.entity_type,
            entity_id=self.entity_id,
            actor_id=self.actor_id,
            device_id=self.device_id,
            operation=self.operation,
            field=self.field,
            value=self.value,
            physical_ms=self.hlc.physical_ms,
            counter=self.hlc.counter,
            node_id=self.hlc.node_id,
            source=self.source,
            model=self.source_meta.model if self.source_meta else None,
            prompt_hash=self.source_meta.prompt_hash if self.source_meta else None,
        )


class PushRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    device_id: str = Field(min_length=1, max_length=64)
    ops: list[OpIn] = Field(min_length=1)


class PushResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    server_hlc: dict[str, Any]
    server_seq: int
    results: list[dict[str, Any]]
