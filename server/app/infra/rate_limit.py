"""进程内滑动窗口限流（仅用于鉴权接口）。"""

from __future__ import annotations

import asyncio
import time


class SlidingWindowLimiter:
    def __init__(self, limit: int, window_seconds: int = 60) -> None:
        self._limit = limit
        self._window = window_seconds
        self._hits: dict[str, list[float]] = {}
        self._lock = asyncio.Lock()

    async def allow(self, key: str) -> bool:
        if self._limit <= 0:
            return True
        now = time.monotonic()
        async with self._lock:
            bucket = [stamp for stamp in self._hits.get(key, []) if now - stamp < self._window]
            if len(bucket) >= self._limit:
                self._hits[key] = bucket
                return False
            bucket.append(now)
            self._hits[key] = bucket
            return True
