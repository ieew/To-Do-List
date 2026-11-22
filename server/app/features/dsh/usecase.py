"""dsh 推送通道用例：注册设备、投递、拉取、确认、状态、清理。

可靠性约定（见 dsh-ime/doc/SERVER-INTEGRATION.md §1、§4）：

- 数据库是唯一真相源；MQTT 只推一个极小的 nudge，设备收到后主动 pull 再 ack。
- 事务回调里只做 SQL，一个网络 `await` 都不许有；发 nudge 一律在事务之外。
- 推送表与 `op_log` 物理隔离，本模块不读写任何业务表。
"""

from __future__ import annotations

import json

import aiosqlite

from app.core_domain.errors import AppError
from app.features.dsh import port
from app.features.dsh.port import ServicesPort as Services
from app.features.dsh.schemas import ClipRequest, PushRequest, RegisterDeviceRequest
from app.features.dsh.ticket import mint_ticket
from app.infra.clock import now_ms
from app.infra.security import new_id

MAX_PAYLOAD_BYTES = 32 * 1024
_TOUCH_INTERVAL_MS = 60_000
_MQTT_TOPIC_PREFIX = "dsh/v1"


# ---------------------------------------------------------------- 设备


async def register_device(services: Services, user_id: str, payload: RegisterDeviceRequest) -> dict:
    settings = services.settings
    at = now_ms()
    device_id = payload.device_id or new_id("dsh")
    name = payload.name.strip()[:64]
    platform = payload.platform.strip()[:32]

    async def txn(conn: aiosqlite.Connection) -> str:
        row = await port.get_device(conn, device_id)
        if row is None:
            await port.insert_device(
                conn,
                device_id=device_id,
                user_id=user_id,
                name=name,
                platform=platform,
                at_ms=at,
            )
            return "created"
        if str(row["user_id"]) != user_id:
            raise AppError("device_not_found")
        if int(row["revoked"]):
            raise AppError("device_revoked")
        await port.update_device_meta(
            conn,
            device_id=device_id,
            name=name or row["name"],
            platform=platform or row["platform"],
        )
        await port.touch_device(conn, device_id=device_id, at_ms=at)
        return "updated"

    action = await services.writer.submit(txn)
    ticket, expires_at = mint_ticket(
        settings.jwt_secret,
        user_id=user_id,
        device_id=device_id,
        # 配置项单位是分钟，票据按秒签
        ttl_seconds=settings.dsh_ticket_ttl_min * 60,
        now_ms=at,
    )
    return {
        "device_id": device_id,
        "action": action,
        "ticket": ticket,
        "ticket_expires_at": expires_at,
        "mqtt": _mqtt_endpoint(services, user_id, device_id),
    }


async def list_devices(services: Services, user_id: str) -> dict:
    runtime = services.dsh
    async with services.read.acquire() as conn:
        rows = await port.list_devices(conn, user_id)
    return {
        "devices": [
            {
                "device_id": row["device_id"],
                "name": row["name"],
                "platform": row["platform"],
                "revoked": bool(row["revoked"]),
                "created_at": int(row["created_at"]),
                "last_seen_at": int(row["last_seen_at"]) if row["last_seen_at"] else None,
                "online": bool(runtime and runtime.is_online(str(row["device_id"]))),
            }
            for row in rows
        ]
    }


async def revoke_device(services: Services, user_id: str, device_id: str) -> dict:
    async def txn(conn: aiosqlite.Connection) -> bool:
        row = await port.get_device(conn, device_id)
        if row is None or str(row["user_id"]) != user_id:
            raise AppError("device_not_found")
        return await port.mark_revoked(conn, device_id)

    changed = await services.writer.submit(txn)
    runtime = services.dsh
    kicked = 0
    if runtime is not None:
        # 内存缓存立即生效（票据还在有效期内也连不上了），并踢掉在线连接。
        kicked = await runtime.revoke(device_id)
    return {"device_id": device_id, "revoked": True, "changed": changed, "kicked": kicked}


def _mqtt_endpoint(services: Services, user_id: str, device_id: str) -> dict:
    settings = services.settings
    runtime = services.dsh
    host = settings.dsh_mqtt_public_host.strip() or settings.dsh_mqtt_bind.strip()
    if host in {"", "0.0.0.0", "::", "[::]"}:  # noqa: S104 - 绑定通配地址时对外报回环
        host = "127.0.0.1"
    return {
        "enabled": bool(settings.dsh_mqtt_enabled),
        "online": bool(runtime is not None and runtime.online),
        "host": host,
        "port": runtime.bound_port if runtime is not None else settings.dsh_mqtt_port,
        "tls": bool(settings.dsh_mqtt_tls_cert and settings.dsh_mqtt_tls_key),
        "username": device_id,
        "topic_in": f"{_MQTT_TOPIC_PREFIX}/{user_id}/dev/{device_id}/in",
        "topic_out": f"{_MQTT_TOPIC_PREFIX}/{user_id}/dev/{device_id}/out",
    }


# ---------------------------------------------------------------- 投递


async def push(
    services: Services,
    *,
    user_id: str,
    payload: PushRequest,
    skip_rate_limit: bool = False,
) -> dict:
    settings = services.settings
    if not skip_rate_limit and services.dsh_limiter is not None:
        if not await services.dsh_limiter.allow(user_id):
            raise AppError("rate_limited")

    body = payload.payload
    if payload.level is not None and payload.kind != "clip":
        if not isinstance(body, dict):
            raise AppError("invalid_request", message="带 level 时 payload 必须是对象")
        body = {**body, "level": payload.level}
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    if len(raw.encode("utf-8")) > MAX_PAYLOAD_BYTES:
        raise AppError(
            "payload_too_large",
            message=f"推送内容超过 {MAX_PAYLOAD_BYTES} 字节上限",
        )

    at = now_ms()
    ttl_ms = (payload.ttl_s or settings.dsh_msg_ttl_hours * 3600) * 1000
    expires_at = at + ttl_ms
    msg_id = payload.msg_id or new_id("msg")

    if payload.kind == "clip" and not settings.dsh_clip_persist:
        # 剪贴板默认不落库：只做一次 MQTT 直传，设备离线就丢弃（设计要求见 §7）。
        if not payload.device_id:
            raise AppError("invalid_request", message="clip 类推送必须指定 device_id")
        await _require_own_device(services, user_id, payload.device_id)
        runtime = services.dsh
        delivered = bool(
            runtime is not None
            and await runtime.publish_clip(
                user_id=user_id, device_id=payload.device_id, msg_id=msg_id, payload=payload.payload
            )
        )
        return {
            "msg_id": msg_id,
            "kind": payload.kind,
            "device_id": payload.device_id,
            "stored": False,
            "delivered": "live" if delivered else "dropped",
        }

    async def txn(conn: aiosqlite.Connection) -> dict:
        if payload.device_id:
            row = await port.get_device(conn, payload.device_id)
            if row is None or str(row["user_id"]) != user_id:
                raise AppError("device_not_found")
            if int(row["revoked"]):
                raise AppError("device_revoked")

        existing = await port.find_message_seq(conn, msg_id)
        if existing is not None:
            # msg_id 唯一 → 生产者重试天然幂等。
            return {"seq": existing, "duplicate": True, "pending": 0, "trimmed": 0}

        seq = await port.insert_message(
            conn,
            msg_id=msg_id,
            user_id=user_id,
            device_id=payload.device_id,
            kind=payload.kind,
            payload=raw,
            created_at=at,
            expires_at=expires_at,
        )
        trimmed = 0
        if payload.device_id:
            trimmed = await port.trim_pending(
                conn,
                user_id=user_id,
                device_id=payload.device_id,
                keep=settings.dsh_msg_max_pending,
                now_ms=at,
            )
        pending = 0
        if payload.device_id:
            pending = await port.pending_count(
                conn, user_id=user_id, device_id=payload.device_id, now_ms=at
            )
        return {"seq": seq, "duplicate": False, "pending": pending, "trimmed": trimmed}

    result = await services.writer.submit(txn)

    nudged = False
    runtime = services.dsh
    if runtime is not None:
        nudged = await runtime.publish_nudge(
            user_id=user_id, device_id=payload.device_id, seq=int(result["seq"])
        )

    return {
        "msg_id": msg_id,
        "seq": int(result["seq"]),
        "kind": payload.kind,
        "device_id": payload.device_id,
        "created_at": at,
        "expires_at": expires_at,
        "stored": True,
        "duplicate": bool(result["duplicate"]),
        "pending": int(result["pending"]),
        "trimmed": int(result["trimmed"]),
        "nudged": nudged,
        "level": payload.level,
    }


async def _require_own_device(services: Services, user_id: str, device_id: str) -> None:
    async with services.read.acquire() as conn:
        row = await port.get_device(conn, device_id)
    if row is None or str(row["user_id"]) != user_id:
        raise AppError("device_not_found")
    if int(row["revoked"]):
        raise AppError("device_revoked")


# ---------------------------------------------------------------- 剪贴板上行


async def clip(
    services: Services,
    *,
    user_id: str,
    payload: ClipRequest,
    exclude_device_id: str | None = None,
) -> dict:
    """剪贴板：默认不落库，实时转发给同用户的其他在线设备（排除来源设备）。

    `dsh_clip_persist=true` 时改为落库（device_id=NULL，全员待投），由 pull 补投。
    """
    settings = services.settings
    if services.dsh_clip_limiter is not None and not await services.dsh_clip_limiter.allow(user_id):
        raise AppError("rate_limited")

    raw = payload.text
    if len(raw.encode("utf-8")) > MAX_PAYLOAD_BYTES:
        raise AppError("payload_too_large", message=f"剪贴板内容超过 {MAX_PAYLOAD_BYTES} 字节")

    at = now_ms()
    msg_id = payload.msg_id or new_id("clip")
    target = payload.device_id
    if target:
        await _require_own_device(services, user_id, target)

    runtime = services.dsh
    if settings.dsh_clip_persist:
        expires_at = at + settings.dsh_msg_ttl_hours * 3600 * 1000

        async def txn(conn: aiosqlite.Connection) -> int:
            existing = await port.find_message_seq(conn, msg_id)
            if existing is not None:
                return existing
            return await port.insert_message(
                conn,
                msg_id=msg_id,
                user_id=user_id,
                device_id=target,
                kind="clip",
                payload=json.dumps({"text": raw, "fp": payload.fp}, ensure_ascii=False),
                created_at=at,
                expires_at=expires_at,
            )

        seq = await services.writer.submit(txn)
        nudged = bool(
            runtime and await runtime.publish_nudge(user_id=user_id, device_id=None, seq=seq)
        )
        return {
            "msg_id": msg_id,
            "stored": True,
            "delivered": 0,
            "nudged": nudged,
            "seq": seq,
        }

    targets = 0
    if runtime is not None:
        targets = await runtime.publish_clip_others(
            user_id=user_id,
            exclude_device_id=exclude_device_id,
            msg_id=msg_id,
            text=raw,
            fp=payload.fp,
            only_device_id=target if exclude_device_id is None else None,
        )
    return {
        "msg_id": msg_id,
        "stored": False,
        "delivered": targets,
        "nudged": False,
        "seq": 0,
    }


# ---------------------------------------------------------------- 拉取 / 确认


async def pull(
    services: Services,
    *,
    user_id: str,
    device_id: str,
    since_seq: int,
    limit: int,
) -> dict:
    at = now_ms()
    async with services.read.acquire() as conn:
        row = await port.get_device(conn, device_id)
        if row is None or str(row["user_id"]) != user_id:
            raise AppError("device_not_found")
        if int(row["revoked"]):
            raise AppError("device_revoked")
        rows = await port.fetch_pending(
            conn,
            user_id=user_id,
            device_id=device_id,
            since_seq=since_seq,
            limit=limit,
            now_ms=at,
        )
        has_more = False
        if len(rows) == limit and rows:
            has_more = await port.has_pending_after(
                conn, user_id=user_id, device_id=device_id, seq=int(rows[-1]["seq"]), now_ms=at
            )
        latest_seq = await port.latest_message_seq(conn, user_id)
        last_seen = int(row["last_seen_at"]) if row["last_seen_at"] else 0

    if at - last_seen >= _TOUCH_INTERVAL_MS:
        # pull 是设备活着的信号；写库交给 Writer，限频避免每次轮询都写一次。
        async def touch(conn: aiosqlite.Connection) -> None:
            await port.touch_device(conn, device_id=device_id, at_ms=at)

        await services.writer.submit(touch)

    next_seq = int(rows[-1]["seq"]) if rows else since_seq
    return {
        "messages": [
            {
                "seq": int(item["seq"]),
                "msg_id": item["msg_id"],
                "kind": item["kind"],
                "payload": json.loads(item["payload"]),
                "device_id": item["device_id"],
                "created_at": int(item["created_at"]),
                "expires_at": int(item["expires_at"]),
            }
            for item in rows
        ],
        "next_seq": next_seq,
        "has_more": has_more,
        "latest_seq": latest_seq,
        "server_ms": at,
    }


async def ack(services: Services, *, user_id: str, device_id: str, seqs: list[int]) -> dict:
    at = now_ms()

    async def txn(conn: aiosqlite.Connection) -> int:
        row = await port.get_device(conn, device_id)
        if row is None or str(row["user_id"]) != user_id:
            raise AppError("device_not_found")
        if int(row["revoked"]):
            raise AppError("device_revoked")
        return await port.ack_messages(
            conn, user_id=user_id, device_id=device_id, seqs=seqs, at_ms=at
        )

    acked = await services.writer.submit(txn)
    return {"device_id": device_id, "acked": acked, "requested": len(seqs)}


# ---------------------------------------------------------------- 状态 / 清理


async def state(services: Services, user_id: str) -> dict:
    at = now_ms()
    async with services.read.acquire() as conn:
        total, revoked = await port.count_devices(conn, user_id)
        pending = await port.pending_unacked(conn, user_id, at)
        latest_seq = await port.latest_message_seq(conn, user_id)

    runtime = services.dsh
    broker = (
        runtime.snapshot()
        if runtime is not None
        else {
            "enabled": bool(services.settings.dsh_mqtt_enabled),
            "online": False,
            "error": None if not services.settings.dsh_mqtt_enabled else "未启动",
            "bound_port": services.settings.dsh_mqtt_port,
            "devices_online": 0,
            "publish_queue_depth": 0,
            "publish_ms": None,
            "nudges_sent": 0,
            "nudges_dropped": 0,
        }
    )
    return {
        "broker": broker,
        "devices": {"total": total, "revoked": revoked},
        "messages": {"pending": pending, "latest_seq": latest_seq, "server_ms": at},
        "writer_queue_depth": services.writer.queue_depth,
    }


async def cleanup_expired(services: Services, *, batch: int = 500) -> int:
    at = now_ms()

    async def txn(conn: aiosqlite.Connection) -> int:
        seqs = await port.expired_seqs(conn, now_ms=at, limit=batch)
        return await port.delete_messages(conn, seqs)

    return int(await services.writer.submit(txn))
