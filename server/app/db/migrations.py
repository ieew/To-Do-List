"""迁移 runner：按文件名序号顺序应用 SQL，全部成功后记录版本。

约定：
- 迁移文件名为 `NNNN_description.sql`；
- 每个文件在单独事务内应用，失败整体回滚；
- 已应用版本不会重复执行。
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

import aiosqlite

MIGRATIONS_DIR = Path(__file__).with_name("sql")

_BOOTSTRAP = """
CREATE TABLE IF NOT EXISTS schema_migrations (
  version    INTEGER PRIMARY KEY,
  name       TEXT NOT NULL,
  applied_at INTEGER NOT NULL
)
"""


def list_migrations(directory: Path | None = None) -> list[tuple[int, str, Path]]:
    root = directory or MIGRATIONS_DIR
    items: list[tuple[int, str, Path]] = []
    for path in sorted(root.glob("*.sql")):
        prefix, _, rest = path.stem.partition("_")
        if not prefix.isdigit():
            continue
        items.append((int(prefix), rest or path.stem, path))
    versions = [item[0] for item in items]
    if len(set(versions)) != len(versions):
        raise RuntimeError("迁移文件版本号重复")
    return items


def split_statements(sql: str) -> list[str]:
    """按 sqlite3 的语句完整性切分，避免误切字符串里的分号。"""
    statements: list[str] = []
    buffer = ""
    for line in sql.splitlines(keepends=True):
        buffer += line
        if sqlite3.complete_statement(buffer):
            statement = buffer.strip()
            if statement:
                statements.append(statement)
            buffer = ""
    tail = buffer.strip()
    if tail:
        statements.append(tail)
    return statements


async def apply_migrations(
    conn: aiosqlite.Connection,
    *,
    directory: Path | None = None,
    now_ms: int = 0,
) -> list[int]:
    await conn.execute(_BOOTSTRAP)
    cursor = await conn.execute("SELECT version FROM schema_migrations")
    applied = {row[0] for row in await cursor.fetchall()}
    await cursor.close()

    newly_applied: list[int] = []
    for version, name, path in list_migrations(directory):
        if version in applied:
            continue
        statements = split_statements(path.read_text(encoding="utf-8"))
        await conn.execute("BEGIN IMMEDIATE")
        try:
            for statement in statements:
                await conn.execute(statement)
            await conn.execute(
                "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
                (version, name, now_ms),
            )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise
        newly_applied.append(version)
    return newly_applied


async def current_version(conn: aiosqlite.Connection) -> int:
    cursor = await conn.execute("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")
    row = await cursor.fetchone()
    await cursor.close()
    return int(row[0]) if row else 0
