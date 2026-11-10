"""鉴权链路测试。"""

from __future__ import annotations

from httpx import AsyncClient

from .conftest import auth, register_user


async def test_register_login_me(client: AsyncClient) -> None:
    data = await register_user(client, username="alice", email="alice@example.com")
    assert data["user"]["username"] == "alice"
    assert data["user"]["email"] == "alice@example.com"
    assert data["default_workspace_id"].startswith("ws-")
    assert data["tokens"]["access_token"]

    token = data["tokens"]["access_token"]
    me = await client.get("/v1/auth/me", headers=auth(token))
    assert me.status_code == 200
    assert me.json()["user"]["user_id"] == data["user"]["user_id"]

    workspaces = await client.get("/v1/workspaces", headers=auth(token))
    assert workspaces.status_code == 200
    items = workspaces.json()["workspaces"]
    assert len(items) == 1
    assert items[0]["role"] == "owner"
    assert items[0]["member_count"] == 1

    login = await client.post(
        "/v1/auth/login", json={"username": "alice", "password": "password123"}
    )
    assert login.status_code == 200
    assert login.json()["workspaces"][0]["workspace_id"] == data["default_workspace_id"]


async def test_register_conflicts(client: AsyncClient) -> None:
    await register_user(client, username="alice", email="alice@example.com")

    duplicate_name = await client.post(
        "/v1/auth/register", json={"username": "ALICE", "password": "password123"}
    )
    assert duplicate_name.status_code == 409
    assert duplicate_name.json()["error"]["code"] == "username_taken"

    duplicate_email = await client.post(
        "/v1/auth/register",
        json={"username": "bob", "password": "password123", "email": "ALICE@example.com"},
    )
    assert duplicate_email.status_code == 409
    assert duplicate_email.json()["error"]["code"] == "email_taken"


async def test_register_validation(client: AsyncClient) -> None:
    weak = await client.post("/v1/auth/register", json={"username": "ab", "password": "short"})
    assert weak.status_code == 400
    assert weak.json()["error"]["code"] == "invalid_username"

    weak_password = await client.post(
        "/v1/auth/register", json={"username": "alice", "password": "short"}
    )
    assert weak_password.status_code == 400
    assert weak_password.json()["error"]["code"] == "weak_password"

    bad_email = await client.post(
        "/v1/auth/register",
        json={"username": "alice", "password": "password123", "email": "not-an-email"},
    )
    assert bad_email.status_code == 400
    assert bad_email.json()["error"]["code"] == "invalid_email"


async def test_login_invalid_credentials(client: AsyncClient) -> None:
    await register_user(client, username="alice")

    wrong = await client.post(
        "/v1/auth/login", json={"username": "alice", "password": "wrong-password"}
    )
    assert wrong.status_code == 401
    assert wrong.json()["error"]["code"] == "invalid_credentials"

    missing = await client.post(
        "/v1/auth/login", json={"username": "nobody", "password": "password123"}
    )
    assert missing.status_code == 401
    assert missing.json()["error"]["code"] == "invalid_credentials"


async def test_refresh_rotation_and_reuse_detection(client: AsyncClient) -> None:
    data = await register_user(client)
    first_refresh = data["tokens"]["refresh_token"]

    refreshed = await client.post("/v1/auth/refresh", json={"refresh_token": first_refresh})
    assert refreshed.status_code == 200
    second_refresh = refreshed.json()["tokens"]["refresh_token"]
    assert second_refresh != first_refresh

    reuse = await client.post("/v1/auth/refresh", json={"refresh_token": first_refresh})
    assert reuse.status_code == 401
    assert reuse.json()["error"]["code"] == "invalid_refresh_token"

    # 复用检测会吊销整族，因此新令牌也失效。
    family_revoked = await client.post("/v1/auth/refresh", json={"refresh_token": second_refresh})
    assert family_revoked.status_code == 401


async def test_logout_revokes_refresh_token(client: AsyncClient) -> None:
    data = await register_user(client)
    refresh_token = data["tokens"]["refresh_token"]

    logout = await client.post("/v1/auth/logout", json={"refresh_token": refresh_token})
    assert logout.status_code == 204

    blocked = await client.post("/v1/auth/refresh", json={"refresh_token": refresh_token})
    assert blocked.status_code == 401


async def test_requires_bearer_token(client: AsyncClient) -> None:
    response = await client.get("/v1/auth/me")
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"

    bad = await client.get("/v1/auth/me", headers={"Authorization": "Bearer nonsense"})
    assert bad.status_code == 401
    assert bad.json()["error"]["code"] == "invalid_access_token"


async def test_health(client: AsyncClient) -> None:
    response = await client.get("/v1/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["protocol_version"] == "1.0"
