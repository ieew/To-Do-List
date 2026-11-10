"""同步推送用例：整批一次事务，逐条做信封后的校验、授权与幂等处理。"""

from __future__ import annotations

import json
from typing import Any

import aiosqlite

from app.core_domain.errors import AppError, OpRejected, error_payload
from app.core_domain.op import Op, editor_can_write, validate_semantics
from app.db import ops as ops_repo
from app.features.seed.usecase import update_caches
from app.features.sync_push.port import ServicesPort as Services
from app.features.sync_push.schemas import PushRequest
from app.infra.clock import now_ms

_PERMISSION_CODES = frozenset({"workspace_access_revoked", "forbidden"})


async def push(
    services: Services, user_id: str, request: PushRequest
) -> tuple[int, dict[str, Any]]:
    settings = services.settings
    if len(request.ops) > settings.max_ops_per_push:
        raise AppError(
            "payload_too_large",
            message=f"单次最多推送 {settings.max_ops_per_push} 条 op，请分批提交",
        )
    ops = [item.to_op() for item in request.ops]
    at_ms = now_ms()

    async def txn(
        conn: aiosqlite.Connection,
    ) -> tuple[list[dict[str, Any]], int, int, dict[str, Any], int]:
        results: list[dict[str, Any]] = []
        accepted = 0
        permission_rejects = 0
        first_permission_code: str | None = None
        caches: dict[str, dict[Any, Any]] = {
            "timelines": {},
            "creator": {},
            "has_membership": {},
            "role_ops": {},
            "users": {},
        }

        for op in ops:
            existing = await ops_repo.find_op_seq(conn, op.op_id)
            if existing is not None:
                results.append({"op_id": op.op_id, "status": "duplicate", "seq": existing})
                continue
            try:
                seq = await _accept_op(conn, settings, user_id, op, at_ms, caches)
            except (OpRejected, AppError) as exc:
                payload = exc.payload()
                results.append({"op_id": op.op_id, "status": "rejected", "error": payload})
                if exc.code in _PERMISSION_CODES:
                    permission_rejects += 1
                    if first_permission_code is None:
                        first_permission_code = exc.code
            else:
                results.append({"op_id": op.op_id, "status": "accepted", "seq": seq})
                accepted += 1

        server_hlc = await ops_repo.server_hlc_snapshot(conn)
        server_seq = await ops_repo.latest_seq(conn)
        return (
            results,
            accepted,
            permission_rejects,
            first_permission_code,
            server_hlc.to_wire(),
            server_seq,
        )

    (
        results,
        accepted,
        permission_rejects,
        first_permission_code,
        server_hlc,
        server_seq,
    ) = await services.writer.submit(txn)

    if accepted == 0 and ops and permission_rejects == len(ops):
        return 403, {
            "error": error_payload(
                first_permission_code or "workspace_access_revoked",
                server_hlc=server_hlc,
            ),
            "results": results,
        }

    if accepted:
        from app.features.history import usecase as history_usecase

        for workspace_id in {op.workspace_id for op in ops}:
            history_usecase.schedule_snapshot_if_due(services, workspace_id)

    return 200, {"server_hlc": server_hlc, "server_seq": server_seq, "results": results}


async def _accept_op(
    conn: aiosqlite.Connection,
    settings: Any,
    user_id: str,
    op: Op,
    at_ms: int,
    caches: dict[str, dict[Any, Any]],
) -> int:
    if op.actor_id != user_id:
        raise OpRejected("actor_mismatch")

    if op.physical_ms > at_ms + settings.hlc_max_future_ms:
        server_hlc = await ops_repo.server_hlc_snapshot(conn)
        raise OpRejected("hlc_too_far_ahead", server_hlc=server_hlc.to_wire())

    is_workspace_creation = (
        op.entity_type == "workspace"
        and op.field == "__created"
        and op.entity_id == op.workspace_id
    )
    if not is_workspace_creation and not await _workspace_exists(conn, op.workspace_id):
        raise OpRejected("workspace_not_found")

    bound = await ops_repo.get_entity_workspace(conn, op.entity_type, op.entity_id)
    if bound is not None and bound != op.workspace_id:
        raise OpRejected("entity_workspace_mismatch")
    if bound is None and op.entity_type != "user":
        first_field_ok = (
            (op.entity_type == "workspace" and op.field == "__created")
            or (op.entity_type == "membership" and op.field == "__role")
            or (op.entity_type in {"list", "tag", "task"} and op.field == "__created")
        )
        if not first_field_ok:
            raise OpRejected("entity_not_created")

    validate_semantics(op)

    if op.entity_type == "task" and op.field == "list_id" and op.operation == "set":
        list_owner = await ops_repo.get_entity_workspace(conn, "list", str(op.value))
        if list_owner != op.workspace_id:
            raise OpRejected("unknown_list")

    bootstrap = await _is_bootstrap(conn, op, caches)
    if bootstrap:
        role = "owner"
    else:
        role = await _role_at(conn, op.workspace_id, op.actor_id, op.key, caches)
        if role is None:
            server_hlc = await ops_repo.server_hlc_snapshot(conn)
            raise OpRejected("workspace_access_revoked", server_hlc=server_hlc.to_wire())
        if not _role_allows(role, op, user_id):
            raise OpRejected("forbidden")

    target_user: str | None = None
    if op.entity_type == "membership":
        _workspace_part, _, target_user = op.entity_id.partition(":")
        if not await _user_exists(conn, target_user, caches):
            raise OpRejected("unknown_user")
    elif op.entity_type == "user":
        target_user = op.entity_id
        if not await _user_exists(conn, target_user, caches):
            raise OpRejected("unknown_user")
        if target_user != op.actor_id and role != "owner":
            raise OpRejected("forbidden")

    if op.entity_type == "membership":
        await _guard_last_owner(conn, op, target_user)

    op.received_at = at_ms
    seq = await ops_repo.insert_op(conn, op)
    op.seq = seq
    await update_caches(conn, op, at_ms)

    if op.entity_type == "membership" and target_user is not None:
        caches["timelines"].pop((op.workspace_id, target_user), None)
    caches["has_membership"].pop(op.workspace_id, None)
    caches["role_ops"].pop(op.entity_id, None)
    return seq


def _role_allows(role: str, op: Op, user_id: str) -> bool:
    if role == "owner":
        return True
    if role == "editor":
        if op.entity_type == "task":
            return True
        if op.entity_type == "list":
            return editor_can_write("list", op.field)
        if op.entity_type == "tag":
            return editor_can_write("tag", op.field)
        if op.entity_type == "user":
            return op.entity_id == user_id
        return False
    return False


async def _role_at(
    conn: aiosqlite.Connection,
    workspace_id: str,
    user_id: str,
    at_key: tuple[int, int, str, str],
    caches: dict[str, dict[Any, Any]],
) -> str | None:
    cache_key = (workspace_id, user_id)
    timeline = caches["timelines"].get(cache_key)
    if timeline is None:
        cursor = await conn.execute(
            """
            SELECT field, value_json, physical_ms, counter, node_id, op_id
            FROM op_log
            WHERE entity_type = 'membership' AND entity_id = ?
            ORDER BY physical_ms, counter, node_id, op_id
            """,
            (f"{workspace_id}:{user_id}",),
        )
        rows = await cursor.fetchall()
        await cursor.close()
        timeline = [
            (
                (row["physical_ms"], row["counter"], row["node_id"], row["op_id"]),
                row["field"],
                json.loads(row["value_json"]),
            )
            for row in rows
        ]
        caches["timelines"][cache_key] = timeline

    role: str | None = None
    role_key: tuple[int, int, str, str] | None = None
    removed = False
    removed_key: tuple[int, int, str, str] | None = None
    for key, field_name, value in timeline:
        if key > at_key:
            break
        if field_name == "__role" and isinstance(value, str):
            role = value
            role_key = key
        elif field_name == "__removed":
            removed = value is True
            removed_key = key

    if role is None:
        return None
    if removed and (removed_key is not None and (role_key is None or removed_key >= role_key)):
        return None
    return role


async def _is_bootstrap(
    conn: aiosqlite.Connection, op: Op, caches: dict[str, dict[Any, Any]]
) -> bool:
    """工作区创建者建立首个成员关系时，按顺序先写 name/membership 属于合法引导。"""
    if op.entity_type == "workspace" and op.field == "__created":
        # 工作区 id 随机且尚不存在，创建者就是 actor。
        return True

    creator = await _workspace_creator(conn, op.workspace_id, caches)
    if creator != op.actor_id:
        return False
    if await _has_membership_ops(conn, op.workspace_id, caches):
        return False
    if op.entity_type == "workspace" and op.field == "name":
        return True
    if (
        op.entity_type == "membership"
        and op.field == "__role"
        and op.value == "owner"
        and op.entity_id == f"{op.workspace_id}:{op.actor_id}"
        and not await _has_role_op(conn, op.entity_id, caches)
    ):
        return True
    return False


async def _workspace_exists(conn: aiosqlite.Connection, workspace_id: str) -> bool:
    cursor = await conn.execute("SELECT 1 FROM workspaces WHERE workspace_id = ?", (workspace_id,))
    row = await cursor.fetchone()
    await cursor.close()
    return row is not None


async def _workspace_creator(
    conn: aiosqlite.Connection,
    workspace_id: str,
    caches: dict[str, dict[Any, Any]],
) -> str | None:
    if workspace_id in caches["creator"]:
        return caches["creator"][workspace_id]
    cursor = await conn.execute(
        """
        SELECT actor_id FROM op_log
        WHERE entity_type = 'workspace' AND entity_id = ? AND field = '__created'
        ORDER BY seq LIMIT 1
        """,
        (workspace_id,),
    )
    row = await cursor.fetchone()
    await cursor.close()
    creator = str(row[0]) if row else None
    caches["creator"][workspace_id] = creator
    return creator


async def _has_membership_ops(
    conn: aiosqlite.Connection,
    workspace_id: str,
    caches: dict[str, dict[Any, Any]],
) -> bool:
    if workspace_id in caches["has_membership"]:
        return caches["has_membership"][workspace_id]
    cursor = await conn.execute(
        "SELECT 1 FROM op_log WHERE workspace_id = ? AND entity_type = 'membership' LIMIT 1",
        (workspace_id,),
    )
    row = await cursor.fetchone()
    await cursor.close()
    value = row is not None
    caches["has_membership"][workspace_id] = value
    return value


async def _has_role_op(
    conn: aiosqlite.Connection,
    entity_id: str,
    caches: dict[str, dict[Any, Any]],
) -> bool:
    if entity_id in caches["role_ops"]:
        return caches["role_ops"][entity_id]
    cursor = await conn.execute(
        """
        SELECT 1 FROM op_log
        WHERE entity_type = 'membership' AND entity_id = ? AND field = '__role'
        LIMIT 1
        """,
        (entity_id,),
    )
    row = await cursor.fetchone()
    await cursor.close()
    value = row is not None
    caches["role_ops"][entity_id] = value
    return value


async def _user_exists(
    conn: aiosqlite.Connection,
    user_id: str,
    caches: dict[str, dict[Any, Any]],
) -> bool:
    if user_id in caches["users"]:
        return caches["users"][user_id]
    cursor = await conn.execute("SELECT 1 FROM users WHERE user_id = ?", (user_id,))
    row = await cursor.fetchone()
    await cursor.close()
    value = row is not None
    caches["users"][user_id] = value
    return value


async def _guard_last_owner(conn: aiosqlite.Connection, op: Op, target_user: str | None) -> None:
    if target_user is None or op.field not in {"__role", "__removed"}:
        return
    cursor = await conn.execute(
        "SELECT role, removed FROM memberships WHERE workspace_id = ? AND user_id = ?",
        (op.workspace_id, target_user),
    )
    row = await cursor.fetchone()
    await cursor.close()
    if row is None or row["removed"] or row["role"] != "owner":
        return

    still_owner = (op.field == "__role" and op.value == "owner") or (
        op.field == "__removed" and op.value is False
    )
    if still_owner:
        return

    cursor = await conn.execute(
        "SELECT COUNT(*) FROM memberships "
        "WHERE workspace_id = ? AND role = 'owner' AND removed = 0",
        (op.workspace_id,),
    )
    row = await cursor.fetchone()
    await cursor.close()
    owners = int(row[0]) if row else 0
    if owners <= 1:
        raise OpRejected("last_owner")
