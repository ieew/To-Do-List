"""成员管理接口测试。"""

from __future__ import annotations

from httpx import AsyncClient

from .conftest import auth, pull_ops, register_user


async def test_invite_unknown_account(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice", email="alice@example.com")
    response = await client.post(
        "/v1/workspace/invite",
        json={
            "workspace_id": alice["default_workspace_id"],
            "account": "nobody@example.com",
            "role": "editor",
        },
        headers=auth(alice["tokens"]["access_token"]),
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "account_not_found"


async def test_invite_list_and_roles(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice", email="alice@example.com")
    bob = await register_user(client, username="bob", email="bob@example.com")
    token = alice["tokens"]["access_token"]
    workspace_id = alice["default_workspace_id"]

    invited = await client.post(
        "/v1/workspace/invite",
        json={"workspace_id": workspace_id, "account": "bob@example.com", "role": "editor"},
        headers=auth(token),
    )
    assert invited.status_code == 200
    assert invited.json()["role"] == "editor"

    members = await client.get(
        "/v1/workspace/members",
        params={"workspace_id": workspace_id},
        headers=auth(token),
    )
    assert members.status_code == 200
    rows = members.json()["members"]
    assert [row["role"] for row in rows] == ["owner", "editor"]
    assert rows[0]["user_id"] == alice["user"]["user_id"]
    assert rows[0]["username"] == "alice"

    bob_token = bob["tokens"]["access_token"]
    forbidden = await client.post(
        "/v1/workspace/invite",
        json={"workspace_id": workspace_id, "account": "alice", "role": "viewer"},
        headers=auth(bob_token),
    )
    assert forbidden.status_code == 403
    assert forbidden.json()["error"]["code"] == "forbidden"

    bob_pull = await pull_ops(client, bob_token, workspace_id=workspace_id)
    assert bob_pull.status_code == 200
    assert bob_pull.json()["ops"]


async def test_last_owner_and_self_removal_guards(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice", email="alice@example.com")
    token = alice["tokens"]["access_token"]
    workspace_id = alice["default_workspace_id"]
    user_id = alice["user"]["user_id"]

    demote = await client.patch(
        "/v1/workspace/role",
        json={"workspace_id": workspace_id, "user_id": user_id, "role": "viewer"},
        headers=auth(token),
    )
    assert demote.status_code == 409
    assert demote.json()["error"]["code"] == "last_owner"

    remove_self = await client.post(
        "/v1/workspace/remove",
        json={"workspace_id": workspace_id, "user_id": user_id},
        headers=auth(token),
    )
    assert remove_self.status_code == 409
    assert remove_self.json()["error"]["code"] == "cannot_remove_self"


async def test_remove_member_revokes_access(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice", email="alice@example.com")
    bob = await register_user(client, username="bob", email="bob@example.com")
    token = alice["tokens"]["access_token"]
    workspace_id = alice["default_workspace_id"]
    bob_id = bob["user"]["user_id"]

    await client.post(
        "/v1/workspace/invite",
        json={"workspace_id": workspace_id, "account": "bob", "role": "viewer"},
        headers=auth(token),
    )
    removed = await client.post(
        "/v1/workspace/remove",
        json={"workspace_id": workspace_id, "user_id": bob_id},
        headers=auth(token),
    )
    assert removed.status_code == 204

    members = await client.get(
        "/v1/workspace/members",
        params={"workspace_id": workspace_id},
        headers=auth(token),
    )
    assert [row["username"] for row in members.json()["members"]] == ["alice"]

    denied = await pull_ops(client, bob["tokens"]["access_token"], workspace_id=workspace_id)
    assert denied.status_code == 403
    assert denied.json()["error"]["code"] == "workspace_access_revoked"


async def test_role_change_requires_existing_member(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice", email="alice@example.com")
    bob = await register_user(client, username="bob", email="bob@example.com")
    response = await client.patch(
        "/v1/workspace/role",
        json={
            "workspace_id": alice["default_workspace_id"],
            "user_id": bob["user"]["user_id"],
            "role": "editor",
        },
        headers=auth(alice["tokens"]["access_token"]),
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "member_not_found"
