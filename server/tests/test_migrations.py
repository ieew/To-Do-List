"""迁移 runner 测试：旧版本升级、重复执行幂等。"""

from __future__ import annotations

from pathlib import Path

import aiosqlite
import pytest

from app.db.migrations import apply_migrations, current_version, list_migrations, split_statements


async def test_apply_migrations_is_idempotent(tmp_path: Path) -> None:
    db_path = tmp_path / "migrate.sqlite3"
    conn = await aiosqlite.connect(str(db_path), isolation_level=None)
    try:
        first = await apply_migrations(conn, now_ms=1)
        assert first == [1]
        assert await current_version(conn) == 1

        second = await apply_migrations(conn, now_ms=2)
        assert second == []

        cursor = await conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
        )
        tables = {row[0] for row in await cursor.fetchall()}
        await cursor.close()
        assert {
            "users",
            "refresh_tokens",
            "workspaces",
            "memberships",
            "op_log",
            "entities",
            "snapshots",
            "server_hlc",
            "schema_migrations",
        } <= tables

        cursor = await conn.execute("SELECT version, applied_at FROM schema_migrations")
        rows = await cursor.fetchall()
        await cursor.close()
        assert rows == [(1, 1)]
    finally:
        await conn.close()


async def test_upgrade_from_empty_and_from_partially_applied(tmp_path: Path) -> None:
    """模拟「旧版本库」：只有迁移记录表但没有业务表，仍能升级到最新。"""
    db_path = tmp_path / "legacy.sqlite3"
    conn = await aiosqlite.connect(str(db_path), isolation_level=None)
    try:
        await conn.execute(
            "CREATE TABLE schema_migrations ("
            "version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at INTEGER NOT NULL)"
        )
        applied = await apply_migrations(conn, now_ms=10)
        assert applied == [1]
        assert await current_version(conn) == 1
    finally:
        await conn.close()


def test_split_statements_keeps_string_semicolons(tmp_path: Path) -> None:
    sql = "CREATE TABLE a (x TEXT DEFAULT 'a;b');\n-- 注释\nCREATE TABLE b (y INT);\n"
    statements = split_statements(sql)
    assert len(statements) == 2
    assert "a;b" in statements[0]


def test_migration_files_are_ordered_and_unique() -> None:
    items = list_migrations()
    versions = [version for version, _name, _path in items]
    assert versions == sorted(versions)
    assert len(set(versions)) == len(versions)
    assert items[0][0] == 1


def test_duplicate_versions_are_rejected(tmp_path: Path) -> None:
    (tmp_path / "0001_a.sql").write_text("SELECT 1;", encoding="utf-8")
    (tmp_path / "0001_b.sql").write_text("SELECT 2;", encoding="utf-8")
    with pytest.raises(RuntimeError):
        list_migrations(tmp_path)
