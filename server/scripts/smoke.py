"""端到端冒烟：注册 → 同步 → 成员 → 快照/恢复 → 备份导出。

用法（先启动 uvicorn）：
    .\\.venv\\Scripts\\python.exe scripts\\smoke.py
    .\\.venv\\Scripts\\python.exe scripts\\smoke.py --base-url http://127.0.0.1:8000
"""

from __future__ import annotations

import argparse
import io
import secrets
import sys
import time
import zipfile
from datetime import datetime
from typing import Any

import httpx


def now_ms() -> int:
    return int(time.time() * 1000)


def rfc3339(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).astimezone().isoformat(timespec="seconds")


def make_op(
    *,
    op_id: str,
    workspace_id: str,
    entity_type: str,
    entity_id: str,
    field: str,
    value: Any,
    actor_id: str,
    physical_ms: int,
    counter: int = 0,
) -> dict[str, Any]:
    return {
        "op_id": op_id,
        "workspace_id": workspace_id,
        "entity_type": entity_type,
        "entity_id": entity_id,
        "actor_id": actor_id,
        "device_id": "win-smoke",
        "operation": "set",
        "field": field,
        "value": value,
        "hlc": {"physical_ms": physical_ms, "counter": counter, "node_id": "win-smoke"},
        "source": "human",
        "source_meta": {"model": None, "prompt_hash": None},
    }


def expect(response: httpx.Response, status: int, step: str) -> httpx.Response:
    if response.status_code != status:
        raise SystemExit(f"❌ {step} 失败：HTTP {response.status_code}\n{response.text}")
    print(f"✅ {step}")
    return response


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    base = args.base_url.rstrip("/") + "/v1"
    suffix = secrets.token_hex(3)
    alice = f"smoke_{suffix}"
    bob = f"smoke_bob_{suffix}"
    password = "password123"

    with httpx.Client(timeout=60.0) as client:
        health = expect(client.get(f"{base}/health"), 200, "健康检查")
        assert health.json()["status"] == "ok"

        registered = expect(
            client.post(
                f"{base}/auth/register",
                json={
                    "username": alice,
                    "password": password,
                    "email": f"{alice}@example.com",
                    "device_id": "win-smoke",
                },
            ),
            201,
            "注册并种子默认工作区",
        ).json()
        token = registered["tokens"]["access_token"]
        user_id = registered["user"]["user_id"]
        workspace_id = registered["default_workspace_id"]
        headers = {"Authorization": f"Bearer {token}"}

        pulled = expect(
            client.get(
                f"{base}/sync/pull",
                params={"workspace_id": workspace_id, "since_seq": 0},
                headers=headers,
            ),
            200,
            "拉取种子 op",
        ).json()
        assert pulled["ops"], "种子 op 不应为空"

        base_ms = now_ms()
        task_id = f"task-smoke-{suffix}"
        list_id = f"{workspace_id}-today"
        creation = [
            make_op(
                op_id=f"{task_id}-{index}",
                workspace_id=workspace_id,
                entity_type="task",
                entity_id=task_id,
                field=field,
                value=value,
                actor_id=user_id,
                physical_ms=base_ms,
                counter=index,
            )
            for index, (field, value) in enumerate(
                [
                    ("__created", rfc3339(base_ms)),
                    ("title", "冒烟任务"),
                    ("notes", ""),
                    ("priority", "mid"),
                    ("status", "todo"),
                    ("list_id", list_id),
                    ("order", 0.0),
                ]
            )
        ]
        pushed = expect(
            client.post(
                f"{base}/sync/push",
                json={"device_id": "win-smoke", "ops": creation},
                headers=headers,
            ),
            200,
            "推送任务创建 op",
        ).json()
        assert all(item["status"] == "accepted" for item in pushed["results"])

        duplicate = expect(
            client.post(
                f"{base}/sync/push",
                json={"device_id": "win-smoke", "ops": creation},
                headers=headers,
            ),
            200,
            "重复推送幂等",
        ).json()
        assert all(item["status"] == "duplicate" for item in duplicate["results"])

        snapshot = expect(
            client.post(
                f"{base}/history/snapshot",
                json={"workspace_id": workspace_id},
                headers=headers,
            ),
            201,
            "生成快照",
        ).json()
        assert snapshot["counts"]["tasks"] == 1

        change = make_op(
            op_id=f"{task_id}-title-change",
            workspace_id=workspace_id,
            entity_type="task",
            entity_id=task_id,
            field="title",
            value="冒烟任务（已修改）",
            actor_id=user_id,
            physical_ms=base_ms + 1000,
        )
        expect(
            client.post(
                f"{base}/sync/push",
                json={"device_id": "win-smoke", "ops": [change]},
                headers=headers,
            ),
            200,
            "修改任务标题",
        )

        timeline = expect(
            client.get(
                f"{base}/history/timeline",
                params={"workspace_id": workspace_id, "task_id": task_id},
                headers=headers,
            ),
            200,
            "读取历史时间线",
        ).json()
        assert any(entry["field"] == "title" for entry in timeline["entries"])

        dry = expect(
            client.post(
                f"{base}/history/restore",
                json={
                    "workspace_id": workspace_id,
                    "snapshot_id": snapshot["snapshot_id"],
                    "dry_run": True,
                },
                headers=headers,
            ),
            200,
            "恢复预览",
        ).json()
        assert dry["op_count"] >= 1

        expect(
            client.post(
                f"{base}/history/restore",
                json={
                    "workspace_id": workspace_id,
                    "snapshot_id": snapshot["snapshot_id"],
                    "dry_run": False,
                },
                headers=headers,
            ),
            200,
            "恢复到快照",
        )

        after = expect(
            client.post(
                f"{base}/history/snapshot",
                json={"workspace_id": workspace_id},
                headers=headers,
            ),
            201,
            "恢复后重新快照",
        ).json()
        projection = expect(
            client.get(
                f"{base}/history/snapshot",
                params={"workspace_id": workspace_id, "seq": after["seq"]},
                headers=headers,
            ),
            200,
            "读取快照投影",
        ).json()["projection"]
        tasks = {task["id"]: task for task in projection["tasks"]}
        assert tasks[task_id]["title"] == "冒烟任务", tasks[task_id]["title"]

        bob_registered = expect(
            client.post(
                f"{base}/auth/register",
                json={
                    "username": bob,
                    "password": password,
                    "email": f"{bob}@example.com",
                },
            ),
            201,
            "注册协作用户",
        ).json()
        invited = expect(
            client.post(
                f"{base}/workspace/invite",
                json={"workspace_id": workspace_id, "account": bob, "role": "editor"},
                headers=headers,
            ),
            200,
            "邀请成员",
        ).json()
        assert invited["role"] == "editor"

        members = expect(
            client.get(
                f"{base}/workspace/members",
                params={"workspace_id": workspace_id},
                headers=headers,
            ),
            200,
            "读取成员列表",
        ).json()["members"]
        assert {member["user_id"] for member in members} == {
            user_id,
            bob_registered["user"]["user_id"],
        }

        export = expect(
            client.get(
                f"{base}/backup/export",
                params={"workspace_id": workspace_id},
                headers=headers,
            ),
            200,
            "导出工作区备份",
        )
        archive = zipfile.ZipFile(io.BytesIO(export.content))
        assert {"manifest.json", "workspace/ops.jsonl", "workspace/projection.json"} <= set(
            archive.namelist()
        )
        manifest_raw = archive.read("manifest.json")
        assert b"access_token" not in manifest_raw
        assert b"password_hash" in manifest_raw  # 仅出现在 excluded 声明里
        print(f"   备份大小：{len(export.content)} 字节，op 条目已脱敏")

    print("\n🎉 冒烟通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
