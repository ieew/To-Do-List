#!/usr/bin/env bash
# 端到端：真起 To-Do-List 服务端（内嵌 broker），跑纯 JVM 的客户端逻辑。
# 不需要手机：验证 登录→注册设备→MQTT→nudge→pull→应用→ack→重连。
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
PAHO="$BASE/tools/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar"
OUT="$BASE/build/jvm-e2e"
REPO="$(cd "$BASE/.." && pwd)"
SERVER="${SERVER:-$REPO/server}"
PY="${PY:-$REPO/.venv/bin/python}"
HTTP_PORT="${E2E_HTTP_PORT:-8099}"
MQTT_PORT="${E2E_MQTT_PORT:-18899}"
TMP="$(mktemp -d)"

[ -x "$PY" ] || { echo "找不到服务端 venv：$PY"; exit 1; }
[ -d "$SERVER" ] || { echo "找不到服务端目录：$SERVER"; exit 1; }
[ -f "$PAHO" ] || { echo "缺少 Paho jar：$PAHO"; exit 1; }

cleanup() {
  [ -n "${SPID:-}" ] && kill "$SPID" 2>/dev/null || true
  sleep 0.3
  rm -rf "$TMP"
}
trap cleanup EXIT

echo "== 起服务端 :$HTTP_PORT (broker :$MQTT_PORT) =="
(
  cd "$SERVER"
  TODO_SERVER_JWT_SECRET="e2e-secret-$(head -c 24 /dev/urandom | base64 | tr -d '\n')" \
  TODO_SERVER_DB_PATH="$TMP/server.sqlite3" \
  TODO_SERVER_DSH_MQTT_BIND=127.0.0.1 \
  TODO_SERVER_DSH_MQTT_PORT="$MQTT_PORT" \
  TODO_SERVER_SNAPSHOT_EVERY_OPS=0 \
  TODO_SERVER_LOG_LEVEL=warning \
  exec "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port "$HTTP_PORT"
) > "$TMP/server.log" 2>&1 &
SPID=$!

python3 - "$HTTP_PORT" <<'PY'
import sys, time, urllib.request
port = sys.argv[1]
deadline = time.time() + 40
while time.time() < deadline:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/health", timeout=2) as r:
            if r.status == 200:
                print("服务端就绪")
                sys.exit(0)
    except Exception:
        pass
    time.sleep(0.4)
print("服务端没起来，日志如下：")
sys.exit(1)
PY

rm -rf "$OUT"
mkdir -p "$OUT"
echo "== javac (core + E2E) =="
find "$BASE/src/java/com/dshkit/core" -name '*.java' > "$OUT/sources.txt"
echo "$BASE/src/java/org/eclipse/paho/client/mqttv3/internal/NetworkModuleService.java" >> "$OUT/sources.txt"
echo "$BASE/tools/jvmtest/E2E.java" >> "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn -cp "$PAHO" -d "$OUT" @"$OUT/sources.txt" \
  2>&1 | grep -v 'bootstrap class path' || true

echo "== run E2E =="
java -cp "$OUT:$PAHO" com.dshkit.jvmtest.E2E "http://127.0.0.1:$HTTP_PORT" dsh-e2e password123
