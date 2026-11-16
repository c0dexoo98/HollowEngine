#!/usr/bin/env bash
# HollowEngine runtime-android: build a signed APK without gradle.
#   javac (engine + android runtime) -> d8 -> aapt2 link (+assets)
#   -> add classes.dex -> zipalign -> apksigner sign -> verify
set -euo pipefail
cd "$(dirname "$0")"

SDK=/home/z/my-project/tools/android-sdk
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-34/android.jar
JDK=/home/z/my-project/tools/jdk-21.0.13+9/bin
OUT=build

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

# 1. compile engine + android runtime against android.jar
find ../engine/src src -name '*.java' > "$OUT/sources.list"
"$JDK/javac" -encoding UTF-8 --release 17 -nowarn -cp "$AJ" -d "$OUT/classes" @"$OUT/sources.list"
"$JDK/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .

# 2. dex (lambdas desugared) — build-tools/34 d8 crashes under JDK 21,
#    so use a newer D8 (r8 8.5.35 jar, Google Maven)
"$JDK/java" -Xmx2g -cp /home/z/my-project/tools/r8.jar \
  com.android.tools.r8.D8 --release --min-api 26 --lib "$AJ" \
  --output "$OUT/dex" "$OUT/classes.jar"

# 3. resources + assets -> base apk
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0 "$OUT/res.zip"

# 4. add classes.dex at apk root (jar uf needs temp files — blocked here,
#    so rebuild the zip with python)
python3 - "$OUT/base.apk" "$OUT/base.dexed.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
src, dst, dex = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, "classes.dex")
PY
mv "$OUT/base.dexed.apk" "$OUT/base.apk"

# 5. align + sign with a debug key
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
  "$JDK/keytool" -genkeypair -keystore debug.keystore -alias android \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Hollow Vale Debug" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/hollow-vale.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/hollow-vale.apk"
echo "APK ready: $OUT/hollow-vale.apk ($(du -h "$OUT/hollow-vale.apk" | cut -f1))"
