"""Writer Worker：进程内唯一可写连接，命令串行化执行。"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import TypeVar

import aiosqlite

from app.db.migrations import apply_migrations

T = TypeVar("T")

TransactionFn = Callable[[aiosqlite.Connection], Awaitable[T]]


class WriterWorker:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._conn: aiosqlite.Connection | None = None
        self._queue: asyncio.Queue[tuple[TransactionFn, asyncio.Future] | None] = asyncio.Queue()
        self._task: asyncio.Task | None = None

    @property
    def queue_depth(self) -> int:
        return self._queue.qsize()

    @property
    def connection(self) -> aiosqlite.Connection:
        if self._conn is None:
            raise RuntimeError("WriterWorker 尚未启动")
        return self._conn

    async def start(self, *, now_ms: int) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        conn = await aiosqlite.connect(str(self._path), isolation_level=None)
        conn.row_factory = aiosqlite.Row
        await conn.execute("PRAGMA journal_mode = WAL")
        await conn.execute("PRAGMA foreign_keys = ON")
        await conn.execute("PRAGMA busy_timeout = 5000")
        await conn.execute("PRAGMA synchronous = NORMAL")
        await apply_migrations(conn, now_ms=now_ms)
        cursor = await conn.execute("SELECT id FROM server_hlc WHERE id = 1")
        row = await cursor.fetchone()
        await cursor.close()
        if row is None:
            await conn.execute(
                "INSERT INTO server_hlc (id, physical_ms, counter, node_id) "
                "VALUES (1, ?, 0, 'srv')",
                (now_ms,),
            )
        self._conn = conn
        self._task = asyncio.create_task(self._loop(), name="writer-worker")

    async def submit(self, txn: TransactionFn[T]) -> T:
        if self._task is None or self._task.done():
            raise RuntimeError("WriterWorker 未运行")
        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        await self._queue.put((txn, future))
        return await future

    async def _loop(self) -> None:
        assert self._conn is not None
        conn = self._conn
        while True:
            item = await self._queue.get()
            if item is None:
                self._queue.task_done()
                break
            txn, future = item
            try:
                await conn.execute("BEGIN IMMEDIATE")
                try:
                    result = await txn(conn)
                    await conn.commit()
                except BaseException:
                    await conn.rollback()
                    raise
                if not future.done():
                    future.set_result(result)
            except asyncio.CancelledError:
                if not future.done():
                    future.cancel()
                raise
            except BaseException as exc:  # noqa: BLE001 - 需要原样回传给调用方
                if not future.done():
                    future.set_exception(exc)
            finally:
                self._queue.task_done()

    async def stop(self) -> None:
        if self._task is None:
            return
        await self._queue.put(None)
        try:
            await asyncio.wait_for(self._task, timeout=10)
        except TimeoutError:
            self._task.cancel()
            with contextlib.suppress(BaseException):
                await self._task
        if self._conn is not None:
            await self._conn.close()
            self._conn = None
        self._task = None
