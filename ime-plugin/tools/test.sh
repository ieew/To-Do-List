#!/usr/bin/env bash
# 纯 JVM 测试：Json / Store / Protocol / Config（不需要联网）
# Secret 用 Android Keystore，编译时需要 android.jar 提供符号；运行时 CoreTest 不碰它
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
AJAR="${AJAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
PAHO="$BASE/tools/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar"
OUT="$BASE/build/jvm"

[ -f "$AJAR" ] || { echo "缺少 android.jar: $AJAR"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

echo "== javac (core only) =="
find "$BASE/src/java/com/dshkit/core" -name '*.java' > "$OUT/sources.txt"
find "$BASE/tools/jvmtest" -name 'CoreTest.java' >> "$OUT/sources.txt"
set +e
javac -source 8 -target 8 -encoding UTF-8 -nowarn -cp "$AJAR:$PAHO" -d "$OUT" @"$OUT/sources.txt" \
  2>&1 | grep -v 'bootstrap class path'
JAVAC_RC=${PIPESTATUS[0]}
set -e
if [ "$JAVAC_RC" -ne 0 ]; then
    echo "javac 失败（rc=$JAVAC_RC），终止测试"
    exit 1
fi

echo "== run CoreTest =="
java -cp "$OUT:$PAHO" com.dshkit.jvmtest.CoreTest
