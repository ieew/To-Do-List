"""内嵌 amqtt broker 运行时：生命周期、票据鉴权插件、nudge 发布、吊销缓存。

为什么内嵌：见 dsh-ime/doc/SERVER-INTEGRATION.md §1。四个关键约束：

1. broker 会话/离线队列是内存态，可靠性压在 DB 上（pull/ack），这里只当唤醒信号。
2. 插件钩子在事件循环里跑，鉴权只用纯 HMAC（零 IO）；吊销状态走内存集合。
3. 连接与主题 ACL 一对一绑定：`username=device_id`，只允许订/发自己的 `in`/`out`。
4. nudge 不直接扛业务载荷，载荷固定是 `{"t":"nudge","seq":N,"n":M}`。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from dataclasses import dataclass
from typing import Any

from amqtt.broker import Broker
from amqtt.contexts import Action
from amqtt.plugins.base import BaseAuthPlugin, BaseTopicPlugin

from app.config import ServerSettings
from app.features.dsh import port, usecase
from app.features.dsh import reminder as reminder_job
from app.features.dsh.port import ServicesPort as Services
from app.features.dsh.ticket import verify_ticket
from app.infra.clock import now_ms

logger = logging.getLogger("todo.dsh")

TOPIC_PREFIX = "dsh/v1"
_NUDGE_QUEUE_SIZE = 2048
_CLEANUP_INTERVAL_S = 3600
_CLEANUP_BATCH = 500
_REVOKED_REFRESH_S = 60
_CLEANUP_MAX_ROUNDS = 20


def topic_in(user_id: str, device_id: str) -> str:
    return f"{TOPIC_PREFIX}/{user_id}/dev/{device_id}/in"


def topic_out(user_id: str, device_id: str) -> str:
    return f"{TOPIC_PREFIX}/{user_id}/dev/{device_id}/out"


@dataclass(frozen=True, slots=True)
class _Binding:
    user_id: str
    device_id: str


# amqtt 的插件由 BrokerConfig 里的字符串路径实例化，构造时拿不到运行时的引用；
# 服务端保证单进程单 broker（`<db>.lock`），所以这里用模块级活动实例即可。
_ACTIVE_RUNTIME: DshRuntime | None = None


def active_runtime() -> DshRuntime | None:
    return _ACTIVE_RUNTIME


class DshRuntime:
    """broker + nudge 队列 + 吊销缓存 + 清理任务，挂在 `app.state.services.dsh`。"""

    def __init__(self, *, settings: ServerSettings, services: Services) -> None:
        self._settings = settings
        self._services = services
        self._broker: Broker | None = None
        self._started = False
        self._error: str | None = None
        self._bindings: dict[str, _Binding] = {}
        self._revoked: set[str] = set()
        self._nudge_queue: asyncio.Queue[tuple[str, bytes]] = asyncio.Queue(
            maxsize=_NUDGE_QUEUE_SIZE
        )
        self._publish_task: asyncio.Task | None = None
        self._cleanup_task: asyncio.Task | None = None
        self._refresh_task: asyncio.Task | None = None
        self._nudges_sent = 0
        self._nudges_dropped = 0
        self._publish_ms: int | None = None
        self._reminder_task: asyncio.Task | None = None
        self._last_reminder_sweep: int | None = None
        self._reminders_fired = 0

    # ------------------------------------------------------------ 生命周期

    @property
    def online(self) -> bool:
        return self._started and self._broker is not None

    @property
    def error(self) -> str | None:
        return self._error

    def mark_failed(self, exc: BaseException) -> None:
        self._error = f"{type(exc).__name__}: {exc}"[:200]

    @property
    def bound_port(self) -> int:
        if self._broker is not None:
            server = getattr(self._broker, "_servers", {}).get("default")
            instance = getattr(server, "instance", None)
            for sock in getattr(instance, "sockets", None) or []:
                try:
                    return int(sock.getsockname()[1])
                except (OSError, IndexError):
                    continue
        return int(self._settings.dsh_mqtt_port) or 1883

    async def start(self) -> None:
        global _ACTIVE_RUNTIME

        self._revoked = await self._load_revoked()
        config = self._broker_config()
        broker = Broker(config)
        # 先挂上句柄：start 半途失败时 stop() 还能去关监听。
        self._broker = broker
        await broker.start()
        self._started = True
        self._error = None
        _ACTIVE_RUNTIME = self

        self._publish_task = asyncio.create_task(self._publish_loop(), name="dsh-nudge")
        self._refresh_task = asyncio.create_task(self._refresh_revoked_loop(), name="dsh-revoked")
        self._cleanup_task = asyncio.create_task(self._cleanup_loop(), name="dsh-cleanup")
        self._reminder_task = asyncio.create_task(self._reminder_loop(), name="dsh-reminder")
        for task in (
            self._publish_task,
            self._refresh_task,
            self._cleanup_task,
            self._reminder_task,
        ):
            self._services.background.add(task)
            task.add_done_callback(self._services.background.discard)

        logger.info(
            "dsh broker 已启动：bind=%s:%s 实际端口=%s",
            self._settings.dsh_mqtt_bind,
            self._settings.dsh_mqtt_port,
            self.bound_port,
        )

    async def stop(self) -> None:
        global _ACTIVE_RUNTIME

        for task in (
            self._publish_task,
            self._refresh_task,
            self._cleanup_task,
            self._reminder_task,
        ):
            if task is not None and not task.done():
                task.cancel()
                with contextlib.suppress(BaseException):
                    await task
        self._publish_task = None
        self._refresh_task = None
        self._cleanup_task = None
        self._reminder_task = None

        broker, self._broker = self._broker, None
        self._started = False
        self._bindings.clear()
        if broker is not None:
            try:
                await broker.shutdown()
            except Exception:  # noqa: BLE001 - 半启动状态也要尽力关掉监听
                logger.warning("dsh broker 关闭异常，改为直接关监听", exc_info=True)
                for server in getattr(broker, "_servers", {}).values():
                    with contextlib.suppress(Exception):
                        await server.close_instance()
        if _ACTIVE_RUNTIME is self:
            _ACTIVE_RUNTIME = None
        logger.info("dsh broker 已停止")

    def _broker_config(self) -> dict[str, Any]:
        settings = self._settings
        listener: dict[str, Any] = {
            "type": "tcp",
            "bind": f"{settings.dsh_mqtt_bind}:{settings.dsh_mqtt_port}",
            "max_connections": 0,
        }
        if settings.dsh_mqtt_tls_cert and settings.dsh_mqtt_tls_key:
            listener.update(
                {
                    "ssl": True,
                    "certfile": settings.dsh_mqtt_tls_cert,
                    "keyfile": settings.dsh_mqtt_tls_key,
                }
            )
        return {
            "listeners": {"default": listener},
            # 显式给 plugins，避免 amqtt 默认塞进 AnonymousAuthPlugin（允许匿名）。
            "plugins": {"app.features.dsh.broker.DshBrokerPlugin": {}},
        }

    # ------------------------------------------------------------ 鉴权 / ACL 回调

    def verify(self, device_id: str, ticket: str) -> str | None:
        claims = verify_ticket(
            self._settings.jwt_secret, device_id=device_id, ticket=ticket, now_ms=now_ms()
        )
        if claims is None:
            return None
        if device_id in self._revoked:
            return None
        return claims.user_id

    def bind(self, client_id: str | None, *, user_id: str, device_id: str) -> None:
        if client_id:
            self._bindings[client_id] = _Binding(user_id=user_id, device_id=device_id)

    def unbind(self, client_id: str | None) -> None:
        if client_id:
            self._bindings.pop(client_id, None)

    def binding(self, client_id: str | None) -> _Binding | None:
        if not client_id:
            return None
        return self._bindings.get(client_id)

    def is_online(self, device_id: str) -> bool:
        return any(binding.device_id == device_id for binding in self._bindings.values())

    def _client_ids(self, device_id: str) -> list[str]:
        return [cid for cid, binding in self._bindings.items() if binding.device_id == device_id]

    async def _load_revoked(self) -> set[str]:
        async with self._services.read.acquire() as conn:
            return set(await port.revoked_device_ids(conn))

    async def _refresh_revoked_loop(self) -> None:
        while True:
            await asyncio.sleep(_REVOKED_REFRESH_S)
            try:
                # 只做并集：内存里刚吊销的不能被一次刷新抹掉。
                self._revoked |= await self._load_revoked()
            except Exception:  # noqa: BLE001 - 后台刷新失败不影响主链路
                logger.warning("dsh 吊销缓存刷新失败", exc_info=True)

    async def revoke(self, device_id: str) -> int:
        """吊销后立即生效：内存集合 + 踢掉在线连接。返回踢掉的连接数。"""
        self._revoked.add(device_id)
        kicked = 0
        broker = self._broker
        if broker is None:
            return kicked
        for client_id in self._client_ids(device_id):
            handler = broker.sessions.get(client_id, (None, None))[1]
            self._bindings.pop(client_id, None)
            if handler is None:
                continue
            with contextlib.suppress(Exception):
                await handler.handle_connection_closed()
                kicked += 1
        return kicked

    # ------------------------------------------------------------ 推送

    async def publish_nudge(self, *, user_id: str, device_id: str | None, seq: int) -> bool:
        """device_id 为空时给该用户所有在线设备各推一条。返回是否至少投了一台。"""
        if not self.online:
            return False
        targets = [device_id] if device_id else self._online_devices_of(user_id)
        targets = [target for target in targets if self.is_online(target)]
        if not targets:
            return False
        # n 只是提示「大约还有几条」，设备一律以 pull 结果为准。
        async with self._services.read.acquire() as conn:
            pending = await port.pending_unacked(conn, user_id, now_ms())
        payload = json.dumps(
            {"t": "nudge", "seq": int(seq), "n": int(pending)}, separators=(",", ":")
        ).encode("utf-8")
        nudged = False
        for target in targets:
            nudged = self._enqueue(topic_in(user_id, target), payload) or nudged
        return nudged

    async def publish_clip(
        self, *, user_id: str, device_id: str, msg_id: str, payload: Any
    ) -> bool:
        """剪贴板直传（不落库）：设备离线就丢，符合「不留痕」的设计。"""
        if not self.online or not self.is_online(device_id):
            return False
        body = json.dumps(
            {"t": "clip", "id": msg_id, "p": payload},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        return self._enqueue(topic_in(user_id, device_id), body)

    async def publish_clip_others(
        self,
        *,
        user_id: str,
        exclude_device_id: str | None,
        msg_id: str,
        text: str,
        fp: str,
        only_device_id: str | None = None,
    ) -> int:
        """剪贴板直传：默认发同用户的其他在线设备；`only_device_id` 非空时只发这一台。

        `exclude_device_id` 是「来源设备」（设备票据时才非空）：不能回声给它。
        登录用户（CLI/网页）显式指定 `device_id` 时那台是**目标**，走 only_device_id。
        """
        if not self.online:
            return 0
        if only_device_id is not None:
            targets = [only_device_id] if self.is_online(only_device_id) else []
        else:
            targets = [
                device for device in self._online_devices_of(user_id) if device != exclude_device_id
            ]
        if not targets:
            return 0
        body = json.dumps(
            {"t": "clip", "id": msg_id, "p": text, "fp": fp},
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        sent = 0
        for target in targets:
            if self._enqueue(topic_in(user_id, target), body):
                sent += 1
        return sent

    def _online_devices_of(self, user_id: str) -> list[str]:
        seen: list[str] = []
        for binding in self._bindings.values():
            if binding.user_id == user_id and binding.device_id not in seen:
                seen.append(binding.device_id)
        return seen

    def _enqueue(self, topic: str, payload: bytes) -> bool:
        try:
            self._nudge_queue.put_nowait((topic, payload))
            return True
        except asyncio.QueueFull:
            self._nudges_dropped += 1
            logger.warning("dsh 发布队列已满，丢弃一条推送（pull 会兜底）")
            return False

    async def _publish_loop(self) -> None:
        while True:
            topic, payload = await self._nudge_queue.get()
            broker = self._broker
            if broker is None:
                continue
            context = broker.plugins_manager.app_context
            loop = asyncio.get_running_loop()
            started = loop.time()
            try:
                # 内部广播只入 broker 队列，不等 QoS1 ack，不会把事件循环拖住。
                await context.broadcast_message(topic, payload, qos=1)
            except Exception:  # noqa: BLE001 - 单条推送失败不能带崩发布循环
                self._nudges_dropped += 1
                logger.warning("dsh 推送发布失败：topic=%s", topic, exc_info=True)
            else:
                self._nudges_sent += 1
                self._publish_ms = int((loop.time() - started) * 1000)

    # ------------------------------------------------------------ 清理

    async def _cleanup_loop(self) -> None:
        # 启动即清一次：进程重启前遗留的过期消息不用等一小时。
        while True:
            try:
                await self._cleanup_once()
            except Exception:  # noqa: BLE001 - 清理失败不影响通道
                logger.warning("dsh 过期消息清理失败", exc_info=True)
            await asyncio.sleep(_CLEANUP_INTERVAL_S)

    async def _cleanup_once(self) -> int:
        removed = 0
        for _ in range(_CLEANUP_MAX_ROUNDS):
            deleted = await usecase.cleanup_expired(self._services, batch=_CLEANUP_BATCH)
            removed += deleted
            if deleted < _CLEANUP_BATCH:
                break
        if removed:
            logger.info("dsh 清理过期消息 %s 条", removed)
        return removed

    # ------------------------------------------------------------ 提醒

    async def _reminder_loop(self) -> None:
        if not self._settings.dsh_reminders_enabled:
            return
        interval = max(5, int(self._settings.dsh_reminder_interval_s))
        while True:
            try:
                fired = await reminder_job.sweep(self._services)
                self._last_reminder_sweep = now_ms()
                if fired:
                    self._reminders_fired += fired
                    logger.info("dsh 提醒已推送 %s 条", fired)
            except Exception:  # noqa: BLE001 - 提醒失败不能带崩 broker
                logger.warning("dsh 提醒扫描失败", exc_info=True)
            await asyncio.sleep(interval)

    # ------------------------------------------------------------ 可观测

    def snapshot(self) -> dict[str, Any]:
        return {
            "enabled": bool(self._settings.dsh_mqtt_enabled),
            "online": self.online,
            "error": self._error,
            "bound_port": self.bound_port,
            "devices_online": len({b.device_id for b in self._bindings.values()}),
            "publish_queue_depth": self._nudge_queue.qsize(),
            "publish_ms": self._publish_ms,
            "nudges_sent": self._nudges_sent,
            "nudges_dropped": self._nudges_dropped,
            "reminders": {
                "enabled": bool(self._settings.dsh_reminders_enabled),
                "level": int(self._settings.dsh_reminder_level),
                "last_sweep_at": self._last_reminder_sweep,
                "fired": self._reminders_fired,
            },
        }


class DshBrokerPlugin(BaseAuthPlugin, BaseTopicPlugin):
    """amqtt 插件：用户名=device_id、密码=票据；同时负责主题 ACL。

    两个父类的 `__init__` 会顺着 MRO 依次执行（Topic → Auth → Base），互不冲突。
    """

    @dataclass
    class Config:
        topic_prefix: str = TOPIC_PREFIX

    async def authenticate(self, *, session: Any) -> bool:
        runtime = active_runtime()
        if runtime is None:
            return False
        device_id = (session.username or "").strip()
        ticket = (session.password or "").strip()
        if not device_id or not ticket:
            return False
        user_id = runtime.verify(device_id, ticket)
        if user_id is None:
            return False
        session.is_anonymous = False
        runtime.bind(session.client_id, user_id=user_id, device_id=device_id)
        return True

    async def topic_filtering(
        self, *, session: Any = None, topic: str | None = None, action: Any = None
    ) -> bool:
        runtime = active_runtime()
        if runtime is None or session is None or not topic:
            return False
        binding = runtime.binding(session.client_id)
        if binding is None:
            return False
        prefix = getattr(self.config, "topic_prefix", TOPIC_PREFIX) or TOPIC_PREFIX
        base = f"{prefix}/{binding.user_id}/dev/{binding.device_id}"
        if action == Action.SUBSCRIBE:
            return topic == f"{base}/in"
        if action == Action.PUBLISH:
            return topic == f"{base}/out"
        # broker 内部广播（nudge/clip）也走一遍 topic_filtering，方向是 RECEIVE。
        if action == Action.RECEIVE:
            return topic == f"{base}/in"
        return False

    async def on_broker_client_disconnected(
        self, *, client_id: str, client_session: Any = None
    ) -> None:
        runtime = active_runtime()
        if runtime is not None:
            runtime.unbind(client_id)
