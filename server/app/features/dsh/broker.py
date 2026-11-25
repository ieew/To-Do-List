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
import socket
from dataclasses import dataclass
from typing import Any

from amqtt.broker import Broker
from amqtt.contexts import Action, BrokerConfig
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


def _normalize_host(host: str | None) -> str:
    """去掉首尾空白和 IPv6 方括号，得到可交给 getaddrinfo 的裸主机名。"""
    value = (host or "").strip()
    if value.startswith("[") and value.endswith("]"):
        value = value[1:-1].strip()
    return value


def _format_bind(host: str | None, port: int) -> str:
    """把配置里的监听地址拼成 amqtt 能解析的 host:port。

    关键：IPv6 地址**绝不能带方括号**。amqtt 用 `rsplit(":", 1)` 拆地址和端口，
    `[::]:1883` 会得到 host=`[::]`，直接被丢给 getaddrinfo；Linux 上（uvloop）
    会报 `socket.gaierror: [Errno -2] Name or service not known`，启动失败。
    """
    value = _normalize_host(host) or "::"
    return f"{value}:{port}"


def _ipv6_only_default() -> bool:
    """当前平台新建 IPv6 socket 时 `IPV6_V6ONLY` 的默认值。

    - Windows：默认 1 —— `::` 只收 IPv6，必须额外绑 0.0.0.0 才能服务局域网 IPv4；
    - Linux/macOS：默认 0 —— `::` 本身就是双栈，再绑 0.0.0.0 会 EADDRINUSE。
    """
    try:
        sock = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    except OSError:
        return False
    try:
        return bool(sock.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY))
    finally:
        sock.close()


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
        configured_host = _normalize_host(self._settings.dsh_mqtt_bind) or "::"
        # 监听策略逐级降级：默认（IPv6，必要时加 IPv4）→ 仅 IPv6 → 仅 IPv4。
        attempts: list[tuple[str, BrokerConfig]] = [("自动", self._broker_config())]
        if ":" in configured_host:
            attempts.append(("IPv6-only", self._broker_config(dual_stack=False)))
        if configured_host != "0.0.0.0":  # noqa: S104 - 兜底时必须有 IPv4 通配
            attempts.append(
                ("IPv4-only", self._broker_config("0.0.0.0", dual_stack=False))  # noqa: S104
            )

        last_exc: BaseException | None = None
        for label, config in attempts:
            broker = Broker(config)
            # 先挂上句柄：start 半途失败时 stop() 还能去关监听。
            self._broker = broker
            try:
                await broker.start()
            except Exception as exc:  # noqa: BLE001 - 换一种监听方式再试
                last_exc = exc
                with contextlib.suppress(Exception):
                    await broker.shutdown()
                for server in getattr(broker, "_servers", {}).values():
                    with contextlib.suppress(Exception):
                        await server.close_instance()
                logger.warning("dsh broker 以 %s 方式监听失败（%s），换一种方式重试", label, exc)
                continue
            logger.info(
                "dsh broker 监听方式=%s 绑定=%s",
                label,
                ", ".join(
                    f"{name}={listener.bind}" for name, listener in broker.listeners_config.items()
                ),
            )
            break
        else:
            self._broker = None
            raise RuntimeError(f"dsh broker 无法绑定端口：{last_exc}") from last_exc

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
            "dsh broker 已启动：配置 bind=%s:%s 实际端口=%s",
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

    def _listener_config(self, bind: str) -> dict[str, Any]:
        settings = self._settings
        listener: dict[str, Any] = {
            "type": "tcp",
            "bind": bind,
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
        return listener

    def _broker_config(self, bind: str | None = None, *, dual_stack: bool = True) -> BrokerConfig:
        settings = self._settings
        port = settings.dsh_mqtt_port
        host = _normalize_host(bind if bind is not None else settings.dsh_mqtt_bind) or "::"
        config = BrokerConfig.from_dict(
            {
                "listeners": {"default": self._listener_config(_format_bind(host, port))},
                # 显式给 plugins，避免 amqtt 默认塞进 AnonymousAuthPlugin（允许匿名）。
                "plugins": {"app.features.dsh.broker.DshBrokerPlugin": {}},
            }
        )
        # IPv6 socket 默认 v6-only 的平台（Windows）才需要额外绑 IPv4：
        # Linux/macOS 的 `::` 本身就双栈，再绑 0.0.0.0 会 EADDRINUSE。
        #
        # 必须直接往 listeners 里塞对象：BrokerConfig.from_dict 会用 default 的字段
        # 覆盖其它 listener 中「恰好等于字段默认值」的项，而 `0.0.0.0:<port>` 正好是
        # ListenerConfig.bind 的默认字面量，走 dict 配置会被 default 的 IPv6 反向覆盖。
        if dual_stack and ":" in host and port != 0 and _ipv6_only_default():
            ipv4_bind = _format_bind("0.0.0.0", port)  # noqa: S104 - 局域网的手机只走 IPv4
            config.listeners["ipv4"] = BrokerConfig.from_dict(
                {"listeners": {"default": self._listener_config(ipv4_bind)}}
            ).listeners["default"]
        return config

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

    async def publish_sync(
        self, *, user_id: str, workspaces: list[str] | None = None, seq: int = 0
    ) -> int:
        """数据变更信号：给该用户所有在线设备发 `{"t":"sync","seq":N,"ws":[...]}`。

        - `seq`：本次 push 后的全局 `server_seq`，客户端可比自己的游标决定是否跳过；
        - `ws`：受影响的工作区（去重），客户端可只拉这些工作区；
        - 不落库、不动 dsh 游标；丢了也不影响正确性（轮询仍会同步）。
        """
        if not self.online:
            return 0
        targets = [device for device in self._online_devices_of(user_id) if self.is_online(device)]
        if not targets:
            return 0
        body = json.dumps(
            {
                "t": "sync",
                "seq": int(seq),
                "ws": sorted({workspace for workspace in (workspaces or []) if workspace}),
            },
            separators=(",", ":"),
        ).encode("utf-8")
        sent = 0
        for target in targets:
            if self._enqueue(topic_in(user_id, target), body):
                sent += 1
        return sent

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
