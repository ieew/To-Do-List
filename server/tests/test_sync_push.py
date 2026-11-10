"""同步推送测试：接受、幂等、信封校验、时钟与授权拒绝。"""

from __future__ import annotations

from httpx import AsyncClient

from .conftest import (
    auth,
    make_op,
    now_ms,
    push_ops,
    register_user,
    task_create_ops,
    workspace_create_ops,
)


async def _setup(client: AsyncClient) -> tuple[str, str, str]:
    data = await register_user(client)
    return (
        data["tokens"]["access_token"],
        data["user"]["user_id"],
        data["default_workspace_id"],
    )


async def test_accepts_task_ops_and_reports_duplicates(client: AsyncClient) -> None:
    token, user_id, workspace_id = await _setup(client)
    list_id = f"{workspace_id}-today"
    ops = task_create_ops(
        workspace_id=workspace_id,
        task_id="task-1",
        list_id=list_id,
        actor_id=user_id,
    )

    first = await push_ops(client, token, ops)
    assert first.status_code == 200, first.text
    body = first.json()
    assert [item["status"] for item in body["results"]] == ["accepted"] * len(ops)
    seqs = [item["seq"] for item in body["results"]]
    assert seqs == sorted(seqs)
    assert body["server_seq"] >= seqs[-1]
    assert body["server_hlc"]["node_id"] == "srv"

    second = await push_ops(client, token, ops)
    assert second.status_code == 200
    assert [item["status"] for item in second.json()["results"]] == ["duplicate"] * len(ops)
    assert [item["seq"] for item in second.json()["results"]] == seqs


async def test_rejects_unknown_envelope_field(client: AsyncClient) -> None:
    token, user_id, workspace_id = await _setup(client)
    op = make_op(
        op_id="op-extra",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-1",
        field="title",
        value="x",
        actor_id=user_id,
    )
    op["unexpected"] = True
    response = await push_ops(client, token, [op])
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_op_envelope"
    assert response.json()["error"]["details"]


async def test_actor_mismatch_is_rejected_per_op(client: AsyncClient) -> None:
    token, _user_id, workspace_id = await _setup(client)
    op = make_op(
        op_id="op-actor",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-2",
        field="__created",
        value="2026-11-09T10:00:00+08:00",
        actor_id="u-someone-else",
    )
    response = await push_ops(client, token, [op])
    assert response.status_code == 200
    result = response.json()["results"][0]
    assert result["status"] == "rejected"
    assert result["error"]["code"] == "actor_mismatch"


async def test_clock_skew_is_rejected_with_server_hlc(client: AsyncClient) -> None:
    token, user_id, workspace_id = await _setup(client)
    far_future = now_ms() + 30 * 60 * 1000
    op = make_op(
        op_id="op-future",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-3",
        field="__created",
        value="2026-11-09T10:00:00+08:00",
        actor_id=user_id,
        physical_ms=far_future,
    )
    response = await push_ops(client, token, [op])
    assert response.status_code == 200
    error = response.json()["results"][0]["error"]
    assert error["code"] == "hlc_too_far_ahead"
    assert error["retryable"] is False
    assert error["server_hlc"]["node_id"] == "srv"


async def test_entity_workspace_mismatch(client: AsyncClient) -> None:
    token, user_id, workspace_id = await _setup(client)
    list_id = f"{workspace_id}-today"
    await push_ops(
        client,
        token,
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-4",
            list_id=list_id,
            actor_id=user_id,
        ),
    )

    second_ws = "ws-second01"
    created = await push_ops(
        client,
        token,
        workspace_create_ops(workspace_id=second_ws, user_id=user_id),
    )
    assert all(item["status"] == "accepted" for item in created.json()["results"]), created.text

    op = make_op(
        op_id="op-cross-ws",
        workspace_id=second_ws,
        entity_type="task",
        entity_id="task-4",
        field="title",
        value="跨工作区",
        actor_id=user_id,
    )
    response = await push_ops(client, token, [op])
    assert response.status_code == 200
    assert response.json()["results"][0]["error"]["code"] == "entity_workspace_mismatch"


async def test_partial_rejection_keeps_valid_ops(client: AsyncClient) -> None:
    token, user_id, workspace_id = await _setup(client)
    list_id = f"{workspace_id}-today"
    await push_ops(
        client,
        token,
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-5",
            list_id=list_id,
            actor_id=user_id,
        ),
    )

    good = make_op(
        op_id="op-good",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-5",
        field="title",
        value="合法标题",
        actor_id=user_id,
    )
    bad = make_op(
        op_id="op-bad",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-5",
        field="not_a_field",
        value="x",
        actor_id=user_id,
    )
    response = await push_ops(client, token, [bad, good])
    assert response.status_code == 200
    results = response.json()["results"]
    assert results[0]["status"] == "rejected"
    assert results[0]["error"]["code"] == "unknown_field"
    assert results[1]["status"] == "accepted"


async def test_unknown_list_rejected(client: AsyncClient) -> None:
    token, user_id, workspace_id = await _setup(client)
    ops = task_create_ops(
        workspace_id=workspace_id,
        task_id="task-6",
        list_id="list-does-not-exist",
        actor_id=user_id,
    )
    response = await push_ops(client, token, ops)
    assert response.status_code == 200
    codes = [item.get("error", {}).get("code") for item in response.json()["results"]]
    assert "unknown_list" in codes
    assert "accepted" in [item["status"] for item in response.json()["results"]]


async def test_viewer_single_batch_returns_403(client: AsyncClient) -> None:
    owner = await register_user(client, username="owner1")
    owner_token = owner["tokens"]["access_token"]
    workspace_id = owner["default_workspace_id"]
    list_id = f"{workspace_id}-today"

    await push_ops(
        client,
        owner_token,
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-7",
            list_id=list_id,
            actor_id=owner["user"]["user_id"],
        ),
    )

    bob = await register_user(client, username="bob1", email="bob1@example.com")
    invited = await client.post(
        "/v1/workspace/invite",
        json={"workspace_id": workspace_id, "account": "bob1", "role": "viewer"},
        headers=auth(owner_token),
    )
    assert invited.status_code == 200, invited.text

    op = make_op(
        op_id="op-viewer",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-7",
        field="title",
        value="只读不能改",
        actor_id=bob["user"]["user_id"],
    )
    response = await push_ops(client, bob["tokens"]["access_token"], [op])
    assert response.status_code == 403
    body = response.json()
    assert body["error"]["code"] == "forbidden"
    assert body["results"][0]["error"]["code"] == "forbidden"
