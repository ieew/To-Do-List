"""设备 MQTT 票据：纯 HMAC 校验，零 IO。

票据 = `base64url(json).hex(hmac_sha256)`，载荷带 `user_id`/`device_id`/过期毫秒。
broker 插件里校验票据**不读库**（见 SERVER-INTEGRATION.md §2.5：插件里不许同步 IO），
吊销状态由运行时的内存集合负责。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
from dataclasses import dataclass

_KEY_CONTEXT = b"dsh-mqtt-ticket-v1"
_VERSION = 1


class TicketError(ValueError):
    """票据格式不合法（与「过期」「签名不符」区分开，方便排查）。"""


@dataclass(frozen=True, slots=True)
class TicketClaims:
    user_id: str
    device_id: str
    expires_at: int


def _signing_key(secret: str) -> bytes:
    return hmac.new(secret.encode("utf-8"), _KEY_CONTEXT, hashlib.sha256).digest()


def _b64encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64decode(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def _signature(secret: str, body: str) -> str:
    return hmac.new(_signing_key(secret), body.encode("ascii"), hashlib.sha256).hexdigest()


def mint_ticket(
    secret: str,
    *,
    user_id: str,
    device_id: str,
    ttl_seconds: int,
    now_ms: int,
) -> tuple[str, int]:
    """签发票据，返回 `(ticket, expires_at_ms)`。"""
    expires_at = now_ms + max(1, int(ttl_seconds)) * 1000
    payload = json.dumps(
        {"v": _VERSION, "u": user_id, "d": device_id, "e": expires_at},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    body = _b64encode(payload)
    return f"{body}.{_signature(secret, body)}", expires_at


def verify_ticket(
    secret: str,
    *,
    device_id: str,
    ticket: str,
    now_ms: int,
) -> TicketClaims | None:
    """校验票据；任何一项不通过都返回 None（调用方只需一种失败处理）。"""
    if not device_id or not ticket:
        return None
    body, _, signature = ticket.partition(".")
    if not body or not signature:
        return None
    if not hmac.compare_digest(signature, _signature(secret, body)):
        return None
    try:
        payload = json.loads(_b64decode(body))
    except (ValueError, TypeError):
        return None
    if not isinstance(payload, dict) or payload.get("v") != _VERSION:
        return None
    claim_device = payload.get("d")
    user_id = payload.get("u")
    expires_at = payload.get("e")
    if claim_device != device_id or not isinstance(user_id, str) or not user_id:
        return None
    if not isinstance(expires_at, int) or expires_at <= now_ms:
        return None
    return TicketClaims(user_id=user_id, device_id=device_id, expires_at=expires_at)
