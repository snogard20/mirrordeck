#!/bin/bash
# MirrorDeck — manual APK build (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner
#
# Requirements: JDK 17+ on PATH (or JAVA_HOME set), and an Android SDK with
# platform android-34 + build-tools 34.0.0. Point ANDROID_HOME at the SDK
# (default: ~/android-sdk). Optional: R8_JAR — a standalone R8 jar used for
# dexing instead of the SDK's bundled d8 (see note at the d8 step).
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
SRC="app/src/main"
BUILD="build"
OUT="${OUT:-$PWD/MirrorDeck-v1.4.apk}"
KS="${KEYSTORE:-$PWD/debug.keystore}"
R8_JAR="${R8_JAR:-$PWD/tools/r8.jar}"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/dex" "$BUILD/apk"

echo "== aapt2 compile (resources) =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$BUILD/res.zip"

echo "== aapt2 link (base APK + R.java) =="
"$BT/aapt2" link -o "$BUILD/apk/base.apk" \
  -I "$PLAT" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  "$BUILD/res.zip" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 6 --version-name "1.4"

echo "== javac =="
# Java 8 target: avoids nest-member attributes some older d8 versions choke on.
find "$SRC/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -source 8 -target 8 -cp "$PLAT" -d "$BUILD/obj" @"$BUILD/sources.txt" 2>/dev/null

echo "== dex =="
jar cf "$BUILD/classes.jar" -C "$BUILD/obj" .
if [ -f "$R8_JAR" ]; then
  # Preferred: standalone R8. The d8 bundled with build-tools 34 (R8 8.2)
  # crashes with an NPE on this code's anonymous NSD listener classes.
  java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --lib "$PLAT" --min-api 24 --output "$BUILD/dex" "$BUILD/classes.jar"
else
  "$BT/d8" --lib "$PLAT" --min-api 24 --output "$BUILD/dex" "$BUILD/classes.jar"
fi

echo "== add classes.dex to APK =="
cp "$BUILD/apk/base.apk" "$BUILD/apk/withdex.apk"
(cd "$BUILD/dex" && zip -q "$OLDPWD/$BUILD/apk/withdex.apk" classes.dex)

echo "== zipalign =="
"$BT/zipalign" -f -p 4 "$BUILD/apk/withdex.apk" "$BUILD/apk/aligned.apk"

echo "== keystore (debug, generated once if absent) =="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias mirrordeck \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=MirrorDeck, O=MirrorDeck, C=US" >/dev/null 2>&1
fi

echo "== apksigner sign =="
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$BUILD/apk/aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --verbose "$OUT" | grep -E "Verifies|v2 scheme|v3 scheme"
"$BT/aapt2" dump badging "$OUT" | grep -E "package:|sdkVersion|targetSdk"
sha256sum "$OUT"
ls -lh "$OUT"
echo "BUILD_OK"
