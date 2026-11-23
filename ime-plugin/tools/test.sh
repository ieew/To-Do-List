#!/usr/bin/env bash
# 纯 JVM 测试：Json / Store / Protocol / Config（不需要 Android，不联网）
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
PAHO="$BASE/tools/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar"
OUT="$BASE/build/jvm"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "== javac (core only) =="
find "$BASE/src/java/com/dshkit/core" -name '*.java' > "$OUT/sources.txt"
find "$BASE/tools/jvmtest" -name 'CoreTest.java' >> "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn -cp "$PAHO" -d "$OUT" @"$OUT/sources.txt" \
  2>&1 | grep -v 'bootstrap class path' || true

echo "== run CoreTest =="
java -cp "$OUT:$PAHO" com.dshkit.jvmtest.CoreTest
