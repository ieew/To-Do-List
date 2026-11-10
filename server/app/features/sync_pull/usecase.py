"""增量拉取用例。"""

from __future__ import annotations

import aiosqlite

from app.core_domain.errors import AppError
from app.db import ops as ops_repo
from app.features.sync_pull.port import ServicesPort as Services


async def pull(
    services: Services,
    user_id: str,
    *,
    workspace_id: str | None,
    since_seq: int,
    limit: int,
) -> dict:
    async with services.read.acquire() as conn:
        if workspace_id is not None:
            await _require_membership(conn, workspace_id, user_id)
            rows = await _fetch_page(conn, workspace_id, since_seq, limit)
        else:
            accessible = await _accessible_workspaces(conn, user_id)
            rows = await _fetch_page_multi(conn, accessible, since_seq, limit)

        last_seq = int(rows[-1]["seq"]) if rows else since_seq
        has_more = False
        if len(rows) == limit and rows:
            has_more = await _has_more(conn, workspace_id, last_seq)
            if has_more is False and workspace_id is None:
                has_more = await _has_more_any(conn, user_id, last_seq)

        server_seq = await ops_repo.latest_seq(conn)
        server_hlc = await ops_repo.server_hlc_snapshot(conn)

    return {
        "ops": [ops_repo.row_to_op(row).to_wire() for row in rows],
        "next_seq": last_seq,
        "has_more": has_more,
        "server_seq": server_seq,
        "server_hlc": server_hlc.to_wire(),
    }


async def _require_membership(conn: aiosqlite.Connection, workspace_id: str, user_id: str) -> None:
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


async def _accessible_workspaces(conn: aiosqlite.Connection, user_id: str) -> list[str]:
    cursor = await conn.execute(
        "SELECT workspace_id FROM memberships WHERE user_id = ? AND removed = 0",
        (user_id,),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return [str(row[0]) for row in rows]


async def _fetch_page(
    conn: aiosqlite.Connection, workspace_id: str, since_seq: int, limit: int
) -> list[aiosqlite.Row]:
    cursor = await conn.execute(
        """
        SELECT * FROM op_log
        WHERE workspace_id = ? AND seq > ?
        ORDER BY seq
        LIMIT ?
        """,
        (workspace_id, since_seq, limit),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return list(rows)


async def _fetch_page_multi(
    conn: aiosqlite.Connection,
    workspace_ids: list[str],
    since_seq: int,
    limit: int,
) -> list[aiosqlite.Row]:
    if not workspace_ids:
        return []
    placeholders = ",".join("?" for _ in workspace_ids)
    # 占位符数量由工作区列表长度决定，值全部走参数绑定。
    cursor = await conn.execute(
        f"""
        SELECT * FROM op_log
        WHERE workspace_id IN ({placeholders}) AND seq > ?
        ORDER BY seq
        LIMIT ?
        """,  # noqa: S608
        (*workspace_ids, since_seq, limit),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return list(rows)


async def _has_more(conn: aiosqlite.Connection, workspace_id: str | None, last_seq: int) -> bool:
    if workspace_id is None:
        return False
    cursor = await conn.execute(
        "SELECT 1 FROM op_log WHERE workspace_id = ? AND seq > ? LIMIT 1",
        (workspace_id, last_seq),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return row is not None


async def _has_more_any(conn: aiosqlite.Connection, user_id: str, last_seq: int) -> bool:
    cursor = await conn.execute(
        """
        SELECT 1 FROM op_log o
        JOIN memberships m ON m.workspace_id = o.workspace_id
        WHERE m.user_id = ? AND m.removed = 0 AND o.seq > ?
        LIMIT 1
        """,
        (user_id, last_seq),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return row is not None
