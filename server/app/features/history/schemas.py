"""历史切片的请求模型。"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SnapshotRequest(_StrictModel):
    workspace_id: str
    seq: int | None = None


class RestoreRequest(_StrictModel):
    workspace_id: str
    snapshot_id: str
    dry_run: bool = False
