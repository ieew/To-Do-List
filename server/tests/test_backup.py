"""备份导出测试。"""

from __future__ import annotations

import io
import json
import zipfile

from httpx import AsyncClient

from app.config import ServerSettings

from .conftest import auth, push_ops, register_user, task_create_ops


async def _seed(client: AsyncClient) -> tuple[dict, dict]:
    data = await register_user(client, username="alice", email="alice@example.com")
    workspace_id = data["default_workspace_id"]
    await push_ops(
        client,
        data["tokens"]["access_token"],
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-1",
            list_id=f"{workspace_id}-today",
            actor_id=data["user"]["user_id"],
            title="备份任务",
        ),
    )
    return data, {"workspace_id": workspace_id}


async def test_export_zip_structure(client: AsyncClient) -> None:
    data, ctx = await _seed(client)
    response = await client.get(
        "/v1/backup/export",
        params={"workspace_id": ctx["workspace_id"]},
        headers=auth(data["tokens"]["access_token"]),
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/zip")

    archive = zipfile.ZipFile(io.BytesIO(response.content))
    names = set(archive.namelist())
    assert {"manifest.json", "workspace/ops.jsonl", "workspace/projection.json"} <= names

    manifest = json.loads(archive.read("manifest.json"))
    assert manifest["workspace_id"] == ctx["workspace_id"]
    assert manifest["excluded"] == ["password_hash", "refresh_token", "jwt_secret"]
    assert manifest["counts"]["tasks"] == 1
    assert "access_token" not in archive.read("manifest.json").decode("utf-8")

    ops_text = archive.read("workspace/ops.jsonl").decode("utf-8")
    op_lines = [line for line in ops_text.splitlines() if line.strip()]
    assert op_lines
    assert all(json.loads(line)["workspace_id"] == ctx["workspace_id"] for line in op_lines)

    projection = json.loads(archive.read("workspace/projection.json"))
    assert projection["tasks"][0]["title"] == "备份任务"


async def test_admin_backup_requires_token(client: AsyncClient) -> None:
    forbidden = await client.post("/v1/admin/backup")
    assert forbidden.status_code == 403

    allowed = await client.post("/v1/admin/backup", headers={"X-Admin-Token": "admin-token"})
    assert allowed.status_code == 201, allowed.text
    body = allowed.json()
    assert body["size_bytes"] > 0
    assert body["backup_path"].endswith(".sqlite3")


async def test_export_requires_membership(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice", email="alice@example.com")
    bob = await register_user(client, username="bob", email="bob@example.com")
    response = await client.get(
        "/v1/backup/export",
        params={"workspace_id": alice["default_workspace_id"]},
        headers=auth(bob["tokens"]["access_token"]),
    )
    assert response.status_code == 403


def test_settings_backup_dir_default() -> None:
    settings = ServerSettings(jwt_secret="x" * 40)
    assert settings.backup_dir.name == "backups"
