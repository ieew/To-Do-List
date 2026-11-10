"""测试夹具：临时数据库 + 应用生命周期 + 已登录客户端。"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient

from app.config import ServerSettings
from app.main import create_app

WORKSPACE_ROOT = Path(__file__).resolve().parents[2]
VECTOR_DIR = WORKSPACE_ROOT / "shared" / "testvectors"


@pytest.fixture
def settings(tmp_path: Path) -> ServerSettings:
    return ServerSettings(
        db_path=tmp_path / "server.sqlite3",
        jwt_secret="test-secret-" + "x" * 40,
        admin_token="admin-token",
        backup_dir=tmp_path / "backups",
        snapshot_every_ops=0,
        auth_rate_limit=0,
        log_level="warning",
    )


@pytest.fixture
async def app(settings: ServerSettings):
    application = create_app(settings)
    async with application.router.lifespan_context(application):
        yield application


@pytest.fixture
async def client(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as http:
        http.app = app  # type: ignore[attr-defined]
        yield http


def now_ms() -> int:
    return int(time.time() * 1000)


def rfc3339(ms: int | None = None) -> str:
    from datetime import datetime

    moment = datetime.fromtimestamp((ms or now_ms()) / 1000).astimezone()
    return moment.isoformat(timespec="seconds")


def make_op(
    *,
    op_id: str,
    workspace_id: str,
    entity_type: str,
    entity_id: str,
    field: str,
    value: Any,
    actor_id: str,
    operation: str = "set",
    physical_ms: int | None = None,
    counter: int = 0,
    node_id: str = "win-test",
    device_id: str = "win-test",
    source: str = "human",
    model: str | None = None,
) -> dict[str, Any]:
    return {
        "op_id": op_id,
        "workspace_id": workspace_id,
        "entity_type": entity_type,
        "entity_id": entity_id,
        "actor_id": actor_id,
        "device_id": device_id,
        "operation": operation,
        "field": field,
        "value": value,
        "hlc": {
            "physical_ms": physical_ms if physical_ms is not None else now_ms(),
            "counter": counter,
            "node_id": node_id,
        },
        "source": source,
        "source_meta": {"model": model, "prompt_hash": None},
    }


def workspace_create_ops(
    *,
    workspace_id: str,
    user_id: str,
    name: str = "第二个空间",
    base_ms: int | None = None,
) -> list[dict[str, Any]]:
    at = base_ms if base_ms is not None else now_ms()
    created = rfc3339(at)
    entries: list[tuple[str, str, str, Any]] = [
        ("workspace", workspace_id, "__created", created),
        ("workspace", workspace_id, "name", name),
        ("membership", f"{workspace_id}:{user_id}", "__role", "owner"),
    ]
    list_id = f"{workspace_id}-today"
    entries.extend(
        [
            ("list", list_id, "__created", created),
            ("list", list_id, "workspace_id", workspace_id),
            ("list", list_id, "name", "今天"),
            ("list", list_id, "color", "#3B82F6"),
            ("list", list_id, "order", 0.0),
        ]
    )
    return [
        make_op(
            op_id=f"{workspace_id}-op-{index}",
            workspace_id=workspace_id,
            entity_type=entity_type,
            entity_id=entity_id,
            field=field_name,
            value=value,
            actor_id=user_id,
            physical_ms=at,
            counter=index,
        )
        for index, (entity_type, entity_id, field_name, value) in enumerate(entries)
    ]


def task_create_ops(
    *,
    workspace_id: str,
    task_id: str,
    list_id: str,
    actor_id: str,
    title: str = "测试任务",
    base_ms: int | None = None,
    node_id: str = "win-test",
) -> list[dict[str, Any]]:
    at = base_ms if base_ms is not None else now_ms()
    fields: list[tuple[str, Any]] = [
        ("__created", rfc3339(at)),
        ("title", title),
        ("notes", ""),
        ("priority", "mid"),
        ("status", "todo"),
        ("list_id", list_id),
        ("order", 0.0),
    ]
    return [
        make_op(
            op_id=f"{task_id}-{index}",
            workspace_id=workspace_id,
            entity_type="task",
            entity_id=task_id,
            field=field_name,
            value=value,
            actor_id=actor_id,
            physical_ms=at,
            counter=index,
            node_id=node_id,
        )
        for index, (field_name, value) in enumerate(fields)
    ]


async def register_user(
    client: AsyncClient,
    *,
    username: str = "alice",
    password: str = "password123",
    email: str | None = None,
    display_name: str | None = None,
) -> dict[str, Any]:
    response = await client.post(
        "/v1/auth/register",
        json={
            "username": username,
            "password": password,
            "email": email,
            "display_name": display_name,
        },
    )
    assert response.status_code == 201, response.text
    return response.json()


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


async def push_ops(
    client: AsyncClient,
    token: str,
    ops: list[dict[str, Any]],
    *,
    device_id: str = "win-test",
):
    return await client.post(
        "/v1/sync/push",
        json={"device_id": device_id, "ops": ops},
        headers=auth(token),
    )


async def pull_ops(
    client: AsyncClient,
    token: str,
    *,
    workspace_id: str | None = None,
    since_seq: int = 0,
    limit: int = 200,
):
    params: dict[str, Any] = {"since_seq": since_seq, "limit": limit}
    if workspace_id is not None:
        params["workspace_id"] = workspace_id
    return await client.get("/v1/sync/pull", params=params, headers=auth(token))
