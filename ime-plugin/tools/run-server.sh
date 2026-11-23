#!/usr/bin/env bash
# 本机开发用：在手机上跑 To-Do-List 服务端 + 内嵌 broker，给输入法里的 dsh-kit 联调。
#
#   bash tools/run-server.sh            # 前台跑（Ctrl+C 停）
#   nohup bash tools/run-server.sh > /tmp/dsh-server.log 2>&1 &
#
# 监听：HTTP 127.0.0.1:8000，MQTT 127.0.0.1:1883（同一台手机上的输入法可达）
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$BASE/.." && pwd)"
SERVER="${SERVER:-$REPO/server}"
PY="${PY:-$REPO/.venv/bin/python}"
DATA="${DATA:-$REPO/server/data}"

[ -x "$PY" ] || { echo "找不到服务端 venv：$PY"; exit 1; }
mkdir -p "$DATA"

export TODO_SERVER_JWT_SECRET="${TODO_SERVER_JWT_SECRET:-dsh-local-dev-secret-0123456789abcdef0123456789}"
export TODO_SERVER_DB_PATH="${TODO_SERVER_DB_PATH:-$DATA/server.sqlite3}"
export TODO_SERVER_DSH_MQTT_BIND="127.0.0.1"
export TODO_SERVER_DSH_MQTT_PORT="1883"
export TODO_SERVER_DSH_MQTT_PUBLIC_HOST="127.0.0.1"
export TODO_SERVER_SNAPSHOT_EVERY_OPS="0"
export TODO_SERVER_LOG_LEVEL="${TODO_SERVER_LOG_LEVEL:-info}"

cd "$SERVER"
exec "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000
