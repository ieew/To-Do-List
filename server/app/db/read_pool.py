"""只读连接池：`query_only = ON`，永不参与写事务。"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import aiosqlite


class ReadPool:
    def __init__(self, path: Path, size: int = 4) -> None:
        self._path = path
        self._size = max(1, size)
        self._queue: asyncio.Queue[aiosqlite.Connection] = asyncio.Queue()

    async def start(self) -> None:
        for _ in range(self._size):
            conn = await aiosqlite.connect(str(self._path), isolation_level=None)
            conn.row_factory = aiosqlite.Row
            await conn.execute("PRAGMA journal_mode = WAL")
            await conn.execute("PRAGMA busy_timeout = 5000")
            await conn.execute("PRAGMA query_only = ON")
            self._queue.put_nowait(conn)

    @asynccontextmanager
    async def acquire(self) -> AsyncIterator[aiosqlite.Connection]:
        conn = await self._queue.get()
        try:
            yield conn
        finally:
            self._queue.put_nowait(conn)

    async def close(self) -> None:
        while not self._queue.empty():
            conn = self._queue.get_nowait()
            await conn.close()
