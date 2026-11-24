"""应用级依赖容器。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from app.config import ServerSettings
from app.db.read_pool import ReadPool
from app.db.writer import WriterWorker
from app.infra.rate_limit import SlidingWindowLimiter

if TYPE_CHECKING:
    from app.features.dsh.broker import DshRuntime


@dataclass(slots=True)
class Services:
    settings: ServerSettings
    writer: WriterWorker
    read: ReadPool
    background: set = field(default_factory=set)
    # dsh 推送通道：broker 未启用或启动失败时仍是对象，只是 online=False。
    dsh: DshRuntime | None = None
    dsh_limiter: SlidingWindowLimiter | None = None
    dsh_clip_limiter: SlidingWindowLimiter | None = None
    # 设备凭证续期限流：按设备计数，避免凭证被爆破/滥用
    dsh_refresh_limiter: SlidingWindowLimiter | None = None
