"""历史时间线、快照与恢复测试。"""

from __future__ import annotations

from typing import Any

from httpx import AsyncClient

from .conftest import auth, make_op, now_ms, push_ops, register_user, task_create_ops


async def _bootstrap(client: AsyncClient) -> dict[str, Any]:
    data = await register_user(client, username="alice", email="alice@example.com")
    return {
        "token": data["tokens"]["access_token"],
        "user_id": data["user"]["user_id"],
        "workspace_id": data["default_workspace_id"],
        "headers": auth(data["tokens"]["access_token"]),
    }


async def test_snapshot_timeline_and_restore(client: AsyncClient) -> None:
    ctx = await _bootstrap(client)
    token = ctx["token"]
    headers = ctx["headers"]
    user_id = ctx["user_id"]
    workspace_id = ctx["workspace_id"]
    list_id = f"{workspace_id}-today"

    created = await push_ops(
        client,
        token,
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-1",
            list_id=list_id,
            actor_id=user_id,
            title="初始标题",
        ),
    )
    assert created.status_code == 200

    snapshot = await client.post(
        "/v1/history/snapshot",
        json={"workspace_id": workspace_id},
        headers=headers,
    )
    assert snapshot.status_code == 201, snapshot.text
    snapshot_body = snapshot.json()
    assert snapshot_body["counts"]["tasks"] == 1
    assert snapshot_body["counts"]["lists"] == 4
    snapshot_id = snapshot_body["snapshot_id"]

    base = now_ms() + 1000
    changes = [
        make_op(
            op_id="op-title-change",
            workspace_id=workspace_id,
            entity_type="task",
            entity_id="task-1",
            field="title",
            value="新标题",
            actor_id=user_id,
            physical_ms=base,
            counter=0,
        ),
        make_op(
            op_id="op-status-change",
            workspace_id=workspace_id,
            entity_type="task",
            entity_id="task-1",
            field="status",
            value="done",
            actor_id=user_id,
            physical_ms=base,
            counter=1,
        ),
    ]
    assert (await push_ops(client, token, changes)).status_code == 200

    timeline = await client.get(
        "/v1/history/timeline",
        params={"workspace_id": workspace_id, "task_id": "task-1"},
        headers=headers,
    )
    assert timeline.status_code == 200
    entries = timeline.json()["entries"]
    actions = [entry["action"] for entry in entries]
    assert "修改了标题" in actions
    assert entries[0]["undoable"] is True
    assert entries[0]["undone"] is False
    title_entry = next(entry for entry in entries if entry["field"] == "title")
    assert title_entry["new_value"] == "新标题"

    dry = await client.post(
        "/v1/history/restore",
        json={"workspace_id": workspace_id, "snapshot_id": snapshot_id, "dry_run": True},
        headers=headers,
    )
    assert dry.status_code == 200, dry.text
    dry_body = dry.json()
    assert dry_body["dry_run"] is True
    assert dry_body["op_count"] >= 2
    assert [change for change in dry_body["changes"] if change["field"] == "title"]

    # 快照之后新建的任务不应被恢复流程删除。
    late_task = task_create_ops(
        workspace_id=workspace_id,
        task_id="task-2",
        list_id=list_id,
        actor_id=user_id,
        title="快照后新增",
        base_ms=base + 1000,
    )
    assert (await push_ops(client, token, late_task)).status_code == 200

    restored = await client.post(
        "/v1/history/restore",
        json={"workspace_id": workspace_id, "snapshot_id": snapshot_id, "dry_run": False},
        headers=headers,
    )
    assert restored.status_code == 200, restored.text
    assert restored.json()["op_count"] == dry_body["op_count"]

    after = await client.post(
        "/v1/history/snapshot",
        json={"workspace_id": workspace_id},
        headers=headers,
    )
    projection = await client.get(
        "/v1/history/snapshot",
        params={"workspace_id": workspace_id, "seq": after.json()["seq"]},
        headers=headers,
    )
    tasks = {task["id"]: task for task in projection.json()["projection"]["tasks"]}
    assert tasks["task-1"]["title"] == "初始标题"
    assert tasks["task-1"]["status"] == "todo"
    assert "task-2" in tasks


async def test_restore_requires_snapshot(client: AsyncClient) -> None:
    ctx = await _bootstrap(client)
    response = await client.post(
        "/v1/history/restore",
        json={
            "workspace_id": ctx["workspace_id"],
            "snapshot_id": "snap-missing",
            "dry_run": True,
        },
        headers=ctx["headers"],
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "snapshot_not_found"


async def test_get_snapshot_without_any(client: AsyncClient) -> None:
    ctx = await _bootstrap(client)
    response = await client.get(
        "/v1/history/snapshot",
        params={"workspace_id": ctx["workspace_id"]},
        headers=ctx["headers"],
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "snapshot_not_found"
