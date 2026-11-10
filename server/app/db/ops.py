"""op_log 读写助手：插入、读取、实体绑定、成员缓存、服务端 HLC。"""

from __future__ import annotations

import json
from collections.abc import Sequence
from typing import Any

import aiosqlite

from app.core_domain.hlc import Hlc, next_hlc
from app.core_domain.op import Op

SERVER_NODE_ID = "srv"


def row_to_op(row: aiosqlite.Row) -> Op:
    raw_meta = row["source_meta_json"]
    meta: dict[str, Any] = json.loads(raw_meta) if raw_meta else {}
    return Op(
        op_id=row["op_id"],
        workspace_id=row["workspace_id"],
        entity_type=row["entity_type"],
        entity_id=row["entity_id"],
        actor_id=row["actor_id"],
        device_id=row["device_id"],
        operation=row["operation"],
        field=row["field"],
        value=json.loads(row["value_json"]),
        physical_ms=row["physical_ms"],
        counter=row["counter"],
        node_id=row["node_id"],
        source=row["source"],
        model=meta.get("model"),
        prompt_hash=meta.get("prompt_hash"),
        seq=row["seq"],
        received_at=row["received_at"],
    )


async def insert_op(conn: aiosqlite.Connection, op: Op) -> int:
    meta = (
        json.dumps(
            {"model": op.model, "prompt_hash": op.prompt_hash},
            ensure_ascii=False,
            separators=(",", ":"),
        )
        if op.model is not None or op.prompt_hash is not None
        else None
    )
    cursor = await conn.execute(
        """
        INSERT INTO op_log (
            op_id, workspace_id, entity_type, entity_id, actor_id, device_id,
            operation, field, value_json, physical_ms, counter, node_id,
            source, source_meta_json, received_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            op.op_id,
            op.workspace_id,
            op.entity_type,
            op.entity_id,
            op.actor_id,
            op.device_id,
            op.operation,
            op.field,
            json.dumps(op.value, ensure_ascii=False, separators=(",", ":")),
            op.physical_ms,
            op.counter,
            op.node_id,
            op.source,
            meta,
            op.received_at or op.physical_ms,
        ),
    )
    seq = int(cursor.lastrowid or 0)
    await cursor.close()
    return seq


async def find_op_seq(conn: aiosqlite.Connection, op_id: str) -> int | None:
    cursor = await conn.execute("SELECT seq FROM op_log WHERE op_id = ?", (op_id,))
    row = await cursor.fetchone()
    await cursor.close()
    return int(row[0]) if row else None


async def load_ops(
    conn: aiosqlite.Connection,
    *,
    workspace_id: str | None = None,
    max_seq: int | None = None,
    since_seq: int | None = None,
) -> list[Op]:
    clauses: list[str] = []
    params: list[Any] = []
    if workspace_id is not None:
        clauses.append("workspace_id = ?")
        params.append(workspace_id)
    if max_seq is not None:
        clauses.append("seq <= ?")
        params.append(max_seq)
    if since_seq is not None:
        clauses.append("seq > ?")
        params.append(since_seq)
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
    # where 仅由上面的常量子句拼接，值均走参数绑定。
    cursor = await conn.execute(
        f"SELECT * FROM op_log {where} ORDER BY seq",  # noqa: S608
        params,
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return [row_to_op(row) for row in rows]


async def latest_seq(conn: aiosqlite.Connection) -> int:
    cursor = await conn.execute("SELECT COALESCE(MAX(seq), 0) FROM op_log")
    row = await cursor.fetchone()
    await cursor.close()
    return int(row[0]) if row else 0


async def max_workspace_hlc(
    conn: aiosqlite.Connection, workspace_id: str
) -> tuple[int, int] | None:
    cursor = await conn.execute(
        """
        SELECT physical_ms, counter FROM op_log
        WHERE workspace_id = ?
        ORDER BY physical_ms DESC, counter DESC
        LIMIT 1
        """,
        (workspace_id,),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return (int(row[0]), int(row[1])) if row else None


async def next_server_hlc(
    conn: aiosqlite.Connection,
    now_ms: int,
    *,
    workspace_id: str | None = None,
) -> Hlc:
    cursor = await conn.execute("SELECT physical_ms, counter FROM server_hlc WHERE id = 1")
    row = await cursor.fetchone()
    await cursor.close()
    if row is None:
        await conn.execute(
            "INSERT INTO server_hlc (id, physical_ms, counter, node_id) VALUES (1, ?, 0, ?)",
            (now_ms, SERVER_NODE_ID),
        )
        prev_physical, prev_counter = now_ms, 0
    else:
        prev_physical, prev_counter = int(row[0]), int(row[1])

    floor = await max_workspace_hlc(conn, workspace_id) if workspace_id else None
    hlc = next_hlc(
        prev_physical_ms=prev_physical,
        prev_counter=prev_counter,
        now_ms=now_ms,
        node_id=SERVER_NODE_ID,
        floor=floor,
    )
    await conn.execute(
        "UPDATE server_hlc SET physical_ms = ?, counter = ? WHERE id = 1",
        (hlc.physical_ms, hlc.counter),
    )
    return hlc


async def server_hlc_snapshot(conn: aiosqlite.Connection) -> Hlc:
    cursor = await conn.execute("SELECT physical_ms, counter, node_id FROM server_hlc WHERE id = 1")
    row = await cursor.fetchone()
    await cursor.close()
    if row is None:
        return Hlc(0, 0, SERVER_NODE_ID)
    return Hlc(int(row[0]), int(row[1]), str(row[2]))


async def get_entity_workspace(
    conn: aiosqlite.Connection, entity_type: str, entity_id: str
) -> str | None:
    cursor = await conn.execute(
        "SELECT workspace_id FROM entities WHERE entity_type = ? AND entity_id = ?",
        (entity_type, entity_id),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return str(row[0]) if row else None


async def bind_entity(
    conn: aiosqlite.Connection,
    entity_type: str,
    entity_id: str,
    workspace_id: str,
    seq: int,
) -> None:
    await conn.execute(
        """
        INSERT INTO entities (entity_type, entity_id, workspace_id, created_seq)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(entity_type, entity_id) DO NOTHING
        """,
        (entity_type, entity_id, workspace_id, seq),
    )


async def upsert_workspace(
    conn: aiosqlite.Connection,
    workspace_id: str,
    *,
    name: str | None = None,
    deleted: bool | None = None,
    created_at: int = 0,
) -> None:
    await conn.execute(
        """
        INSERT INTO workspaces (workspace_id, name, deleted, created_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(workspace_id) DO UPDATE SET
            name = CASE WHEN ? IS NULL THEN workspaces.name ELSE ? END,
            deleted = CASE WHEN ? IS NULL THEN workspaces.deleted ELSE ? END
        """,
        (
            workspace_id,
            name or "",
            1 if deleted else 0,
            created_at,
            name,
            name,
            None if deleted is None else (1 if deleted else 0),
            None if deleted is None else (1 if deleted else 0),
        ),
    )


async def refresh_membership_cache(
    conn: aiosqlite.Connection, workspace_id: str, user_id: str, now_ms: int
) -> None:
    entity_id = f"{workspace_id}:{user_id}"
    cursor = await conn.execute(
        """
        SELECT field, value_json, physical_ms, counter, node_id, op_id
        FROM op_log
        WHERE entity_type = 'membership' AND entity_id = ?
        ORDER BY physical_ms, counter, node_id, op_id
        """,
        (entity_id,),
    )
    rows = await cursor.fetchall()
    await cursor.close()

    role: str | None = None
    removed = False
    for row in rows:
        field_name = row["field"]
        value = json.loads(row["value_json"])
        if field_name == "__role" and isinstance(value, str):
            role = value
        elif field_name == "__removed":
            removed = value is True

    if role is None and not removed:
        return
    await conn.execute(
        """
        INSERT INTO memberships (workspace_id, user_id, role, removed, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(workspace_id, user_id) DO UPDATE SET
            role = excluded.role,
            removed = excluded.removed,
            updated_at = excluded.updated_at
        """,
        (workspace_id, user_id, role or "viewer", 1 if removed else 0, now_ms),
    )


async def op_rows_to_wire(rows: Sequence[aiosqlite.Row]) -> list[dict[str, Any]]:
    return [row_to_op(row).to_wire() for row in rows]
