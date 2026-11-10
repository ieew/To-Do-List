"""幂等性与批次事务语义。"""

from __future__ import annotations

from httpx import AsyncClient

from .conftest import make_op, pull_ops, push_ops, register_user, task_create_ops


async def test_duplicate_op_id_is_not_written_twice(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    user_id = data["user"]["user_id"]
    workspace_id = data["default_workspace_id"]
    list_id = f"{workspace_id}-today"

    await push_ops(
        client,
        token,
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-1",
            list_id=list_id,
            actor_id=user_id,
        ),
    )

    first = make_op(
        op_id="op-once",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-1",
        field="title",
        value="第一次",
        actor_id=user_id,
    )
    second = {**first, "value": "第二次（同 op_id 应被忽略）"}

    assert (await push_ops(client, token, [first])).status_code == 200
    duplicate = await push_ops(client, token, [second])
    assert duplicate.status_code == 200
    assert duplicate.json()["results"][0]["status"] == "duplicate"

    pulled = await pull_ops(client, token, workspace_id=workspace_id)
    matches = [op for op in pulled.json()["ops"] if op["op_id"] == "op-once"]
    assert len(matches) == 1
    assert matches[0]["value"] == "第一次"


async def test_invalid_op_does_not_abort_valid_ones(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    user_id = data["user"]["user_id"]
    workspace_id = data["default_workspace_id"]
    list_id = f"{workspace_id}-today"

    await push_ops(
        client,
        token,
        task_create_ops(
            workspace_id=workspace_id,
            task_id="task-2",
            list_id=list_id,
            actor_id=user_id,
        ),
    )

    good = make_op(
        op_id="op-good-idem",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-2",
        field="notes",
        value="有效备注",
        actor_id=user_id,
    )
    bad = make_op(
        op_id="op-bad-idem",
        workspace_id=workspace_id,
        entity_type="task",
        entity_id="task-2",
        field="title",
        value="",
        actor_id=user_id,
    )
    response = await push_ops(client, token, [good, bad])
    assert response.status_code == 200
    results = response.json()["results"]
    assert results[0]["status"] == "accepted"
    assert results[1]["status"] == "rejected"
    assert results[1]["error"]["code"] == "invalid_value"

    pulled = await pull_ops(client, token, workspace_id=workspace_id)
    ids = {op["op_id"] for op in pulled.json()["ops"]}
    assert "op-good-idem" in ids
    assert "op-bad-idem" not in ids
