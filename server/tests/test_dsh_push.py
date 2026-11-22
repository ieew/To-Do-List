"""dsh 推送通道测试：设备、nudge、pull/ack、吊销、主题 ACL、op_log 隔离。"""

from __future__ import annotations

import asyncio
import contextlib
import json
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import quote

import aiosqlite
import pytest
from amqtt.client import MQTTClient
from amqtt.errors import AMQTTError, ConnectError
from httpx import ASGITransport, AsyncClient

from app.config import ServerSettings
from app.features.dsh import usecase
from app.infra.rate_limit import SlidingWindowLimiter
from app.main import create_app

from .conftest import auth, register_user

# ---------------------------------------------------------------- 夹具


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
        dsh_mqtt_enabled=True,
        dsh_mqtt_bind="127.0.0.1",
        dsh_mqtt_port=0,
        dsh_push_rate=0,
    )


# ---------------------------------------------------------------- 辅助


def broker_port(app) -> int:
    runtime = app.state.services.dsh
    assert runtime is not None, "broker 没起来"
    return runtime.bound_port


def mqtt_uri(app, device_id: str, ticket: str) -> str:
    return (
        f"mqtt://{quote(device_id, safe='')}:{quote(ticket, safe='')}@127.0.0.1:{broker_port(app)}"
    )


async def register_device(
    client: AsyncClient, token: str, *, name: str = "phone", platform: str = "android"
) -> dict:
    response = await client.post(
        "/v1/dsh/devices/register",
        json={"name": name, "platform": platform},
        headers=auth(token),
    )
    assert response.status_code == 201, response.text
    return response.json()


async def new_identity(client: AsyncClient, *, username: str = "alice") -> tuple[str, str]:
    data = await register_user(client, username=username)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    return token, device["device_id"]


async def push(
    client: AsyncClient,
    token: str,
    *,
    kind: str = "notify",
    payload: object = None,
    device_id: str | None = None,
    ttl_s: int | None = None,
    msg_id: str | None = None,
    level: int | None = None,
):
    body: dict = {"kind": kind, "payload": {"title": "hello"} if payload is None else payload}
    if device_id is not None:
        body["device_id"] = device_id
    if ttl_s is not None:
        body["ttl_s"] = ttl_s
    if msg_id is not None:
        body["msg_id"] = msg_id
    if level is not None:
        body["level"] = level
    return await client.post("/v1/dsh/push", json=body, headers=auth(token))


async def pull(
    client: AsyncClient, token: str, device_id: str, *, since: int = 0, limit: int = 100
):
    return await client.get(
        "/v1/dsh/pull",
        params={"device_id": device_id, "since": since, "limit": limit},
        headers=auth(token),
    )


async def ack(client: AsyncClient, token: str, device_id: str, seqs: list[int]):
    return await client.post(
        "/v1/dsh/ack", json={"device_id": device_id, "seqs": seqs}, headers=auth(token)
    )


@asynccontextmanager
async def device_connection(
    app, device: dict, *, client_id: str = "c1", subscribe: bool = True
) -> AsyncIterator[MQTTClient]:
    mqtt = MQTTClient(client_id=client_id, config={"keep_alive": 10, "auto_reconnect": False})
    await mqtt.connect(mqtt_uri(app, device["device_id"], device["ticket"]), cleansession=True)
    try:
        if subscribe:
            await mqtt.subscribe([(device["mqtt"]["topic_in"], 1)])
        yield mqtt
    finally:
        with contextlib.suppress(Exception):
            await mqtt.disconnect()


async def next_payload(mqtt: MQTTClient, *, wait_s: float = 5.0) -> dict:
    message = await mqtt.deliver_message(timeout_duration=wait_s)
    assert message is not None and message.data is not None
    return json.loads(bytes(message.data).decode("utf-8"))


async def count_rows(app, sql: str) -> int:
    async with app.state.services.read.acquire() as conn:
        cursor = await conn.execute(sql)
        row = await cursor.fetchone()
        await cursor.close()
    return int(row[0]) if row else 0


# ---------------------------------------------------------------- 设备


async def test_register_device_returns_ticket_and_endpoint(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]

    device = await register_device(client, token)
    assert device["device_id"].startswith("dsh-")
    assert device["action"] == "created"
    assert device["ticket"]
    # 票据 TTL 是分钟级（默认 10 分钟），别把分钟当秒签成 10 秒
    assert device["ticket_expires_at"] - int(time.time() * 1000) > 5 * 60 * 1000

    endpoint = device["mqtt"]
    assert endpoint["online"] is True
    assert endpoint["port"] == broker_port(app)
    assert endpoint["username"] == device["device_id"]
    assert endpoint["topic_in"].endswith(f"/{device['device_id']}/in")
    assert endpoint["topic_out"].endswith(f"/{device['device_id']}/out")

    listing = await client.get("/v1/dsh/devices", headers=auth(token))
    assert listing.status_code == 200
    assert [item["device_id"] for item in listing.json()["devices"]] == [device["device_id"]]


async def test_register_same_device_refreshes_ticket(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    response = await client.post(
        "/v1/dsh/devices/register",
        json={"device_id": device["device_id"], "name": "改名了"},
        headers=auth(token),
    )
    assert response.status_code == 201
    assert response.json()["action"] == "updated"
    assert response.json()["device_id"] == device["device_id"]

    async with device_connection(app, response.json()):
        pass


async def test_cannot_register_someone_elses_device(client: AsyncClient) -> None:
    alice_data = await register_user(client, username="alice")
    alice_token = alice_data["tokens"]["access_token"]
    alice_device = await register_device(client, alice_token)

    bob_data = await register_user(client, username="bobby")
    bob_token = bob_data["tokens"]["access_token"]
    response = await client.post(
        "/v1/dsh/devices/register",
        json={"device_id": alice_device["device_id"]},
        headers=auth(bob_token),
    )
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "device_not_found"


# ---------------------------------------------------------------- nudge / 拉取 / 确认


async def test_push_triggers_nudge_and_pull_then_ack(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    async with device_connection(app, device) as mqtt:
        pushed = await push(client, token, device_id=device["device_id"], payload={"title": "喝水"})
        assert pushed.status_code == 200, pushed.text
        seq = pushed.json()["seq"]
        assert pushed.json()["stored"] is True
        assert pushed.json()["nudged"] is True

        nudge = await next_payload(mqtt)
        assert nudge["t"] == "nudge"
        assert nudge["seq"] == seq
        assert nudge["n"] == 1

    fetched = await pull(client, token, device["device_id"])
    assert fetched.status_code == 200, fetched.text
    body = fetched.json()
    assert [item["seq"] for item in body["messages"]] == [seq]
    assert body["messages"][0]["payload"] == {"title": "喝水"}
    assert body["messages"][0]["kind"] == "notify"
    assert body["next_seq"] == seq
    assert body["has_more"] is False

    first = await ack(client, token, device["device_id"], [seq])
    assert first.json()["acked"] == 1
    again = await ack(client, token, device["device_id"], [seq])
    assert again.json()["acked"] == 0  # 幂等

    after = await pull(client, token, device["device_id"], since=seq)
    assert after.json()["messages"] == []
    assert after.json()["next_seq"] == seq


async def test_pull_with_device_ticket_headers(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    await push(client, token, device_id=device["device_id"])

    headers = {"X-Dsh-Device-Id": device["device_id"], "X-Dsh-Ticket": device["ticket"]}
    response = await client.get("/v1/dsh/pull", headers=headers)
    assert response.status_code == 200, response.text
    assert len(response.json()["messages"]) == 1

    # 票据和 device 对不上 → 401
    bad = await client.get(
        "/v1/dsh/pull", headers={"X-Dsh-Device-Id": "dsh-other", "X-Dsh-Ticket": device["ticket"]}
    )
    assert bad.status_code == 401

    # 设备票据不能推送
    pushed = await client.post(
        "/v1/dsh/push", json={"kind": "notify", "payload": 1}, headers=headers
    )
    assert pushed.status_code == 401


async def test_push_idempotent_by_msg_id(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    first = await push(client, token, device_id=device["device_id"], msg_id="msg-fixed-1")
    second = await push(client, token, device_id=device["device_id"], msg_id="msg-fixed-1")
    assert first.status_code == 200 and second.status_code == 200
    assert second.json()["duplicate"] is True
    assert second.json()["seq"] == first.json()["seq"]

    body = (await pull(client, token, device["device_id"])).json()
    assert len(body["messages"]) == 1


async def test_broadcast_message_visible_to_all_devices(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    phone = await register_device(client, token, name="phone")
    tablet = await register_device(client, token, name="tablet")

    pushed = await push(client, token)  # 不带 device_id → 广播
    assert pushed.status_code == 200
    seq = pushed.json()["seq"]

    for device in (phone, tablet):
        body = (await pull(client, token, device["device_id"])).json()
        assert [item["seq"] for item in body["messages"]] == [seq]

    await ack(client, token, phone["device_id"], [seq])
    assert (await pull(client, token, phone["device_id"])).json()["messages"] == []
    # 手机确认不影响平板
    assert len((await pull(client, token, tablet["device_id"])).json()["messages"]) == 1


async def test_pull_requires_own_device(client: AsyncClient) -> None:
    alice_token, alice_device_id = await new_identity(client, username="alice")
    bob_data = await register_user(client, username="bobby")
    bob_token = bob_data["tokens"]["access_token"]

    response = await pull(client, bob_token, alice_device_id)
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "device_not_found"

    acked = await ack(client, bob_token, alice_device_id, [1])
    assert acked.status_code == 404

    pushed = await push(client, bob_token, device_id=alice_device_id)
    assert pushed.status_code == 404

    assert alice_token  # 夹具只用来造数据


async def test_ack_ignores_foreign_seqs(client: AsyncClient) -> None:
    alice_token, alice_device_id = await new_identity(client, username="alice")
    bob_token, bob_device_id = await new_identity(client, username="bobby")

    pushed = await push(client, alice_token, device_id=alice_device_id)
    seq = pushed.json()["seq"]

    response = await ack(client, bob_token, bob_device_id, [seq])
    assert response.status_code == 200
    assert response.json()["acked"] == 0
    # 消息仍是 alice 的待投消息
    assert len((await pull(client, alice_token, alice_device_id)).json()["messages"]) == 1


# ---------------------------------------------------------------- 过期 / 清理 / 限流


async def test_expired_message_not_pulled_and_cleaned(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    for index in range(3):
        await push(client, token, device_id=device["device_id"], payload={"i": index})

    async def expire_all(conn: aiosqlite.Connection) -> None:
        await conn.execute("UPDATE dsh_messages SET expires_at = 0")

    await app.state.services.writer.submit(expire_all)
    assert (await pull(client, token, device["device_id"])).json()["messages"] == []

    removed = await usecase.cleanup_expired(app.state.services)
    assert removed == 3
    assert await count_rows(app, "SELECT COUNT(*) FROM dsh_messages") == 0


async def test_payload_too_large_rejected(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    response = await push(
        client, token, device_id=device["device_id"], payload="x" * (usecase.MAX_PAYLOAD_BYTES + 10)
    )
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "payload_too_large"


async def test_push_rate_limited(client: AsyncClient) -> None:
    services = client.app.state.services
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    services.dsh_limiter = SlidingWindowLimiter(1)  # 每分钟只放一条
    try:
        assert (await push(client, token, device_id=device["device_id"])).status_code == 200
        limited = await push(client, token, device_id=device["device_id"])
        assert limited.status_code == 429
        assert limited.json()["error"]["code"] == "rate_limited"
    finally:
        services.dsh_limiter = None


# ---------------------------------------------------------------- 剪贴板（不落库）


async def test_clip_not_persisted_and_delivered_live(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    async with device_connection(app, device) as mqtt:
        response = await push(
            client, token, kind="clip", payload="复制的密码", device_id=device["device_id"]
        )
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["stored"] is False
        assert body["delivered"] == "live"

        message = await next_payload(mqtt)
        assert message["t"] == "clip"
        assert message["p"] == "复制的密码"
        assert message["id"] == body["msg_id"]

    assert await count_rows(app, "SELECT COUNT(*) FROM dsh_messages") == 0


async def test_clip_dropped_when_device_offline(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    response = await push(client, token, kind="clip", payload="x", device_id=device["device_id"])
    assert response.status_code == 200
    assert response.json()["delivered"] == "dropped"
    assert await count_rows(app, "SELECT COUNT(*) FROM dsh_messages") == 0


# ---------------------------------------------------------------- 鉴权 / ACL / 吊销


async def test_wrong_ticket_rejected(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    mqtt = MQTTClient(client_id="bad", config={"keep_alive": 10, "auto_reconnect": False})
    with pytest.raises(ConnectError):
        await mqtt.connect(
            mqtt_uri(app, device["device_id"], "not-a-real-ticket"), cleansession=True
        )

    # 匿名也连不上
    anonymous = MQTTClient(client_id="anon", config={"keep_alive": 10, "auto_reconnect": False})
    with pytest.raises(ConnectError):
        await anonymous.connect(f"mqtt://127.0.0.1:{broker_port(app)}", cleansession=True)


async def test_topic_acl(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    other = await register_device(client, token, name="other")

    async with device_connection(app, device, subscribe=False) as mqtt:
        # 只能订自己的 in
        denied = await mqtt.subscribe([(other["mqtt"]["topic_in"], 1)])
        assert denied[0] == 0x80  # SUBACK 失败码
        granted = await mqtt.subscribe([(device["mqtt"]["topic_in"], 1)])
        assert granted[0] in (0, 1, 2)

        # 不能往自己的 in 上发（那是服务端的方向）
        await mqtt.publish(device["mqtt"]["topic_in"], b"forged", qos=1)
        with pytest.raises(asyncio.TimeoutError):
            await mqtt.deliver_message(timeout_duration=1.0)


async def test_revoked_device_is_kicked_and_cannot_reconnect(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    async with device_connection(app, device) as mqtt:
        response = await client.post(
            f"/v1/dsh/devices/{device['device_id']}/revoke", headers=auth(token)
        )
        assert response.status_code == 200, response.text
        assert response.json()["kicked"] == 1
        # 连接被踢掉：要么收不到消息、要么直接报会话失效
        with pytest.raises((asyncio.TimeoutError, AMQTTError)):
            await mqtt.deliver_message(timeout_duration=1.0)

    reconnect = MQTTClient(client_id="c2", config={"keep_alive": 10, "auto_reconnect": False})
    with pytest.raises(ConnectError):
        await reconnect.connect(
            mqtt_uri(app, device["device_id"], device["ticket"]), cleansession=True
        )

    # 已吊销设备不能再注册回来
    again = await client.post(
        "/v1/dsh/devices/register",
        json={"device_id": device["device_id"]},
        headers=auth(token),
    )
    assert again.status_code == 409
    assert again.json()["error"]["code"] == "device_revoked"

    listed = await client.get("/v1/dsh/devices", headers=auth(token))
    assert listed.json()["devices"][0]["revoked"] is True


# ---------------------------------------------------------------- 状态 / admin / 隔离


async def test_state_reports_broker_and_pending(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    await push(client, token, device_id=device["device_id"])

    response = await client.get("/v1/dsh/state", headers=auth(token))
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["broker"]["online"] is True
    assert body["broker"]["bound_port"] == broker_port(app)
    assert body["devices"] == {"total": 1, "revoked": 0}
    assert body["messages"]["pending"] == 1
    assert "writer_queue_depth" in body


async def test_admin_token_push(client: AsyncClient) -> None:
    data = await register_user(client)
    user_id = data["user"]["user_id"]
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    response = await client.post(
        "/v1/dsh/push",
        json={"kind": "notify", "payload": {"from": "admin"}, "user_id": user_id},
        headers={"X-Admin-Token": "admin-token"},
    )
    assert response.status_code == 200, response.text
    assert response.json()["stored"] is True

    body = (await pull(client, token, device["device_id"])).json()
    assert body["messages"][0]["payload"] == {"from": "admin"}

    # admin 没指定 user_id → 400
    missing = await client.post(
        "/v1/dsh/push",
        json={"kind": "notify", "payload": 1},
        headers={"X-Admin-Token": "admin-token"},
    )
    assert missing.status_code == 400

    # 错误 admin token → 401
    wrong = await client.post(
        "/v1/dsh/push",
        json={"kind": "notify", "payload": 1, "user_id": user_id},
        headers={"X-Admin-Token": "nope"},
    )
    assert wrong.status_code == 401


async def test_push_does_not_touch_op_log(client: AsyncClient, app) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)
    before = await count_rows(app, "SELECT COUNT(*) FROM op_log")

    await push(client, token, device_id=device["device_id"])
    await push(client, token)
    await pull(client, token, device["device_id"])

    assert await count_rows(app, "SELECT COUNT(*) FROM op_log") == before


async def test_http_channel_works_with_broker_disabled(tmp_path: Path) -> None:
    """S0 验收：broker 关掉也能纯 HTTP 收发（nudged=False，靠设备自己轮询）。"""
    settings = ServerSettings(
        db_path=tmp_path / "no-broker.sqlite3",
        jwt_secret="test-secret-" + "x" * 40,
        backup_dir=tmp_path / "backups",
        snapshot_every_ops=0,
        auth_rate_limit=0,
        log_level="warning",
        dsh_mqtt_enabled=False,
    )
    application = create_app(settings)
    async with application.router.lifespan_context(application):
        transport = ASGITransport(app=application)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            data = await register_user(client)
            token = data["tokens"]["access_token"]
            device = await register_device(client, token)
            assert device["mqtt"]["enabled"] is False
            assert device["mqtt"]["online"] is False

            pushed = await push(client, token, device_id=device["device_id"])
            assert pushed.status_code == 200, pushed.text
            assert pushed.json()["nudged"] is False

            body = (await pull(client, token, device["device_id"])).json()
            assert [item["seq"] for item in body["messages"]] == [pushed.json()["seq"]]

            state = (await client.get("/v1/dsh/state", headers=auth(token))).json()
            assert state["broker"]["enabled"] is False
            assert state["broker"]["online"] is False


async def test_push_level_merged_into_payload(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    for level in (0, 3, 7):
        pushed = await push(client, token, device_id=device["device_id"], level=level)
        assert pushed.status_code == 200, pushed.text
        assert pushed.json()["level"] == level

    body = (await pull(client, token, device["device_id"])).json()
    levels = [item["payload"]["level"] for item in body["messages"]]
    # 等级只影响客户端提醒方式，0 级同样投递、同样在待投列表里
    assert levels == [0, 3, 7]


async def test_push_level_validation(client: AsyncClient) -> None:
    data = await register_user(client)
    token = data["tokens"]["access_token"]
    device = await register_device(client, token)

    too_high = await push(client, token, device_id=device["device_id"], level=8)
    assert too_high.status_code == 400

    not_object = await push(client, token, device_id=device["device_id"], payload="纯文本", level=5)
    assert not_object.status_code == 400
    assert not_object.json()["error"]["code"] == "invalid_request"
