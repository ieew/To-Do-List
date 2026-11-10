"""服务端时钟：core_domain 之外唯一读取物理时钟的位置。"""

from __future__ import annotations

import time
from datetime import datetime


def now_ms() -> int:
    return int(time.time() * 1000)


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")
