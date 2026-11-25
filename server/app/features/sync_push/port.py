"""同步推送切片依赖的端口（由 `app.services.Services` 组合根实现）。"""

from __future__ import annotations

from typing import Any, Protocol

from app.config import ServerSettings
from app.db.read_pool import ReadPool
from app.db.writer import WriterWorker


class ServicesPort(Protocol):
    settings: ServerSettings
    writer: WriterWorker
    read: ReadPool
    # dsh 推送通道（可选）：op 入账后给同账号在线设备发一个极小的 sync 信号。
    dsh: Any
