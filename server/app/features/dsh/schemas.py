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
    # 通知等级 0–7：只决定客户端提醒方式（见 docs/server-protocol.md §15.5），
    # 与服务端投递可靠性无关；给了就合并进 payload.level。
    level: int | None = Field(default=None, ge=0, le=7)

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


class ClipRequest(_StrictModel):
    """剪贴板上行：文本 + 指纹。默认不落库，只做实时转发。"""

    text: str = Field(min_length=1, max_length=32768)
    fp: str = Field(default="", max_length=64)
    device_id: str | None = None
    msg_id: str | None = None

    @field_validator("msg_id")
    @classmethod
    def _check_msg_id(cls, value: str | None) -> str | None:
        if value is not None and not _MSG_ID_RE.match(value):
            raise ValueError("msg_id 只能是 1–64 位字母、数字或 ._:+-")
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
