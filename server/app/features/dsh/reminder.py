"""服务端提醒：把待办任务上的 `remind_at` 变成一次推送。

**账号体系不另起一套**：提醒归属完全走待办自己的 `users` / `memberships`，
推送只是「这个账号有设备在线就送到」——没有 dsh 专用账号，也没有第二套登录。

规则（与客户端提醒口径对齐，见 docs/reminder-and-tag-model.md）：

- 任务的当前 `remind_at` 取 op_log 里该字段的 LWW 最新值；`__deleted=true` 或
  `status=done` 的任务不提醒。
- 生效时刻 = `remind_at - dsh_reminder_lead_s`；只在 `dsh_reminder_window_s`
  以内补发（服务停机久了不轰炸）。
- 同一条 `(workspace_id, task_id, remind_at)` 对同一个用户只推一次，记在
  `dsh_reminder_fires`；改了 `remind_at` 就是新记录，会再提醒。
- 收件人 = 该工作区未移除的成员（每人一条，按各自设备投递）。
"""

from __future__ import annotations

import hashlib
import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import aiosqlite

from app.features.dsh import usecase
from app.features.dsh.port import ServicesPort as Services
from app.features.dsh.schemas import PushRequest
from app.infra.clock import now_ms as clock_now_ms

logger = logging.getLogger("todo.dsh")

_TASK_FIELDS = ("remind_at", "__deleted", "status", "title")


@dataclass(frozen=True, slots=True)
class Reminder:
    workspace_id: str
    task_id: str
    remind_at_ms: int
    remind_at_raw: str
    title: str


def parse_rfc3339(value: Any) -> int | None:
    """RFC 3339 → 毫秒；解析不了返回 None（脏值不提醒，也不报错）。"""
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    try:
        moment = datetime.fromisoformat(text)
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return int(moment.timestamp() * 1000)


async def latest_task_fields(
    conn: aiosqlite.Connection,
) -> dict[tuple[str, str], dict[str, Any]]:
    """op_log 里每个 (workspace, task) 各字段的 LWW 最新值（HLC 全序）。"""
    placeholders = ",".join("?" for _ in _TASK_FIELDS)
    cursor = await conn.execute(
        f"""
        WITH ranked AS (
          SELECT workspace_id, entity_id, field, value_json,
                 ROW_NUMBER() OVER (
                   PARTITION BY workspace_id, entity_id, field
                   ORDER BY physical_ms DESC, counter DESC, node_id DESC, op_id DESC
                 ) AS rn
          FROM op_log
          WHERE entity_type = 'task' AND field IN ({placeholders})
        )
        SELECT workspace_id, entity_id, field, value_json
        FROM ranked WHERE rn = 1
        """,  # noqa: S608 - placeholders 是常量
        _TASK_FIELDS,
    )
    rows = await cursor.fetchall()
    await cursor.close()

    out: dict[tuple[str, str], dict[str, Any]] = defaultdict(dict)
    for row in rows:
        try:
            import json

            out[(str(row["workspace_id"]), str(row["entity_id"]))][str(row["field"])] = json.loads(
                row["value_json"]
            )
        except (TypeError, ValueError):
            continue
    return out


async def workspace_members(
    conn: aiosqlite.Connection, workspace_ids: list[str]
) -> dict[str, list[str]]:
    if not workspace_ids:
        return {}
    placeholders = ",".join("?" for _ in workspace_ids)
    cursor = await conn.execute(
        f"""
        SELECT workspace_id, user_id FROM memberships
        WHERE workspace_id IN ({placeholders}) AND removed = 0
        """,  # noqa: S608 - placeholders 由列表长度决定
        tuple(workspace_ids),
    )
    rows = await cursor.fetchall()
    await cursor.close()
    out: dict[str, list[str]] = defaultdict(list)
    for row in rows:
        out[str(row["workspace_id"])].append(str(row["user_id"]))
    return out


async def due_reminders(services: Services, *, now: int) -> list[Reminder]:
    settings = services.settings
    lead_ms = max(0, settings.dsh_reminder_lead_s) * 1000
    window_ms = max(0, settings.dsh_reminder_window_s) * 1000

    async with services.read.acquire() as conn:
        fields = await latest_task_fields(conn)

    due: list[Reminder] = []
    for (workspace_id, task_id), values in fields.items():
        if values.get("__deleted") is True:
            continue
        if values.get("status") == "done":
            continue
        raw = values.get("remind_at")
        remind_ms = parse_rfc3339(raw)
        if remind_ms is None:
            continue
        fire_at = remind_ms - lead_ms
        if fire_at > now:
            continue
        if window_ms and now - fire_at > window_ms:
            continue
        title = values.get("title")
        due.append(
            Reminder(
                workspace_id=workspace_id,
                task_id=task_id,
                remind_at_ms=remind_ms,
                remind_at_raw=str(raw),
                title=str(title) if isinstance(title, str) and title else "未命名任务",
            )
        )
    return due


def reminder_msg_id(reminder: Reminder) -> str:
    digest = hashlib.sha256(
        f"{reminder.workspace_id}|{reminder.task_id}|{reminder.remind_at_ms}".encode()
    ).hexdigest()[:24]
    return f"remind-{digest}"


async def _mark_fired(services: Services, reminder: Reminder, user_id: str, now: int) -> bool:
    """记一条触发记录；已经记过返回 False。"""

    async def txn(conn: aiosqlite.Connection) -> bool:
        cursor = await conn.execute(
            """
            INSERT OR IGNORE INTO dsh_reminder_fires
                (workspace_id, task_id, remind_at, user_id, fired_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                reminder.workspace_id,
                reminder.task_id,
                reminder.remind_at_ms,
                user_id,
                now,
            ),
        )
        inserted = cursor.rowcount
        await cursor.close()
        return bool(inserted)

    return bool(await services.writer.submit(txn))


async def sweep(services: Services, *, now: int | None = None) -> int:
    """扫一遍到点提醒，返回本次新推送的条数（按用户计）。"""
    settings = services.settings
    if not settings.dsh_reminders_enabled:
        return 0
    at = now if now is not None else clock_now_ms()
    due = await due_reminders(services, now=at)
    if not due:
        return 0

    async with services.read.acquire() as conn:
        members = await workspace_members(conn, sorted({r.workspace_id for r in due}))

    fired = 0
    for reminder in due:
        for user_id in members.get(reminder.workspace_id, []):
            payload = PushRequest(
                kind="notify",
                level=settings.dsh_reminder_level,
                msg_id=reminder_msg_id(reminder),
                payload={
                    "title": reminder.title,
                    "body": "任务到期提醒",
                    "type": "reminder",
                    "task_id": reminder.task_id,
                    "workspace_id": reminder.workspace_id,
                    "remind_at": reminder.remind_at_raw,
                },
            )
            try:
                await usecase.push(services, user_id=user_id, payload=payload, skip_rate_limit=True)
            except Exception:  # noqa: BLE001 - 单条失败不影响其它提醒
                logger.warning(
                    "提醒推送失败 task=%s user=%s", reminder.task_id, user_id, exc_info=True
                )
                continue
            if await _mark_fired(services, reminder, user_id, at):
                fired += 1
    return fired


async def recent_fires(services: Services, limit: int = 20) -> list[dict[str, Any]]:
    async with services.read.acquire() as conn:
        cursor = await conn.execute(
            """
            SELECT workspace_id, task_id, remind_at, user_id, fired_at
            FROM dsh_reminder_fires ORDER BY fired_at DESC LIMIT ?
            """,
            (limit,),
        )
        rows = await cursor.fetchall()
        await cursor.close()
    return [
        {
            "workspace_id": row["workspace_id"],
            "task_id": row["task_id"],
            "remind_at": int(row["remind_at"]),
            "user_id": row["user_id"],
            "fired_at": int(row["fired_at"]),
        }
        for row in rows
    ]
