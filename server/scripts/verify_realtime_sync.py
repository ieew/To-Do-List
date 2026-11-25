"""端到端验证：push 一条 op 后，同账号其他在线设备是否能收到 sync 信号。

用法（服务端已启动，dsh broker 可连）：
    python scripts/verify_realtime_sync.py http://127.0.0.1:8011 127.0.0.1:1883
"""

from __future__ import annotations

import asyncio
import json
import sys
import time
import uuid

import httpx
from amqtt.client import MQTTClient

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8011"
MQTT_ADDR = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1:1883"


async def main() -> int:
    suffix = uuid.uuid4().hex[:8]
    async with httpx.AsyncClient(base_url=BASE, timeout=10) as http:
        r = await http.post(
            "/v1/auth/register",
            json={
                "username": f"verify_{suffix}",
                "password": "Passw0rd!123",
                "workspace_name": "验证库",
            },
        )
        r.raise_for_status()
        body = r.json()
        token = body["tokens"]["access_token"]
        workspace_id = body["default_workspace_id"]
        user_id = body["user"]["user_id"]
        print(f"注册 OK user_id={user_id} ws={workspace_id}")

        headers = {"Authorization": f"Bearer {token}"}
        devices = {}
        for name in ("A", "B"):
            r = await http.post(
                "/v1/dsh/devices/register",
                json={"name": f"verify-{name}", "platform": "test"},
                headers=headers,
            )
            r.raise_for_status()
            devices[name] = r.json()
            print(f"设备 {name} -> {devices[name]['device_id']} action={devices[name]['action']}")

        # 设备 B 连上 broker，订阅自己的 in 主题
        dev_b = devices["B"]
        topic_in = f"dsh/v1/{user_id}/dev/{dev_b['device_id']}/in"
        client = MQTTClient(client_id=f"verifyB-{suffix}", config={"auto_reconnect": False})
        await client.connect(f"mqtt://{dev_b['device_id']}:{dev_b['ticket']}@{MQTT_ADDR}")
        await client.subscribe([(topic_in, 1)])
        print(f"设备 B 已订阅 {topic_in}")

        # 等一下让 broker 完成绑定
        await asyncio.sleep(0.5)

        # 用登录 token push 两条 task op（首条必须是 __created，否则被拒 entity_not_created）
        import datetime

        now_ms = int(time.time() * 1000)
        created_at = datetime.datetime.now(datetime.UTC).astimezone().isoformat()
        base = {
            "workspace_id": workspace_id,
            "entity_type": "task",
            "entity_id": f"task-{suffix}",
            "actor_id": user_id,
            "device_id": devices["A"]["device_id"],
            "operation": "set",
            "hlc": {"physical_ms": now_ms, "counter": 0, "node_id": devices["A"]["device_id"]},
            "source": "human",
        }
        ops = [
            {**base, "op_id": f"op-{suffix}-c", "field": "__created", "value": created_at},
            {**base, "op_id": f"op-{suffix}-t", "field": "title", "value": "验证实时同步"},
        ]
        r = await http.post(
            "/v1/sync/push",
            json={"device_id": devices["A"]["device_id"], "ops": ops},
            headers=headers,
        )
        print(f"push HTTP {r.status_code}: {r.text[:300]}")
        r.raise_for_status()

        try:
            message = await asyncio.wait_for(client.deliver_message(), timeout=5)
        except TimeoutError:
            print("❌ 5 秒内没有收到任何 MQTT 消息")
            await client.disconnect()
            return 1

        raw = bytes(message.publish_packet.payload.data).decode("utf-8")
        print(f"✅ 收到 MQTT 消息 topic={message.publish_packet.variable_header.topic_name}")
        print(f"   payload={raw}")
        payload = json.loads(raw)
        ok = payload.get("t") == "sync" and payload.get("seq", 0) >= 1
        print("✅ 信号格式正确" if ok else "❌ 信号格式不符：期望 t=sync 且 seq>=1")
        await client.disconnect()
        return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
