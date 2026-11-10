"""鉴权用例：注册（含种子数据）、登录、刷新、登出、当前用户。"""

from __future__ import annotations

import asyncio
import re
from datetime import datetime
from typing import Any

import aiosqlite

from app.core_domain.errors import AppError
from app.features.auth.port import ServicesPort as Services
from app.features.auth.schemas import LoginRequest, RefreshRequest, RegisterRequest
from app.features.seed.usecase import seed_new_workspace
from app.infra.clock import now_ms
from app.infra.security import (
    create_access_token,
    hash_password,
    new_id,
    new_opaque_token,
    sha256_hex,
    verify_password,
)

USERNAME_RE = re.compile(r"^[A-Za-z0-9_-]{3,32}$")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def _iso_from_ms(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")


def _user_out(row: aiosqlite.Row) -> dict[str, Any]:
    return {
        "user_id": row["user_id"],
        "username": row["username"],
        "display_name": row["display_name"],
        "email": row["email"],
        "created_at": _iso_from_ms(int(row["created_at"])),
    }


async def _fetch_user_by_username(
    conn: aiosqlite.Connection, username: str
) -> aiosqlite.Row | None:
    cursor = await conn.execute("SELECT * FROM users WHERE username = ?", (username,))
    row = await cursor.fetchone()
    await cursor.close()
    return row


async def _fetch_user_by_email(conn: aiosqlite.Connection, email: str) -> aiosqlite.Row | None:
    cursor = await conn.execute("SELECT * FROM users WHERE email = ?", (email,))
    row = await cursor.fetchone()
    await cursor.close()
    return row


async def fetch_user(conn: aiosqlite.Connection, user_id: str) -> aiosqlite.Row | None:
    cursor = await conn.execute("SELECT * FROM users WHERE user_id = ?", (user_id,))
    row = await cursor.fetchone()
    await cursor.close()
    return row


async def _unique_id(conn: aiosqlite.Connection, table: str, column: str, prefix: str) -> str:
    for _ in range(20):
        candidate = new_id(prefix)
        # table / column 只来自调用方常量，不拼用户输入。
        cursor = await conn.execute(
            f"SELECT 1 FROM {table} WHERE {column} = ?",  # noqa: S608
            (candidate,),
        )
        row = await cursor.fetchone()
        await cursor.close()
        if row is None:
            return candidate
    raise AppError("internal_error", message="生成唯一 id 失败，请重试")


async def _issue_tokens(
    conn: aiosqlite.Connection,
    services: Services,
    *,
    user_id: str,
    device_id: str | None,
    at_ms: int,
    family_id: str | None = None,
) -> tuple[str, dict[str, Any]]:
    settings = services.settings
    raw_refresh = new_opaque_token()
    token_id = new_id("rt")
    family = family_id or new_id("rtf")
    access_token, access_expires_at = create_access_token(
        settings.jwt_secret,
        user_id,
        ttl_minutes=settings.access_ttl_minutes,
        now_ms=at_ms,
    )
    refresh_expires_at = at_ms + settings.refresh_ttl_days * 86_400_000
    await conn.execute(
        """
        INSERT INTO refresh_tokens (
            token_id, family_id, user_id, device_id, token_hash,
            issued_at, expires_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (
            token_id,
            family,
            user_id,
            device_id,
            sha256_hex(raw_refresh),
            at_ms,
            refresh_expires_at,
        ),
    )
    return token_id, {
        "token_type": "Bearer",
        "access_token": access_token,
        "access_expires_at": access_expires_at,
        "refresh_token": raw_refresh,
        "refresh_expires_at": refresh_expires_at,
    }


async def list_workspaces(conn: aiosqlite.Connection, user_id: str) -> list[dict[str, Any]]:
    cursor = await conn.execute(
        """
        SELECT w.workspace_id, w.name, w.created_at, m.role,
               (SELECT COUNT(*) FROM memberships m2
                WHERE m2.workspace_id = w.workspace_id AND m2.removed = 0) AS member_count
        FROM memberships m
        JOIN workspaces w ON w.workspace_id = m.workspace_id
        WHERE m.user_id = ? AND m.removed = 0 AND w.deleted = 0
        ORDER BY w.name, w.workspace_id
        """,
        (user_id,),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return [
        {
            "workspace_id": row["workspace_id"],
            "name": row["name"] or "未命名空间",
            "role": row["role"],
            "member_count": int(row["member_count"]),
            "created_at": _iso_from_ms(int(row["created_at"])),
        }
        for row in rows
    ]


def _clean_register(payload: RegisterRequest, services: Services) -> dict[str, Any]:
    username = payload.username.strip()
    if not USERNAME_RE.fullmatch(username):
        raise AppError("invalid_username")
    if not 8 <= len(payload.password) <= 128:
        raise AppError("weak_password")

    email = (payload.email or "").strip() or None
    if email is not None and not EMAIL_RE.fullmatch(email):
        raise AppError("invalid_email")

    display_name = (payload.display_name or "").strip() or username
    if not 1 <= len(display_name) <= 24:
        raise AppError("invalid_value", message="显示名称需为 1–24 个字符")

    workspace_name = (
        payload.workspace_name or ""
    ).strip() or services.settings.default_workspace_name
    if not 1 <= len(workspace_name) <= 24:
        raise AppError("invalid_value", message="工作区名称需为 1–24 个字符")

    device_id = (payload.device_id or "").strip() or None
    if device_id is not None and len(device_id) > 64:
        raise AppError("invalid_value", message="device_id 最长 64 字符")

    return {
        "username": username,
        "email": email,
        "display_name": display_name,
        "workspace_name": workspace_name,
        "device_id": device_id,
        "password": payload.password,
    }


async def register(services: Services, payload: RegisterRequest) -> dict[str, Any]:
    clean = _clean_register(payload, services)
    password_hash = await asyncio.to_thread(hash_password, clean["password"])
    at_ms = now_ms()

    async def txn(conn: aiosqlite.Connection) -> dict[str, Any]:
        if await _fetch_user_by_username(conn, clean["username"]) is not None:
            raise AppError("username_taken")
        if clean["email"] and await _fetch_user_by_email(conn, clean["email"]) is not None:
            raise AppError("email_taken")

        user_id = await _unique_id(conn, "users", "user_id", "u")
        workspace_id = await _unique_id(conn, "workspaces", "workspace_id", "ws")
        await conn.execute(
            """
            INSERT INTO users (user_id, username, email, display_name, password_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                user_id,
                clean["username"],
                clean["email"],
                clean["display_name"],
                password_hash,
                at_ms,
            ),
        )
        await seed_new_workspace(
            conn,
            workspace_id=workspace_id,
            user_id=user_id,
            display_name=clean["display_name"],
            email=clean["email"],
            workspace_name=clean["workspace_name"],
            now_ms=at_ms,
        )
        _, tokens = await _issue_tokens(
            conn, services, user_id=user_id, device_id=clean["device_id"], at_ms=at_ms
        )
        row = await fetch_user(conn, user_id)
        assert row is not None
        return {
            "user": _user_out(row),
            "default_workspace_id": workspace_id,
            "tokens": tokens,
        }

    return await services.writer.submit(txn)


async def login(services: Services, payload: LoginRequest) -> dict[str, Any]:
    username = payload.username.strip()
    async with services.read.acquire() as conn:
        row = await _fetch_user_by_username(conn, username)
    if row is None:
        raise AppError("invalid_credentials")
    verified = await asyncio.to_thread(verify_password, payload.password, row["password_hash"])
    if not verified:
        raise AppError("invalid_credentials")

    at_ms = now_ms()
    device_id = (payload.device_id or "").strip() or None

    async def txn(conn: aiosqlite.Connection) -> dict[str, Any]:
        _, tokens = await _issue_tokens(
            conn, services, user_id=row["user_id"], device_id=device_id, at_ms=at_ms
        )
        return tokens

    tokens = await services.writer.submit(txn)
    async with services.read.acquire() as conn:
        workspaces = await list_workspaces(conn, row["user_id"])
    return {"user": _user_out(row), "workspaces": workspaces, "tokens": tokens}


async def refresh(services: Services, payload: RefreshRequest) -> dict[str, Any]:
    at_ms = now_ms()
    token_hash = sha256_hex(payload.refresh_token)
    device_id = (payload.device_id or "").strip() or None

    async def txn(conn: aiosqlite.Connection) -> dict[str, Any] | None:
        cursor = await conn.execute(
            "SELECT * FROM refresh_tokens WHERE token_hash = ?", (token_hash,)
        )
        row = await cursor.fetchone()
        await cursor.close()
        if row is None:
            return None
        if row["used_at"] is not None or row["revoked_at"] is not None:
            # 家族复用视为泄露：吊销整族后返回失败。
            await conn.execute(
                """
                UPDATE refresh_tokens SET revoked_at = ?
                WHERE family_id = ? AND revoked_at IS NULL
                """,
                (at_ms, row["family_id"]),
            )
            return None
        if int(row["expires_at"]) <= at_ms:
            return None
        if device_id and row["device_id"] and device_id != row["device_id"]:
            return None

        new_token_id, tokens = await _issue_tokens(
            conn,
            services,
            user_id=row["user_id"],
            device_id=row["device_id"],
            at_ms=at_ms,
            family_id=row["family_id"],
        )
        await conn.execute(
            "UPDATE refresh_tokens SET used_at = ?, revoked_at = ?, replaced_by = ? "
            "WHERE token_id = ?",
            (at_ms, at_ms, new_token_id, row["token_id"]),
        )
        return tokens

    tokens = await services.writer.submit(txn)
    if tokens is None:
        raise AppError("invalid_refresh_token")
    return {"tokens": tokens}


async def logout(services: Services, payload: RefreshRequest) -> None:
    at_ms = now_ms()
    token_hash = sha256_hex(payload.refresh_token)

    async def txn(conn: aiosqlite.Connection) -> None:
        await conn.execute(
            """
            UPDATE refresh_tokens SET revoked_at = ?
            WHERE token_hash = ? AND revoked_at IS NULL
            """,
            (at_ms, token_hash),
        )

    await services.writer.submit(txn)


async def me(services: Services, user_id: str) -> dict[str, Any]:
    async with services.read.acquire() as conn:
        row = await fetch_user(conn, user_id)
    if row is None:
        raise AppError("unauthorized")
    return {"user": _user_out(row)}


async def workspaces(services: Services, user_id: str) -> dict[str, Any]:
    async with services.read.acquire() as conn:
        items = await list_workspaces(conn, user_id)
    return {"workspaces": items}
