"""成员管理：列表、邀请、改角色、移除。

写操作本质是服务端代为生成 membership / user 的 op，与客户端推送走同一 op_log 管道。
"""

from __future__ import annotations

from typing import Any

import aiosqlite

from app.core_domain.errors import AppError
from app.db import ops as ops_repo
from app.features.seed.usecase import append_server_op, color_for, initial_of
from app.features.workspace_member.port import ServicesPort as Services
from app.features.workspace_member.schemas import InviteRequest, RoleRequest
from app.infra.clock import now_ms

_ROLE_RANK = {"owner": 0, "editor": 1}


async def list_members(services: Services, user_id: str, workspace_id: str) -> dict:
    async with services.read.acquire() as conn:
        await require_role(conn, workspace_id, user_id, None)
        rows = await ops_repo.load_ops(conn, workspace_id=workspace_id)
        projection = _project(rows, workspace_id, user_id)
        members = projection["members"]
        usernames = await _usernames(conn, [member["user_id"] for member in members])

    enriched = [
        {
            "user_id": member["user_id"],
            "username": usernames.get(member["user_id"], ""),
            "display_name": member["name"],
            "name": member["name"],
            "initial": member["initial"],
            "color": member["color"],
            "email": member["email"],
            "role": member["role"],
            "joined_at": member["joined_at"],
        }
        for member in members
    ]
    enriched.sort(
        key=lambda item: (
            _ROLE_RANK.get(item["role"], 2),
            item["joined_at"],
            item["user_id"],
        )
    )
    return {"workspace_id": workspace_id, "members": enriched}


def _project(rows: list[Any], workspace_id: str, user_id: str) -> dict[str, Any]:
    from app.core_domain.projection import build_projection

    return build_projection(rows, workspace_id, user_id)


async def _usernames(conn: aiosqlite.Connection, user_ids: list[str]) -> dict[str, str]:
    if not user_ids:
        return {}
    placeholders = ",".join("?" for _ in user_ids)
    # 占位符数量由 id 列表长度决定，值全部走参数绑定。
    cursor = await conn.execute(
        f"SELECT user_id, username FROM users WHERE user_id IN ({placeholders})",  # noqa: S608
        tuple(user_ids),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return {row["user_id"]: row["username"] for row in rows}


async def require_role(
    conn: aiosqlite.Connection,
    workspace_id: str,
    user_id: str,
    roles: set[str] | None,
) -> str:
    cursor = await conn.execute("SELECT 1 FROM workspaces WHERE workspace_id = ?", (workspace_id,))
    exists = await cursor.fetchone()
    await cursor.close()
    if exists is None:
        raise AppError("workspace_not_found")

    cursor = await conn.execute(
        "SELECT role, removed FROM memberships WHERE workspace_id = ? AND user_id = ?",
        (workspace_id, user_id),
    )
    row = await cursor.fetchone()
    await cursor.close()
    if row is None or row["removed"]:
        raise AppError("workspace_access_revoked")
    role = str(row["role"])
    if roles is not None and role not in roles:
        raise AppError("forbidden")
    return role


async def invite(services: Services, user_id: str, payload: InviteRequest) -> dict:
    at_ms = now_ms()

    async def txn(conn: aiosqlite.Connection) -> dict:
        await require_role(conn, payload.workspace_id, user_id, {"owner"})
        account = payload.account.strip()
        if not account:
            raise AppError("invalid_request", message="请填写用户名或邮箱")

        cursor = await conn.execute(
            "SELECT * FROM users WHERE username = ? OR email = ? LIMIT 1",
            (account, account),
        )
        target = await cursor.fetchone()
        await cursor.close()
        if target is None:
            raise AppError("account_not_found")
        target_id = str(target["user_id"])

        cursor = await conn.execute(
            "SELECT role, removed FROM memberships WHERE workspace_id = ? AND user_id = ?",
            (payload.workspace_id, target_id),
        )
        membership = await cursor.fetchone()
        await cursor.close()

        is_active_member = membership is not None and not membership["removed"]
        if not is_active_member:
            display_name = str(target["display_name"])
            for field_name, value in (
                ("name", display_name),
                ("initial", initial_of(display_name)),
                ("color", color_for(display_name)),
                ("email", target["email"] or ""),
            ):
                await append_server_op(
                    conn,
                    workspace_id=payload.workspace_id,
                    entity_type="user",
                    entity_id=target_id,
                    operation="set",
                    field_name=field_name,
                    value=value,
                    actor_id=user_id,
                    now_ms=at_ms,
                    source="human",
                )

        if not is_active_member or membership["role"] != payload.role:
            await append_server_op(
                conn,
                workspace_id=payload.workspace_id,
                entity_type="membership",
                entity_id=f"{payload.workspace_id}:{target_id}",
                operation="set",
                field_name="__role",
                value=payload.role,
                actor_id=user_id,
                now_ms=at_ms,
                source="human",
            )
        return {"user_id": target_id, "role": payload.role}

    return await services.writer.submit(txn)


async def set_role(services: Services, user_id: str, payload: RoleRequest) -> dict:
    at_ms = now_ms()

    async def txn(conn: aiosqlite.Connection) -> dict:
        await require_role(conn, payload.workspace_id, user_id, {"owner"})
        current = await _membership(conn, payload.workspace_id, payload.user_id)
        if current is None or current["removed"]:
            raise AppError("member_not_found")
        if current["role"] == "owner" and payload.role != "owner":
            await _guard_last_owner(conn, payload.workspace_id)
        await append_server_op(
            conn,
            workspace_id=payload.workspace_id,
            entity_type="membership",
            entity_id=f"{payload.workspace_id}:{payload.user_id}",
            operation="set",
            field_name="__role",
            value=payload.role,
            actor_id=user_id,
            now_ms=at_ms,
            source="human",
        )
        return {"user_id": payload.user_id, "role": payload.role}

    return await services.writer.submit(txn)


async def remove(services: Services, user_id: str, workspace_id: str, target_id: str) -> None:
    at_ms = now_ms()

    async def txn(conn: aiosqlite.Connection) -> None:
        await require_role(conn, workspace_id, user_id, {"owner"})
        if target_id == user_id:
            raise AppError("cannot_remove_self")
        current = await _membership(conn, workspace_id, target_id)
        if current is None or current["removed"]:
            raise AppError("member_not_found")
        if current["role"] == "owner":
            await _guard_last_owner(conn, workspace_id)
        await append_server_op(
            conn,
            workspace_id=workspace_id,
            entity_type="membership",
            entity_id=f"{workspace_id}:{target_id}",
            operation="set",
            field_name="__removed",
            value=True,
            actor_id=user_id,
            now_ms=at_ms,
            source="human",
        )

    await services.writer.submit(txn)


async def _membership(
    conn: aiosqlite.Connection, workspace_id: str, user_id: str
) -> aiosqlite.Row | None:
    cursor = await conn.execute(
        "SELECT role, removed FROM memberships WHERE workspace_id = ? AND user_id = ?",
        (workspace_id, user_id),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return row


async def _guard_last_owner(conn: aiosqlite.Connection, workspace_id: str) -> None:
    cursor = await conn.execute(
        "SELECT COUNT(*) FROM memberships "
        "WHERE workspace_id = ? AND role = 'owner' AND removed = 0",
        (workspace_id,),
    )
    row = await cursor.fetchone()
    await cursor.close()
    owners = int(row[0]) if row else 0
    if owners <= 1:
        raise AppError("last_owner")
