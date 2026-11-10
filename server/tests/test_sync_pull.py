"""增量拉取测试。"""

from __future__ import annotations

from httpx import AsyncClient

from .conftest import pull_ops, register_user


async def test_pull_seed_data_and_cursor(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    workspace_id = data["default_workspace_id"]

    first = await pull_ops(client, token, workspace_id=workspace_id)
    assert first.status_code == 200
    body = first.json()
    assert body["ops"]
    assert body["next_seq"] == body["ops"][-1]["seq"]
    assert body["has_more"] is False
    assert all(op["workspace_id"] == workspace_id for op in body["ops"])
    assert body["server_seq"] >= body["next_seq"]
    assert body["server_hlc"]["node_id"] == "srv"

    cursor = body["next_seq"]
    empty = await pull_ops(client, token, workspace_id=workspace_id, since_seq=cursor)
    assert empty.status_code == 200
    assert empty.json()["ops"] == []
    assert empty.json()["next_seq"] == cursor


async def test_pull_pagination(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    workspace_id = data["default_workspace_id"]

    collected: list[dict] = []
    cursor = 0
    for _ in range(30):
        response = await pull_ops(
            client, token, workspace_id=workspace_id, since_seq=cursor, limit=5
        )
        assert response.status_code == 200
        body = response.json()
        collected.extend(body["ops"])
        cursor = body["next_seq"]
        if not body["has_more"]:
            break
    else:  # pragma: no cover - 防御性
        raise AssertionError("分页未在合理轮次内结束")

    seqs = [op["seq"] for op in collected]
    assert seqs == sorted(seqs)
    assert len(set(seqs)) == len(seqs)
    assert len(collected) > 10


async def test_pull_without_workspace_filter(client: AsyncClient) -> None:
    data = await register_user(client)
    response = await pull_ops(client, data["tokens"]["access_token"])
    assert response.status_code == 200
    assert response.json()["ops"]


async def test_pull_rejects_non_member_and_unknown_workspace(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice", email="alice@example.com")
    bob = await register_user(client, username="bob", email="bob@example.com")

    denied = await pull_ops(
        client,
        bob["tokens"]["access_token"],
        workspace_id=alice["default_workspace_id"],
    )
    assert denied.status_code == 403
    assert denied.json()["error"]["code"] == "workspace_access_revoked"

    missing = await pull_ops(client, bob["tokens"]["access_token"], workspace_id="ws-not-exist")
    assert missing.status_code == 404
    assert missing.json()["error"]["code"] == "workspace_not_found"


async def test_pull_invalid_limit(client: AsyncClient) -> None:
    data = await register_user(client)
    response = await client.get(
        "/v1/sync/pull",
        params={"limit": 5000},
        headers={"Authorization": f"Bearer {data['tokens']['access_token']}"},
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_request"
