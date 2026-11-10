"""历史：时间线、快照与恢复到快照（只追加反向 op）。"""

from __future__ import annotations

import asyncio
import json
from typing import Any

import aiosqlite

from app.core_domain.errors import AppError
from app.core_domain.op import Op, validate_semantics
from app.core_domain.projection import build_projection
from app.core_domain.timeline import RestoreChange, build_timeline, diff_projections
from app.db import ops as ops_repo
from app.features.history.port import ServicesPort as Services
from app.features.seed.usecase import append_server_op
from app.features.workspace_member.usecase import require_role
from app.infra.clock import now_ms
from app.infra.security import new_id

_EDIT_ROLES = {"owner", "editor"}


async def timeline(
    services: Services,
    user_id: str,
    *,
    workspace_id: str,
    task_id: str | None,
    limit: int,
    before_seq: int | None,
) -> dict:
    async with services.read.acquire() as conn:
        await require_role(conn, workspace_id, user_id, None)
        rows = await ops_repo.load_ops(conn, workspace_id=workspace_id)
    return build_timeline(
        rows,
        workspace_id=workspace_id,
        current_user_id=user_id,
        task_id=task_id,
        limit=limit,
        before_seq=before_seq,
        now_ms=now_ms(),
    )


async def create_snapshot(
    services: Services, user_id: str, workspace_id: str, seq: int | None
) -> dict:
    at_ms = now_ms()

    async def txn(conn: aiosqlite.Connection) -> dict:
        await require_role(conn, workspace_id, user_id, None)
        return await create_snapshot_tx(conn, workspace_id=workspace_id, seq=seq, at_ms=at_ms)

    return await services.writer.submit(txn)


async def create_snapshot_tx(
    conn: aiosqlite.Connection,
    *,
    workspace_id: str,
    seq: int | None,
    at_ms: int,
) -> dict:
    target_seq = seq
    if target_seq is None:
        cursor = await conn.execute(
            "SELECT COALESCE(MAX(seq), 0) FROM op_log WHERE workspace_id = ?",
            (workspace_id,),
        )
        row = await cursor.fetchone()
        await cursor.close()
        target_seq = int(row[0]) if row else 0

    rows = await ops_repo.load_ops(conn, workspace_id=workspace_id, max_seq=target_seq)
    projection = build_projection(rows, workspace_id, "")
    snapshot_id = new_id("snap")
    await conn.execute(
        """
        INSERT INTO snapshots (snapshot_id, workspace_id, seq, projection_json, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (
            snapshot_id,
            workspace_id,
            target_seq,
            json.dumps(projection, ensure_ascii=False, separators=(",", ":")),
            at_ms,
        ),
    )
    return {
        "snapshot_id": snapshot_id,
        "workspace_id": workspace_id,
        "seq": target_seq,
        "created_at": at_ms,
        "counts": {
            "lists": len(projection["lists"]),
            "tags": len(projection["tags"]),
            "members": len(projection["members"]),
            "tasks": len(projection["tasks"]),
        },
    }


async def get_snapshot(
    services: Services, user_id: str, workspace_id: str, seq: int | None
) -> dict:
    async with services.read.acquire() as conn:
        await require_role(conn, workspace_id, user_id, None)
        cursor = await conn.execute(
            """
            SELECT * FROM snapshots
            WHERE workspace_id = ? AND seq <= ?
            ORDER BY seq DESC
            LIMIT 1
            """,
            (workspace_id, seq if seq is not None else 2**63 - 1),
        )
        row = await cursor.fetchone()
        await cursor.close()
    if row is None:
        raise AppError("snapshot_not_found")
    return {
        "snapshot_id": row["snapshot_id"],
        "workspace_id": row["workspace_id"],
        "seq": int(row["seq"]),
        "created_at": int(row["created_at"]),
        "projection": json.loads(row["projection_json"]),
    }


async def restore(
    services: Services,
    user_id: str,
    *,
    workspace_id: str,
    snapshot_id: str,
    dry_run: bool,
) -> dict:
    at_ms = now_ms()

    if dry_run:
        async with services.read.acquire() as conn:
            await require_role(conn, workspace_id, user_id, _EDIT_ROLES)
            snapshot, changes = await _prepare_restore(
                conn, workspace_id, snapshot_id, services.settings.restore_max_ops
            )
        return _restore_response(snapshot, changes, dry_run=True)

    async def txn(conn: aiosqlite.Connection) -> dict:
        await require_role(conn, workspace_id, user_id, _EDIT_ROLES)
        snapshot, changes = await _prepare_restore(
            conn, workspace_id, snapshot_id, services.settings.restore_max_ops
        )
        for change in changes:
            validate_semantics(_change_as_op(change, workspace_id, user_id))
        for change in changes:
            await append_server_op(
                conn,
                workspace_id=workspace_id,
                entity_type=change.entity_type,
                entity_id=change.entity_id,
                operation=change.operation,
                field_name=change.field,
                value=change.value,
                actor_id=user_id,
                now_ms=at_ms,
                source="human",
            )
        return _restore_response(snapshot, changes, dry_run=False)

    return await services.writer.submit(txn)


def _change_as_op(change: RestoreChange, workspace_id: str, user_id: str) -> Op:
    """仅用于值域/字段校验的占位 op。"""
    return Op(
        op_id="restore-check",
        workspace_id=workspace_id,
        entity_type=change.entity_type,
        entity_id=change.entity_id,
        actor_id=user_id,
        device_id="srv",
        operation=change.operation,
        field=change.field,
        value=change.value,
        physical_ms=0,
        counter=0,
        node_id="srv",
        source="human",
    )


def _restore_response(
    snapshot: aiosqlite.Row, changes: list[RestoreChange], *, dry_run: bool
) -> dict:
    return {
        "snapshot_id": snapshot["snapshot_id"],
        "restored_to_seq": int(snapshot["seq"]),
        "dry_run": dry_run,
        "op_count": len(changes),
        "changes": [change.as_dict() for change in changes],
    }


async def _prepare_restore(
    conn: aiosqlite.Connection,
    workspace_id: str,
    snapshot_id: str,
    max_ops: int,
) -> tuple[aiosqlite.Row, list[RestoreChange]]:
    cursor = await conn.execute(
        "SELECT * FROM snapshots WHERE snapshot_id = ? AND workspace_id = ?",
        (snapshot_id, workspace_id),
    )
    snapshot = await cursor.fetchone()
    await cursor.close()
    if snapshot is None:
        raise AppError("snapshot_not_found")

    current_rows = await ops_repo.load_ops(conn, workspace_id=workspace_id)
    current = build_projection(current_rows, workspace_id, "")
    target = json.loads(snapshot["projection_json"])
    changes = diff_projections(target, current)
    if len(changes) > max_ops:
        raise AppError(
            "restore_too_large",
            message=f"恢复需要生成 {len(changes)} 条 op，超过上限 {max_ops}，请使用更近的快照",
        )
    return snapshot, changes


def schedule_snapshot_if_due(services: Services, workspace_id: str) -> None:
    if services.settings.snapshot_every_ops <= 0:
        return
    task = asyncio.create_task(_maybe_snapshot(services, workspace_id))
    services.background.add(task)
    task.add_done_callback(services.background.discard)


async def _maybe_snapshot(services: Services, workspace_id: str) -> None:
    threshold = services.settings.snapshot_every_ops
    if threshold <= 0:
        return
    async with services.read.acquire() as conn:
        cursor = await conn.execute(
            "SELECT COALESCE(MAX(seq), 0) FROM snapshots WHERE workspace_id = ?",
            (workspace_id,),
        )
        row = await cursor.fetchone()
        await cursor.close()
        last_snapshot_seq = int(row[0]) if row else 0
        cursor = await conn.execute(
            "SELECT COUNT(*) FROM op_log WHERE workspace_id = ? AND seq > ?",
            (workspace_id, last_snapshot_seq),
        )
        row = await cursor.fetchone()
        await cursor.close()
        pending = int(row[0]) if row else 0
    if pending < threshold:
        return

    at_ms = now_ms()

    async def txn(conn: aiosqlite.Connection) -> Any:
        return await create_snapshot_tx(conn, workspace_id=workspace_id, seq=None, at_ms=at_ms)

    await services.writer.submit(txn)
