"""设备级凭证测试：签发、续期轮换、越权、过期、吊销、限流。"""

from __future__ import annotations

from pathlib import Path

import pytest
from httpx import AsyncClient

from app.config import ServerSettings

from .conftest import auth, register_user


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
        # 凭证端点不依赖 MQTT，关掉 broker 让用例更快。
        dsh_mqtt_enabled=False,
        dsh_push_rate=0,
        dsh_device_refresh_rate=0,
    )


async def register_device(
    client: AsyncClient, token: str, *, platform: str = "android-ime"
) -> dict:
    response = await client.post(
        "/v1/dsh/devices/register",
        json={"name": "ime", "platform": platform},
        headers=auth(token),
    )
    assert response.status_code == 201, response.text
    return response.json()


async def issue(client: AsyncClient, token: str, device_id: str):
    return await client.post(f"/v1/dsh/devices/{device_id}/credential", headers=auth(token))


async def refresh(client: AsyncClient, device_id: str, credential: str):
    return await client.post(
        "/v1/dsh/devices/refresh",
        json={"device_id": device_id, "credential": credential},
    )


async def test_issue_returns_credential_only_once(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    response = await issue(client, token, device["device_id"])
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["device_id"] == device["device_id"]
    assert body["credential"].startswith("dshcred-")
    assert body["credential_expires_at"] > 0
    # 设备列表不泄露凭证哈希
    listing = await client.get("/v1/dsh/devices", headers=auth(token))
    assert "credential" not in str(listing.json())


async def test_cannot_issue_for_someone_elses_device(client: AsyncClient) -> None:
    alice = await register_user(client, username="alice")
    alice_token = alice["tokens"]["access_token"]
    device = await register_device(client, alice_token)

    bob = await register_user(client, username="bobby")
    bob_token = bob["tokens"]["access_token"]
    response = await issue(client, bob_token, device["device_id"])
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "device_not_found"


async def test_refresh_rotates_credential_and_returns_ticket(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    issued = (await issue(client, token, device["device_id"])).json()

    first = await refresh(client, device["device_id"], issued["credential"])
    assert first.status_code == 200, first.text
    body = first.json()
    assert body["device_id"] == device["device_id"]
    assert body["ticket"]
    assert body["credential"].startswith("dshcred-")
    assert body["credential"] != issued["credential"]
    assert body["mqtt"]["topic_in"].endswith(f"/{device['device_id']}/in")

    # 旧凭证立即失效
    stale = await refresh(client, device["device_id"], issued["credential"])
    assert stale.status_code == 401
    assert stale.json()["error"]["code"] == "invalid_credential"

    # 新凭证可用
    second = await refresh(client, device["device_id"], body["credential"])
    assert second.status_code == 200, second.text


async def test_refreshed_ticket_can_pull_messages(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    issued = (await issue(client, token, device["device_id"])).json()
    refreshed = (await refresh(client, device["device_id"], issued["credential"])).json()

    push = await client.post(
        "/v1/dsh/push",
        json={"kind": "notify", "device_id": device["device_id"], "payload": {"title": "hi"}},
        headers=auth(token),
    )
    assert push.status_code == 200, push.text

    headers = {
        "X-Dsh-Device-Id": device["device_id"],
        "X-Dsh-Ticket": refreshed["ticket"],
    }
    pulled = await client.get("/v1/dsh/pull", params={"since": 0}, headers=headers)
    assert pulled.status_code == 200, pulled.text
    assert [item["kind"] for item in pulled.json()["messages"]] == ["notify"]


async def test_refresh_rejects_wrong_credential_and_unknown_device(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    await issue(client, token, device["device_id"])

    wrong = await refresh(client, device["device_id"], "dshcred-not-the-real-one")
    assert wrong.status_code == 401
    assert wrong.json()["error"]["code"] == "invalid_credential"

    unknown = await refresh(client, "dsh-does-not-exist", "dshcred-whatever")
    assert unknown.status_code == 401
    assert unknown.json()["error"]["code"] == "invalid_credential"

    # 从未签发过凭证的设备也不能续期
    fresh = await register_device(client, token)
    response = await refresh(client, fresh["device_id"], "dshcred-whatever")
    assert response.status_code == 401


async def test_refresh_rejects_expired_credential(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    issued = (await issue(client, token, device["device_id"])).json()

    async def expire(conn) -> None:
        await conn.execute(
            "UPDATE dsh_devices SET credential_expires_at = 1 WHERE device_id = ?",
            (device["device_id"],),
        )

    await app.state.services.writer.submit(expire)
    response = await refresh(client, device["device_id"], issued["credential"])
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_credential"


async def test_revoke_clears_credential(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    issued = (await issue(client, token, device["device_id"])).json()

    revoked = await client.post(
        f"/v1/dsh/devices/{device['device_id']}/revoke", headers=auth(token)
    )
    assert revoked.status_code == 200
    assert revoked.json()["revoked"] is True

    response = await refresh(client, device["device_id"], issued["credential"])
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "device_revoked"


async def test_credential_cannot_access_account_apis(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    issued = (await issue(client, token, device["device_id"])).json()

    # 凭证不是 access token
    response = await client.get("/v1/dsh/devices", headers=auth(issued["credential"]))
    assert response.status_code == 401

    # 也不能当设备票据用
    headers = {
        "X-Dsh-Device-Id": device["device_id"],
        "X-Dsh-Ticket": issued["credential"],
    }
    pulled = await client.get("/v1/dsh/pull", params={"since": 0}, headers=headers)
    assert pulled.status_code == 401

    # 凭证只能走续期端点
    again = await refresh(client, device["device_id"], issued["credential"])
    assert again.status_code == 200


async def test_refresh_is_rate_limited(tmp_path: Path) -> None:
    from httpx import ASGITransport

    from app.main import create_app

    settings = ServerSettings(
        db_path=tmp_path / "server.sqlite3",
        jwt_secret="test-secret-" + "x" * 40,
        admin_token="admin-token",
        backup_dir=tmp_path / "backups",
        snapshot_every_ops=0,
        auth_rate_limit=0,
        log_level="warning",
        dsh_mqtt_enabled=False,
        dsh_push_rate=0,
        dsh_device_refresh_rate=1,
    )
    application = create_app(settings)
    async with application.router.lifespan_context(application):
        async with AsyncClient(
            transport=ASGITransport(app=application), base_url="http://test"
        ) as client:
            data = await register_user(client)
            token = data["tokens"]["access_token"]
            device = await register_device(client, token)
            issued = (await issue(client, token, device["device_id"])).json()

            first = await refresh(client, device["device_id"], issued["credential"])
            assert first.status_code == 200, first.text
            rotated = first.json()["credential"]

            second = await refresh(client, device["device_id"], rotated)
            assert second.status_code == 429
            assert second.json()["error"]["code"] == "rate_limited"
