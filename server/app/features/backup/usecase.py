"""备份：工作区逻辑导出与 admin 全库快照。"""

from __future__ import annotations

import asyncio
import json
import sqlite3
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any

from app.core_domain.projection import build_projection
from app.db import ops as ops_repo
from app.features.backup.port import ServicesPort as Services
from app.features.workspace_member.usecase import require_role
from app.infra.clock import now_ms

EXCLUDED = ["password_hash", "refresh_token", "jwt_secret"]


async def export_workspace(services: Services, user_id: str, workspace_id: str) -> tuple[Path, str]:
    async with services.read.acquire() as conn:
        await require_role(conn, workspace_id, user_id, None)
        await conn.execute("BEGIN DEFERRED")
        try:
            rows = await ops_repo.load_ops(conn, workspace_id=workspace_id)
            server_seq = await ops_repo.latest_seq(conn)
        finally:
            await conn.execute("COMMIT")

    projections = build_projection(rows, workspace_id, user_id)
    ops_wire = [op.to_wire() for op in rows]
    manifest: dict[str, Any] = {
        "protocol_version": services.settings.protocol_version,
        "generated_at": now_ms(),
        "exporter_user_id": user_id,
        "workspace_id": workspace_id,
        "server_seq": server_seq,
        "counts": {
            "ops": len(ops_wire),
            "lists": len(projections["lists"]),
            "tags": len(projections["tags"]),
            "members": len(projections["members"]),
            "tasks": len(projections["tasks"]),
        },
        "excluded": EXCLUDED,
    }

    handle = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
    path = Path(handle.name)
    handle.close()
    await asyncio.to_thread(_write_zip, path, manifest, ops_wire, projections)

    stamp = datetime.now().astimezone().strftime("%Y%m%d%H%M%S")
    filename = f"todo-backup-{workspace_id}-{stamp}.zip"
    return path, filename


def _write_zip(
    path: Path,
    manifest: dict[str, Any],
    ops_wire: list[dict[str, Any]],
    projection: dict[str, Any],
) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        payload = "\n".join(
            json.dumps(item, ensure_ascii=False, separators=(",", ":")) for item in ops_wire
        )
        archive.writestr("workspace/ops.jsonl", payload + ("\n" if payload else ""))
        archive.writestr(
            "workspace/projection.json",
            json.dumps(projection, ensure_ascii=False, indent=2),
        )


async def admin_backup(services: Services) -> dict[str, Any]:
    backup_dir = services.settings.backup_dir
    backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().astimezone().strftime("%Y%m%d%H%M%S")
    destination = backup_dir / f"server-{stamp}.sqlite3"
    await asyncio.to_thread(_sqlite_backup, services.settings.db_path, destination)

    async with services.read.acquire() as conn:
        rows = await ops_repo.load_ops(conn)
    jsonl_path = destination.with_suffix(".jsonl")
    await asyncio.to_thread(_write_jsonl, jsonl_path, [op.to_wire() for op in rows])

    size = await asyncio.to_thread(lambda: destination.stat().st_size)
    return {
        "backup_path": str(destination),
        "jsonl_path": str(jsonl_path),
        "size_bytes": size,
        "created_at": now_ms(),
    }


def _sqlite_backup(source_path: Path, destination: Path) -> None:
    source = sqlite3.connect(str(source_path))
    try:
        target = sqlite3.connect(str(destination))
        try:
            with target:
                source.backup(target)
        finally:
            target.close()
    finally:
        source.close()


def _write_jsonl(path: Path, items: list[dict[str, Any]]) -> None:
    lines = "\n".join(json.dumps(item, ensure_ascii=False, separators=(",", ":")) for item in items)
    path.write_text(lines + ("\n" if lines else ""), encoding="utf-8")
