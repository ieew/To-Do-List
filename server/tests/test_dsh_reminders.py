"""服务端提醒推送测试：remind_at → 一次 notify，且只推一次。"""

from __future__ import annotations

import time

from httpx import AsyncClient

from app.features.dsh import reminder

from .conftest import auth, make_op, push_ops, register_user, rfc3339, task_create_ops


def now_ms() -> int:
    return int(time.time() * 1000)


async def register_device(client: AsyncClient, token: str) -> dict:
    response = await client.post(
        "/v1/dsh/devices/register",
        json={"name": "phone", "platform": "android"},
        headers=auth(token),
    )
    assert response.status_code == 201, response.text
    return response.json()


async def push_task(
    client: AsyncClient,
    token: str,
    *,
    workspace_id: str,
    user_id: str,
    task_id: str = "t-remind",
    title: str = "交电费",
    remind_ms: int | None = None,
    status: str | None = None,
    deleted: bool = False,
) -> None:
    ops = task_create_ops(
        workspace_id=workspace_id,
        task_id=task_id,
        list_id=f"{workspace_id}-today",
        actor_id=user_id,
        title=title,
    )
    if remind_ms is not None:
        # 注意：op 的 HLC 用「现在」，提醒时间只是值里的 RFC3339。
        # 用过去时刻会让权限按 role-at-HLC 判定失败（workspace_access_revoked）。
        ops.append(
            make_op(
                op_id=f"{task_id}-remind-{remind_ms}",
                workspace_id=workspace_id,
                entity_type="task",
                entity_id=task_id,
                field="remind_at",
                value=rfc3339(remind_ms),
                actor_id=user_id,
                counter=100,
            )
        )
    if status is not None:
        ops.append(
            make_op(
                op_id=f"{task_id}-status",
                workspace_id=workspace_id,
                entity_type="task",
                entity_id=task_id,
                field="status",
                value=status,
                actor_id=user_id,
                counter=100,
            )
        )
    if deleted:
        ops.append(
            make_op(
                op_id=f"{task_id}-deleted",
                workspace_id=workspace_id,
                entity_type="task",
                entity_id=task_id,
                field="__deleted",
                value=True,
                actor_id=user_id,
                counter=100,
            )
        )
    response = await push_ops(client, token, ops)
    assert response.status_code == 200, response.text


async def test_reminder_fires_once_with_level(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    user_id = data["user"]["user_id"]
    workspace_id = data["default_workspace_id"]
    device = await register_device(client, token)

    at = now_ms()
    await push_task(
        client, token, workspace_id=workspace_id, user_id=user_id, remind_ms=at - 60_000
    )

    services = client.app.state.services
    fired = await reminder.sweep(services, now=at)
    assert fired == 1

    # 第二次扫描不该重复推
    assert await reminder.sweep(services, now=at + 30_000) == 0

    pulled = await client.get(
        "/v1/dsh/pull",
        params={"device_id": device["device_id"], "since": 0},
        headers=auth(token),
    )
    assert pulled.status_code == 200
    messages = pulled.json()["messages"]
    assert len(messages) == 1
    message = messages[0]
    assert message["kind"] == "notify"
    assert message["payload"]["level"] == 6
    assert message["payload"]["type"] == "reminder"
    assert message["payload"]["title"] == "交电费"
    assert message["payload"]["task_id"] == "t-remind"


async def test_reminder_respects_window(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    user_id = data["user"]["user_id"]
    workspace_id = data["default_workspace_id"]

    at = now_ms()
    # 3 小时前就该提醒了，超出默认 1 小时补发窗口
    await push_task(
        client, token, workspace_id=workspace_id, user_id=user_id, remind_ms=at - 3 * 3600 * 1000
    )
    assert await reminder.sweep(client.app.state.services, now=at) == 0


async def test_reminder_skips_done_and_deleted(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    user_id = data["user"]["user_id"]
    workspace_id = data["default_workspace_id"]

    at = now_ms()
    await push_task(
        client,
        token,
        workspace_id=workspace_id,
        user_id=user_id,
        task_id="t-done",
        remind_ms=at - 60_000,
        status="done",
    )
    await push_task(
        client,
        token,
        workspace_id=workspace_id,
        user_id=user_id,
        task_id="t-del",
        remind_ms=at - 60_000,
        deleted=True,
    )
    assert await reminder.sweep(client.app.state.services, now=at) == 0


async def test_reminder_refires_when_time_changed(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    user_id = data["user"]["user_id"]
    workspace_id = data["default_workspace_id"]

    at = now_ms()
    await push_task(
        client, token, workspace_id=workspace_id, user_id=user_id, remind_ms=at - 120_000
    )
    services = client.app.state.services
    assert await reminder.sweep(services, now=at) == 1

    # 改 remind_at → 新的一次提醒
    await push_task(
        client,
        token,
        workspace_id=workspace_id,
        user_id=user_id,
        task_id="t-remind",
        remind_ms=at - 60_000,
    )
    assert await reminder.sweep(services, now=at + 1000) == 1
    assert await reminder.sweep(services, now=at + 2000) == 0


async def test_reminder_lead_time(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    user_id = data["user"]["user_id"]
    workspace_id = data["default_workspace_id"]

    at = now_ms()
    # 5 分钟后才到点
    await push_task(
        client, token, workspace_id=workspace_id, user_id=user_id, remind_ms=at + 5 * 60 * 1000
    )
    services = client.app.state.services
    assert await reminder.sweep(services, now=at) == 0

    original = services.settings.dsh_reminder_lead_s
    services.settings.dsh_reminder_lead_s = 600  # 提前 10 分钟
    try:
        assert await reminder.sweep(services, now=at) == 1
    finally:
        services.settings.dsh_reminder_lead_s = original
