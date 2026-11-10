"""工作区成员切片的请求模型。"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class InviteRequest(_StrictModel):
    workspace_id: str
    account: str
    role: Literal["owner", "editor", "viewer"]


class RoleRequest(_StrictModel):
    workspace_id: str
    user_id: str
    role: Literal["owner", "editor", "viewer"]


class RemoveRequest(_StrictModel):
    workspace_id: str
    user_id: str
