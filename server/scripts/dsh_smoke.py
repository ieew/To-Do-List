"""dsh 推送通道端到端冒烟：注册设备 → 订阅 nudge → push → pull → ack。

用法（先启动 uvicorn，账号不存在时加 --register）：
    .venv/bin/python scripts/dsh_smoke.py --username alice --password password123 --register
    .venv/bin/python scripts/dsh_smoke.py --base-url http://127.0.0.1:8000 --no-mqtt

--no-mqtt 只验证纯 HTTP 通道（broker 关掉或端口不通时用）。
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import sys
from typing import Any
from urllib.parse import quote

import httpx

DEFAULT_KIND = "notify"


def _step(ok: bool, text: str) -> None:
    print(f"{'✓' if ok else '✗'} {text}")


async def _login(client: httpx.AsyncClient, username: str, password: str) -> str:
    response = await client.post(
        "/v1/auth/login", json={"username": username, "password": password}
    )
    if response.status_code == 200:
        return response.json()["tokens"]["access_token"]
    if response.status_code != 401:
        raise SystemExit(f"登录失败：{response.status_code} {response.text}")
    return ""


async def _register(client: httpx.AsyncClient, username: str, password: str) -> str:
    response = await client.post(
        "/v1/auth/register", json={"username": username, "password": password}
    )
    if response.status_code != 201:
        raise SystemExit(f"注册失败：{response.status_code} {response.text}")
    return response.json()["tokens"]["access_token"]


async def main() -> int:
    parser = argparse.ArgumentParser(description="dsh 推送通道冒烟")
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--username", default="dsh-smoke")
    parser.add_argument("--password", default="password123")
    parser.add_argument("--register", action="store_true", help="账号不存在时先注册")
    parser.add_argument("--device-name", default="smoke")
    parser.add_argument("--no-mqtt", action="store_true", help="跳过 MQTT，只验 HTTP 通道")
    parser.add_argument("--cafile", default=None, help="broker 用 TLS 时的 CA 文件")
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    async with httpx.AsyncClient(base_url=args.base_url, timeout=30) as client:
        token = await _login(client, args.username, args.password)
        if not token:
            if not args.register:
                raise SystemExit("登录失败：账号不存在。加 --register 先注册，或检查用户名/密码")
            token = await _register(client, args.username, args.password)
        headers = {"Authorization": f"Bearer {token}"}
        _step(True, f"登录/注册成功：{args.username}")

        health = await client.get("/v1/health")
        _step(health.status_code == 200, f"健康检查：{health.status_code}")

        registered = await client.post(
            "/v1/dsh/devices/register",
            json={"name": args.device_name, "platform": "smoke"},
            headers=headers,
        )
        if registered.status_code != 201:
            raise SystemExit(f"注册设备失败：{registered.status_code} {registered.text}")
        device: dict[str, Any] = registered.json()
        endpoint = device["mqtt"]
        _step(True, f"设备已注册：{device['device_id']}（broker online={endpoint['online']}）")

        topic_in = endpoint["topic_in"]
        mqtt = None
        if not args.no_mqtt:
            mqtt = await _connect(endpoint, device["ticket"], args.cafile)
            if mqtt is None:
                _step(False, "MQTT 连接失败，改用 --no-mqtt 只验 HTTP 通道")
                return 1
            await mqtt.subscribe([(topic_in, 1)])
            _step(True, f"已连接 broker 并订阅 {topic_in}")

        try:
            pushed = await client.post(
                "/v1/dsh/push",
                json={
                    "kind": DEFAULT_KIND,
                    "payload": {"title": "冒烟测试", "body": "来自 dsh_smoke.py"},
                    "device_id": device["device_id"],
                },
                headers=headers,
            )
            if pushed.status_code != 200:
                raise SystemExit(f"投递失败：{pushed.status_code} {pushed.text}")
            pushed_body = pushed.json()
            seq = pushed_body["seq"]
            _step(True, f"投递成功：seq={seq} nudged={pushed_body['nudged']}")

            if mqtt is not None:
                try:
                    message = await mqtt.deliver_message(timeout_duration=args.timeout)
                except Exception as exc:  # noqa: BLE001 - 超时/断连都是失败
                    _step(False, f"{args.timeout}s 内没收到 nudge：{exc!r}")
                    return 1
                nudge = json.loads(bytes(message.data).decode("utf-8"))
                ok = nudge.get("t") == "nudge" and nudge.get("seq") == seq
                _step(ok, f"收到 nudge：{nudge}")
                if not ok:
                    return 1

            pulled = await client.get(
                "/v1/dsh/pull",
                params={"device_id": device["device_id"], "since": 0, "limit": 20},
                headers=headers,
            )
            if pulled.status_code != 200:
                raise SystemExit(f"拉取失败：{pulled.status_code} {pulled.text}")
            messages = pulled.json()["messages"]
            hit = [item for item in messages if item["seq"] == seq]
            _step(bool(hit), f"pull 拿到 {len(messages)} 条，其中包含 seq={seq}：{bool(hit)}")
            if not hit:
                return 1

            acked = await client.post(
                "/v1/dsh/ack",
                json={"device_id": device["device_id"], "seqs": [seq]},
                headers=headers,
            )
            _step(
                acked.status_code == 200 and acked.json()["acked"] == 1,
                f"ack 成功：{acked.json()}",
            )

            state = await client.get("/v1/dsh/state", headers=headers)
            _step(
                state.status_code == 200, f"state：{json.dumps(state.json(), ensure_ascii=False)}"
            )
        finally:
            if mqtt is not None:
                with contextlib.suppress(Exception):  # 断开失败不影响结论
                    await mqtt.disconnect()

    print("\n全部通过：nudge→pull→ack 链路可用。")
    return 0


async def _connect(endpoint: dict[str, Any], ticket: str, cafile: str | None):
    try:
        from amqtt.client import MQTTClient
    except ImportError:
        print("✗ 未安装 amqtt：pip install amqtt（或用 --no-mqtt）")
        return None

    scheme = "mqtts" if endpoint["tls"] else "mqtt"
    uri = (
        f"{scheme}://{quote(endpoint['username'], safe='')}:{quote(ticket, safe='')}"
        f"@{endpoint['host']}:{endpoint['port']}"
    )
    mqtt = MQTTClient(client_id="dsh-smoke", config={"keep_alive": 15, "auto_reconnect": False})
    try:
        await mqtt.connect(uri, cleansession=True, cafile=cafile)
    except Exception as exc:  # noqa: BLE001 - 连接失败要把原因打出来
        print(f"✗ MQTT 连接失败：{exc!r}")
        return None
    return mqtt


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
