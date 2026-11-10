"""服务端生成 op 与注册种子数据。

预设清单/标签必须与 `windows/src-tauri/src/storage.rs` 的 DEFAULT_LISTS / DEFAULT_TAGS
逐字一致；改动任一处都必须同步另一处与相关文档。
"""

from __future__ import annotations

from typing import Any

import aiosqlite

from app.core_domain.op import Op
from app.db import ops as ops_repo
from app.infra.clock import now_iso

DEFAULT_LISTS: tuple[tuple[str, str, str, float], ...] = (
    ("today", "今天", "#3B82F6", 0.0),
    ("work", "工作", "#F59E0B", 1.0),
    ("life", "生活", "#10B981", 2.0),
    ("study", "学习", "#8B5CF6", 3.0),
)

DEFAULT_TAGS: tuple[tuple[str, str, str, str, bool], ...] = (
    ("work", "工作", "#EFF6FF", "#2563EB", False),
    ("life", "生活", "#ECFDF5", "#059669", False),
    ("study", "学习", "#F5F3FF", "#7C3AED", False),
    ("important", "重要", "#FEF2F2", "#DC2626", True),
    ("week", "本周", "#FEF3C7", "#B45309", True),
)

PALETTE = ("#3B82F6", "#10B981", "#8B5CF6", "#F59E0B", "#EC4899")


def initial_of(name: str) -> str:
    stripped = name.strip()
    return stripped[0] if stripped else "用"


def color_for(name: str) -> str:
    return PALETTE[len(name.strip()) % len(PALETTE)]


async def append_server_op(
    conn: aiosqlite.Connection,
    *,
    workspace_id: str,
    entity_type: str,
    entity_id: str,
    operation: str,
    field_name: str,
    value: Any,
    actor_id: str,
    now_ms: int,
    source: str = "system",
    model: str | None = None,
) -> Op:
    """以 `node_id = "srv"` 追加一条服务端生成的 op，并维护缓存与绑定。"""
    hlc = await ops_repo.next_server_hlc(conn, now_ms, workspace_id=workspace_id)
    op = Op(
        op_id=_new_op_id(),
        workspace_id=workspace_id,
        entity_type=entity_type,
        entity_id=entity_id,
        actor_id=actor_id,
        device_id="srv",
        operation=operation,
        field=field_name,
        value=value,
        physical_ms=hlc.physical_ms,
        counter=hlc.counter,
        node_id=hlc.node_id,
        source=source,
        model=model,
        received_at=now_ms,
    )
    seq = await ops_repo.insert_op(conn, op)
    op.seq = seq
    await update_caches(conn, op, now_ms)
    return op


async def update_caches(conn: aiosqlite.Connection, op: Op, now_ms: int) -> None:
    """按 op 更新实体绑定、工作区缓存与成员当前态。"""
    if op.entity_type == "workspace":
        if op.field == "__created":
            await ops_repo.bind_entity(
                conn, "workspace", op.entity_id, op.workspace_id, op.seq or 0
            )
            await ops_repo.upsert_workspace(conn, op.workspace_id, created_at=now_ms)
        elif op.field == "name" and isinstance(op.value, str):
            await ops_repo.upsert_workspace(conn, op.workspace_id, name=op.value)
        elif op.field == "__deleted":
            await ops_repo.upsert_workspace(conn, op.workspace_id, deleted=op.value is True)
        return

    if op.entity_type == "membership":
        workspace_id, _, user_id = op.entity_id.partition(":")
        if workspace_id and user_id:
            await ops_repo.refresh_membership_cache(conn, workspace_id, user_id, now_ms)
        return

    if op.field == "__created":
        await ops_repo.bind_entity(conn, op.entity_type, op.entity_id, op.workspace_id, op.seq or 0)


def _new_op_id() -> str:
    import uuid

    return str(uuid.uuid4())


async def seed_new_workspace(
    conn: aiosqlite.Connection,
    *,
    workspace_id: str,
    user_id: str,
    display_name: str,
    email: str | None,
    workspace_name: str,
    now_ms: int,
) -> None:
    """注册时写入默认工作区、成员关系、4 个预设清单与 5 个预设标签。"""
    created_at = now_iso()
    await ops_repo.upsert_workspace(conn, workspace_id, name=workspace_name, created_at=now_ms)

    async def write(
        entity_type: str,
        entity_id: str,
        field_name: str,
        value: Any,
        source: str = "system",
    ) -> None:
        await append_server_op(
            conn,
            workspace_id=workspace_id,
            entity_type=entity_type,
            entity_id=entity_id,
            operation="set",
            field_name=field_name,
            value=value,
            actor_id=user_id,
            now_ms=now_ms,
            source=source,
        )

    await write("workspace", workspace_id, "__created", created_at)
    await write("workspace", workspace_id, "name", workspace_name)
    await write("membership", f"{workspace_id}:{user_id}", "__role", "owner")
    await write("user", user_id, "name", display_name)
    await write("user", user_id, "initial", initial_of(display_name))
    await write("user", user_id, "color", "#3B82F6")
    await write("user", user_id, "email", email or "")

    for key, name, color, order in DEFAULT_LISTS:
        list_id = f"{workspace_id}-{key}"
        await write("list", list_id, "__created", created_at)
        await write("list", list_id, "workspace_id", workspace_id)
        await write("list", list_id, "name", name)
        await write("list", list_id, "color", color)
        await write("list", list_id, "order", order)

    for key, name, bg, fg, sidebar in DEFAULT_TAGS:
        tag_id = f"{workspace_id}-tag-{key}"
        await write("tag", tag_id, "__created", created_at)
        await write("tag", tag_id, "workspace_id", workspace_id)
        await write("tag", tag_id, "name", name)
        await write("tag", tag_id, "bg", bg)
        await write("tag", tag_id, "fg", fg)
        await write("tag", tag_id, "show_in_sidebar", sidebar)
