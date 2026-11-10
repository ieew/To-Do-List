"""鉴权切片的请求模型。"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RegisterRequest(_StrictModel):
    username: str
    password: str
    email: str | None = None
    display_name: str | None = None
    device_id: str | None = None
    workspace_name: str | None = None


class LoginRequest(_StrictModel):
    username: str
    password: str
    device_id: str | None = None


class RefreshRequest(_StrictModel):
    refresh_token: str
    device_id: str | None = None


class LogoutRequest(_StrictModel):
    refresh_token: str


class TokenPair(_StrictModel):
    token_type: Literal["Bearer"] = "Bearer"  # noqa: S105 - 协议字段值，不是密钥
    access_token: str
    access_expires_at: int
    refresh_token: str
    refresh_expires_at: int


class UserOut(_StrictModel):
    user_id: str
    username: str
    display_name: str
    email: str | None
    created_at: str


class WorkspaceSummaryOut(_StrictModel):
    workspace_id: str
    name: str
    role: str
    member_count: int
    created_at: str


class RegisterResponse(_StrictModel):
    user: UserOut
    default_workspace_id: str
    tokens: TokenPair


class LoginResponse(_StrictModel):
    user: UserOut
    workspaces: list[WorkspaceSummaryOut]
    tokens: TokenPair


class RefreshResponse(_StrictModel):
    tokens: TokenPair


class MeResponse(_StrictModel):
    user: UserOut


class WorkspaceListResponse(_StrictModel):
    workspaces: list[WorkspaceSummaryOut]


def as_dict(model: BaseModel) -> dict[str, Any]:
    return model.model_dump()
