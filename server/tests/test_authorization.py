"""授权矩阵与 role-at-HLC 测试。"""

from __future__ import annotations

from httpx import AsyncClient

from .conftest import auth, make_op, now_ms, push_ops, register_user, task_create_ops


async def _prepare(client: AsyncClient) -> dict:
    alice = await register_user(client, username="alice", email="alice@example.com")
    bob = await register_user(client, username="bob", email="bob@example.com")
    workspace_id = alice["default_workspace_id"]
    list_id = f"{workspace_id}-today"
    await push_ops(
        client,
        alice["tokens"]["access_token"],
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-1",
            list_id=list_id,
            actor_id=alice["user"]["user_id"],
        ),
    )
    invited = await client.post(
        "/v1/workspace/invite",
        json={"workspace_id": workspace_id, "account": "bob", "role": "editor"},
        headers=auth(alice["tokens"]["access_token"]),
    )
    assert invited.status_code == 200, invited.text
    return {"alice": alice, "bob": bob, "workspace_id": workspace_id}


async def test_role_at_hlc_downgrade_and_removal(client: AsyncClient) -> None:
    ctx = await _prepare(client)
    alice_token = ctx["alice"]["tokens"]["access_token"]
    bob_token = ctx["bob"]["tokens"]["access_token"]
    bob_id = ctx["bob"]["user"]["user_id"]
    workspace_id = ctx["workspace_id"]

    base = now_ms() + 1000
    early = base
    downgrade = base + 1000
    late = base + 2000

    down_op = make_op(
        op_id="op-downgrade",
        workspace_id=workspace_id,
        entity_type="membership",
        entity_id=f"{workspace_id}:{bob_id}",
        field="__role",
        value="viewer",
        actor_id=ctx["alice"]["user"]["user_id"],
        physical_ms=downgrade,
    )
    assert (await push_ops(client, alice_token, [down_op])).status_code == 200

    early_op = make_op(
        op_id="op-early",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-1",
        field="title",
        value="降权前可写",
        actor_id=bob_id,
        physical_ms=early,
    )
    early_response = await push_ops(client, bob_token, [early_op])
    assert early_response.status_code == 200
    assert early_response.json()["results"][0]["status"] == "accepted"

    late_op = make_op(
        op_id="op-late",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-1",
        field="title",
        value="降权后不可写",
        actor_id=bob_id,
        physical_ms=late,
    )
    late_response = await push_ops(client, bob_token, [late_op])
    assert late_response.status_code == 403
    assert late_response.json()["error"]["code"] == "forbidden"

    removed = await client.post(
        "/v1/workspace/remove",
        json={"workspace_id": workspace_id, "user_id": bob_id},
        headers=auth(alice_token),
    )
    assert removed.status_code == 204

    after_removal = make_op(
        op_id="op-after-removal",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-1",
        field="title",
        value="移除后不可写",
        actor_id=bob_id,
        physical_ms=base + 10_000,
    )
    revoked = await push_ops(client, bob_token, [after_removal])
    assert revoked.status_code == 403
    assert revoked.json()["error"]["code"] == "workspace_access_revoked"


async def test_editor_cannot_manage_membership(client: AsyncClient) -> None:
    ctx = await _prepare(client)
    bob_token = ctx["bob"]["tokens"]["access_token"]
    bob_id = ctx["bob"]["user"]["user_id"]
    workspace_id = ctx["workspace_id"]

    op = make_op(
        op_id="op-self-promote",
        workspace_id=workspace_id,
        entity_type="membership",
        entity_id=f"{workspace_id}:{bob_id}",
        field="__role",
        value="owner",
        actor_id=bob_id,
    )
    response = await push_ops(client, bob_token, [op])
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "forbidden"


async def test_editor_can_write_tasks_and_tag_fields(client: AsyncClient) -> None:
    ctx = await _prepare(client)
    bob_token = ctx["bob"]["tokens"]["access_token"]
    bob_id = ctx["bob"]["user"]["user_id"]
    workspace_id = ctx["workspace_id"]

    op = make_op(
        op_id="op-editor-task",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-1",
        field="priority",
        value="high",
        actor_id=bob_id,
    )
    response = await push_ops(client, bob_token, [op])
    assert response.status_code == 200
    assert response.json()["results"][0]["status"] == "accepted"


async def test_editor_cannot_delete_list(client: AsyncClient) -> None:
    ctx = await _prepare(client)
    bob_token = ctx["bob"]["tokens"]["access_token"]
    bob_id = ctx["bob"]["user"]["user_id"]
    workspace_id = ctx["workspace_id"]

    op = make_op(
        op_id="op-editor-delete-list",
        workspace_id=workspace_id,
        entity_type="list",
        entity_id=f"{workspace_id}-today",
        field="__deleted",
        value=True,
        actor_id=bob_id,
    )
    response = await push_ops(client, bob_token, [op])
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "forbidden"
