"""dsh 推送通道的请求模型。"""

from __future__ import annotations

import re
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator

_KIND_RE = re.compile(r"^[a-z][a-z0-9_.-]{0,31}$")
_MSG_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")
_DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RegisterDeviceRequest(_StrictModel):
    name: str = ""
    platform: str = ""
    device_id: str | None = None

    @field_validator("device_id")
    @classmethod
    def _check_device_id(cls, value: str | None) -> str | None:
        if value is not None and not _DEVICE_ID_RE.match(value):
            raise ValueError("device_id 只能是 1–64 位字母、数字或 ._:-")
        return value


class PushRequest(_StrictModel):
    kind: str
    payload: Any = None
    device_id: str | None = None
    user_id: str | None = None
    ttl_s: int | None = Field(default=None, ge=1, le=30 * 24 * 3600)
    msg_id: str | None = None

    @field_validator("kind")
    @classmethod
    def _check_kind(cls, value: str) -> str:
        if not _KIND_RE.match(value):
            raise ValueError("kind 只能是 1–32 位小写字母、数字或 _.+-")
        return value

    @field_validator("msg_id")
    @classmethod
    def _check_msg_id(cls, value: str | None) -> str | None:
        if value is not None and not _MSG_ID_RE.match(value):
            raise ValueError("msg_id 只能是 1–64 位字母、数字或 ._:+-")
        return value

    @field_validator("device_id")
    @classmethod
    def _check_device_id(cls, value: str | None) -> str | None:
        if value is not None and not _DEVICE_ID_RE.match(value):
            raise ValueError("device_id 只能是 1–64 位字母、数字或 ._:-")
        return value


class AckRequest(_StrictModel):
    seqs: list[int] = Field(min_length=1, max_length=500)
    device_id: str | None = None

    @field_validator("seqs")
    @classmethod
    def _check_seqs(cls, value: list[int]) -> list[int]:
        if any(seq <= 0 for seq in value):
            raise ValueError("seq 必须是正整数")
        return value
