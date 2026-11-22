"""dsh 推送通道的端口：纯 SQL 仓储函数（不写业务判定）。"""

from __future__ import annotations

from typing import Any, Protocol

import aiosqlite

from app.config import ServerSettings
from app.db.read_pool import ReadPool
from app.db.writer import WriterWorker


class ServicesPort(Protocol):
    settings: ServerSettings
    writer: WriterWorker
    read: ReadPool
    dsh: Any
    dsh_limiter: Any


# ---------------------------------------------------------------- 设备


async def get_device(conn: aiosqlite.Connection, device_id: str) -> aiosqlite.Row | None:
    cursor = await conn.execute("SELECT * FROM dsh_devices WHERE device_id = ?", (device_id,))
    row = await cursor.fetchone()
    await cursor.close()
    return row


async def list_devices(conn: aiosqlite.Connection, user_id: str) -> list[aiosqlite.Row]:
    cursor = await conn.execute(
        """
        SELECT * FROM dsh_devices
        WHERE user_id = ?
        ORDER BY created_at DESC
        """,
        (user_id,),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return list(rows)


async def insert_device(
    conn: aiosqlite.Connection,
    *,
    device_id: str,
    user_id: str,
    name: str,
    platform: str,
    at_ms: int,
) -> None:
    await conn.execute(
        """
        INSERT INTO dsh_devices (
            device_id, user_id, name, platform, last_seen_at, created_at, revoked
        )
        VALUES (?, ?, ?, ?, ?, ?, 0)
        """,
        (device_id, user_id, name, platform, at_ms, at_ms),
    )


async def update_device_meta(
    conn: aiosqlite.Connection, *, device_id: str, name: str, platform: str
) -> None:
    await conn.execute(
        "UPDATE dsh_devices SET name = ?, platform = ? WHERE device_id = ?",
        (name, platform, device_id),
    )


async def touch_device(conn: aiosqlite.Connection, *, device_id: str, at_ms: int) -> None:
    await conn.execute(
        "UPDATE dsh_devices SET last_seen_at = ? WHERE device_id = ?",
        (at_ms, device_id),
    )


async def mark_revoked(conn: aiosqlite.Connection, device_id: str) -> bool:
    cursor = await conn.execute(
        "UPDATE dsh_devices SET revoked = 1 WHERE device_id = ? AND revoked = 0",
        (device_id,),
    )
    changed = cursor.rowcount
    await cursor.close()
    return bool(changed)


async def count_devices(conn: aiosqlite.Connection, user_id: str) -> tuple[int, int]:
    cursor = await conn.execute(
        """
        SELECT COUNT(*), COALESCE(SUM(revoked), 0) FROM dsh_devices WHERE user_id = ?
        """,
        (user_id,),
    )
    row = await cursor.fetchone()
    await cursor.close()
    total = int(row[0]) if row else 0
    revoked = int(row[1]) if row else 0
    return total, revoked


async def revoked_device_ids(conn: aiosqlite.Connection) -> list[str]:
    cursor = await conn.execute("SELECT device_id FROM dsh_devices WHERE revoked = 1")
    rows = await cursor.fetchall()
    await cursor.close()
    return [str(row[0]) for row in rows]


# ---------------------------------------------------------------- 消息

_PENDING_CLAUSE = """
        user_id = ?
        AND (device_id IS NULL OR device_id = ?)
        AND NOT EXISTS (
            SELECT 1 FROM dsh_acks a
            WHERE a.seq = dsh_messages.seq AND a.device_id = ?
        )
"""


async def find_message_seq(conn: aiosqlite.Connection, msg_id: str) -> int | None:
    cursor = await conn.execute("SELECT seq FROM dsh_messages WHERE msg_id = ?", (msg_id,))
    row = await cursor.fetchone()
    await cursor.close()
    return int(row[0]) if row else None


async def insert_message(
    conn: aiosqlite.Connection,
    *,
    msg_id: str,
    user_id: str,
    device_id: str | None,
    kind: str,
    payload: str,
    created_at: int,
    expires_at: int,
) -> int:
    cursor = await conn.execute(
        """
        INSERT INTO dsh_messages (msg_id, user_id, device_id, kind, payload, created_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (msg_id, user_id, device_id, kind, payload, created_at, expires_at),
    )
    seq = int(cursor.lastrowid or 0)
    await cursor.close()
    return seq


async def pending_count(
    conn: aiosqlite.Connection, *, user_id: str, device_id: str, now_ms: int
) -> int:
    cursor = await conn.execute(
        f"""
        SELECT COUNT(*) FROM dsh_messages
        WHERE {_PENDING_CLAUSE}
          AND expires_at > ?
        """,  # noqa: S608 - 片段是常量
        (user_id, device_id, device_id, now_ms),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return int(row[0]) if row else 0


async def fetch_pending(
    conn: aiosqlite.Connection,
    *,
    user_id: str,
    device_id: str,
    since_seq: int,
    limit: int,
    now_ms: int,
) -> list[aiosqlite.Row]:
    cursor = await conn.execute(
        f"""
        SELECT * FROM dsh_messages
        WHERE {_PENDING_CLAUSE}
          AND expires_at > ?
          AND seq > ?
        ORDER BY seq
        LIMIT ?
        """,  # noqa: S608 - 片段是常量
        (user_id, device_id, device_id, now_ms, since_seq, limit),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return list(rows)


async def has_pending_after(
    conn: aiosqlite.Connection, *, user_id: str, device_id: str, seq: int, now_ms: int
) -> bool:
    cursor = await conn.execute(
        f"""
        SELECT 1 FROM dsh_messages
        WHERE {_PENDING_CLAUSE}
          AND expires_at > ?
          AND seq > ?
        LIMIT 1
        """,  # noqa: S608 - 片段是常量
        (user_id, device_id, device_id, now_ms, seq),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return row is not None


async def trim_pending(
    conn: aiosqlite.Connection, *, user_id: str, device_id: str, keep: int, now_ms: int
) -> int:
    """定向消息未确认数超过上限时，淘汰最旧的几条（广播消息不动，见 §3 设备维度确认）。"""
    if keep <= 0:
        return 0
    cursor = await conn.execute(
        """
        SELECT seq FROM dsh_messages
        WHERE user_id = ? AND device_id = ?
          AND expires_at > ?
          AND NOT EXISTS (
              SELECT 1 FROM dsh_acks a
              WHERE a.seq = dsh_messages.seq AND a.device_id = ?
          )
        ORDER BY seq
        """,
        (user_id, device_id, now_ms, device_id),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    excess = len(rows) - keep
    if excess <= 0:
        return 0
    stale = [int(row[0]) for row in rows[:excess]]
    await delete_messages(conn, stale)
    return len(stale)


async def ack_messages(
    conn: aiosqlite.Connection, *, user_id: str, device_id: str, seqs: list[int], at_ms: int
) -> int:
    if not seqs:
        return 0
    placeholders = ",".join("?" for _ in seqs)
    # 只允许确认属于本用户、本设备可见的消息；别人的 seq 静默忽略。
    cursor = await conn.execute(
        f"""
        SELECT seq FROM dsh_messages
        WHERE seq IN ({placeholders}) AND user_id = ?
        """,  # noqa: S608 - placeholders 由参数数量决定
        (*seqs, user_id),
    )
    owned = [int(row[0]) for row in await cursor.fetchall()]
    await cursor.close()
    if not owned:
        return 0
    before = conn.total_changes
    await conn.executemany(
        "INSERT OR IGNORE INTO dsh_acks (seq, device_id, acked_at) VALUES (?, ?, ?)",
        [(seq, device_id, at_ms) for seq in owned],
    )
    return conn.total_changes - before


async def latest_message_seq(conn: aiosqlite.Connection, user_id: str) -> int:
    cursor = await conn.execute(
        "SELECT COALESCE(MAX(seq), 0) FROM dsh_messages WHERE user_id = ?", (user_id,)
    )
    row = await cursor.fetchone()
    await cursor.close()
    return int(row[0]) if row else 0


async def pending_unacked(conn: aiosqlite.Connection, user_id: str, now_ms: int) -> int:
    """用户维度「还没被至少一台设备确认」的条数；广播消息一律计入（设备维度各认各的）。"""
    cursor = await conn.execute(
        """
        SELECT COUNT(*) FROM dsh_messages m
        WHERE m.user_id = ? AND m.expires_at > ?
          AND (
              m.device_id IS NULL
              OR NOT EXISTS (
                  SELECT 1 FROM dsh_acks a
                  WHERE a.seq = m.seq AND a.device_id = m.device_id
              )
          )
        """,
        (user_id, now_ms),
    )
    row = await cursor.fetchone()
    await cursor.close()
    return int(row[0]) if row else 0


async def expired_seqs(conn: aiosqlite.Connection, *, now_ms: int, limit: int) -> list[int]:
    cursor = await conn.execute(
        "SELECT seq FROM dsh_messages WHERE expires_at <= ? ORDER BY seq LIMIT ?",
        (now_ms, limit),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    return [int(row[0]) for row in rows]


async def delete_messages(conn: aiosqlite.Connection, seqs: list[int]) -> int:
    if not seqs:
        return 0
    placeholders = ",".join("?" for _ in seqs)
    # 先删 ack（外键引用），再删消息。
    await conn.execute(
        f"DELETE FROM dsh_acks WHERE seq IN ({placeholders})",  # noqa: S608
        tuple(seqs),
    )
    cursor = await conn.execute(
        f"DELETE FROM dsh_messages WHERE seq IN ({placeholders})",  # noqa: S608
        tuple(seqs),
    )
    deleted = cursor.rowcount
    await cursor.close()
    return int(deleted or 0)
