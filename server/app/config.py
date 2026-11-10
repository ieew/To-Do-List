"""服务端配置：环境变量前缀 TODO_SERVER_。"""

from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class ServerSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="TODO_SERVER_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    db_path: Path = Field(default=Path("./data/server.sqlite3"))
    jwt_secret: str = ""
    admin_token: str = ""

    access_ttl_minutes: int = 30
    refresh_ttl_days: int = 30

    max_ops_per_push: int = 500
    max_body_bytes: int = 1_048_576
    hlc_max_future_ms: int = 300_000

    snapshot_every_ops: int = 500
    restore_max_ops: int = 5_000
    default_workspace_name: str = "我的空间"

    backup_dir: Path = Field(default=Path("./data/backups"))
    auth_rate_limit: int = 10
    log_level: str = "info"

    bind_host: str = "127.0.0.1"
    port: int = 8000

    cors_origins: str = ""
    protocol_version: str = "1.0"

    @property
    def allowed_cors_origins(self) -> list[str]:
        return [item.strip() for item in self.cors_origins.split(",") if item.strip()]

    def validate_for_startup(self) -> None:
        """启动前校验，失败时给出可操作的中文原因。"""
        if len(self.jwt_secret) < 32:
            raise RuntimeError("服务端启动失败：TODO_SERVER_JWT_SECRET 必须是至少 32 字节的随机串")
        if self.max_ops_per_push <= 0:
            raise RuntimeError("服务端启动失败：TODO_SERVER_MAX_OPS_PER_PUSH 必须大于 0")
        if self.snapshot_every_ops < 0:
            raise RuntimeError("服务端启动失败：TODO_SERVER_SNAPSHOT_EVERY_OPS 不能为负数")
