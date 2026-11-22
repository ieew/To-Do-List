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

    # ---- dsh-kit 推送通道（设计见 dsh-ime/doc/SERVER-INTEGRATION.md §8）----
    dsh_mqtt_enabled: bool = True
    dsh_mqtt_bind: str = "0.0.0.0"  # noqa: S104 - broker 要能被手机连上，默认监听全部网卡
    dsh_mqtt_port: int = 1883
    dsh_mqtt_public_host: str = ""
    dsh_mqtt_tls_cert: str = ""
    dsh_mqtt_tls_key: str = ""
    dsh_ticket_ttl_min: int = 10
    dsh_msg_ttl_hours: int = 72
    dsh_msg_max_pending: int = 1000
    dsh_push_rate: int = 30
    dsh_clip_persist: bool = False

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
        if not 0 <= self.dsh_mqtt_port <= 65535:
            raise RuntimeError("服务端启动失败：TODO_SERVER_DSH_MQTT_PORT 需在 0–65535 之间")
        if bool(self.dsh_mqtt_tls_cert) ^ bool(self.dsh_mqtt_tls_key):
            raise RuntimeError(
                "服务端启动失败：TODO_SERVER_DSH_MQTT_TLS_CERT 与 _KEY 必须同时配置或同时留空"
            )
        if self.dsh_ticket_ttl_min <= 0:
            raise RuntimeError("服务端启动失败：TODO_SERVER_DSH_TICKET_TTL_MIN 必须大于 0")
        if self.dsh_msg_ttl_hours <= 0:
            raise RuntimeError("服务端启动失败：TODO_SERVER_DSH_MSG_TTL_HOURS 必须大于 0")
