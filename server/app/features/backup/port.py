"""备份切片依赖的端口（由 `app.services.Services` 组合根实现）。"""

from __future__ import annotations

from typing import Protocol

from app.config import ServerSettings
from app.db.read_pool import ReadPool
from app.db.writer import WriterWorker


class ServicesPort(Protocol):
    settings: ServerSettings
    writer: WriterWorker
    read: ReadPool
