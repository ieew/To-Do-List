#!/usr/bin/env bash
# 构建 dsh-kit.dex：javac → d8（把我们的类 + Paho 一起打进去）
set -euo pipefail

BASE="$(cd "$(dirname "$0")/.." && pwd)"
AJAR="${AJAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
R8="${R8:-/tmp/r8.jar}"
PAHO="${PAHO:-$BASE/tools/lib/org.eclipse.paho.client.mqttv3-1.2.5.jar}"
OUT="$BASE/build"
DEST="$BASE/out"

[ -f "$AJAR" ] || { echo "缺少 android.jar: $AJAR"; exit 1; }
[ -f "$R8" ]   || { echo "缺少 r8.jar: $R8（可从 aliyun 的 google 镜像下载）"; exit 1; }
[ -f "$PAHO" ] || { echo "缺少 Paho jar: $PAHO"; exit 1; }

rm -rf "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/dex" "$DEST"

echo "== javac =="
find "$BASE/src/java" -name '*.java' > "$OUT/sources.txt"
set +e
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
      -classpath "$AJAR:$PAHO" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path'
JAVAC_RC=${PIPESTATUS[0]}
set -e
if [ "$JAVAC_RC" -ne 0 ]; then
    echo "javac 失败（rc=$JAVAC_RC），终止构建（别再吞错误产出缺类 dex）"
    exit 1
fi

echo "== patch paho jar（剔掉 NetworkModuleService，用我们自己的实现）=="
PATCHED="$OUT/paho-dex.jar"
python3 - "$PAHO" "$PATCHED" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
drop = "org/eclipse/paho/client/mqttv3/internal/NetworkModuleService.class"
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == drop:
            continue
        zout.writestr(item, zin.read(item.filename))
print(f"patched jar: {dst}")
PY

echo "== d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8" com.android.tools.r8.D8 \
     --min-api 21 --lib "$AJAR" \
     --output "$OUT/dex" @"$OUT/classes.txt" "$PATCHED" 2>&1 | grep -v '^Warning' || true

cp "$OUT/dex/classes.dex" "$DEST/dsh-kit.dex"

echo "== done =="
ls -l "$DEST/dsh-kit.dex"
python3 - <<'PY' "$DEST/dsh-kit.dex"
import sys
with open(sys.argv[1], 'rb') as f:
    magic = f.read(8)
print("magic:", magic, "ok" if magic.startswith(b'dex\n') else "BAD")
PY
