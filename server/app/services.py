"""应用级依赖容器。"""

from __future__ import annotations

from dataclasses import dataclass, field

from app.config import ServerSettings
from app.db.read_pool import ReadPool
from app.db.writer import WriterWorker


@dataclass(slots=True)
class Services:
    settings: ServerSettings
    writer: WriterWorker
    read: ReadPool
    background: set = field(default_factory=set)
