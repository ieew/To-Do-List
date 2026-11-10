"""口令哈希、JWT 与刷新令牌工具。"""

from __future__ import annotations

import hashlib
import hmac
import secrets
from typing import Any

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from app.core_domain.errors import AppError

_hasher = PasswordHasher(time_cost=3, memory_cost=65_536, parallelism=4)


def hash_password(raw: str) -> str:
    return _hasher.hash(raw)


def verify_password(raw: str, stored: str) -> bool:
    try:
        return _hasher.verify(stored, raw)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def new_opaque_token() -> str:
    return secrets.token_urlsafe(32)


def new_id(prefix: str, size: int = 8) -> str:
    """生成 `<prefix>-<hex>` 形式的短 id，与客户端 id 风格一致。"""
    return f"{prefix}-{secrets.token_hex(max(1, size // 2))}"


def sha256_hex(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def constant_time_equals(left: str, right: str) -> bool:
    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))


def create_access_token(
    secret: str, user_id: str, *, ttl_minutes: int, now_ms: int
) -> tuple[str, int]:
    expires_at = now_ms + ttl_minutes * 60 * 1000
    payload: dict[str, Any] = {
        "sub": user_id,
        "typ": "access",
        "iat": now_ms // 1000,
        "exp": expires_at // 1000,
        "jti": secrets.token_hex(8),
    }
    token = jwt.encode(payload, secret, algorithm="HS256")
    return token, expires_at


def decode_access_token(secret: str, token: str) -> str:
    try:
        payload = jwt.decode(
            token,
            secret,
            algorithms=["HS256"],
            options={"require": ["exp", "sub"]},
        )
    except jwt.ExpiredSignatureError as exc:
        raise AppError("token_expired") from exc
    except jwt.InvalidTokenError as exc:
        raise AppError("invalid_access_token") from exc
    if payload.get("typ") != "access":
        raise AppError("invalid_access_token")
    subject = payload.get("sub")
    if not isinstance(subject, str) or not subject:
        raise AppError("invalid_access_token")
    return subject
