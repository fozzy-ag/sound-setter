#!/usr/bin/env bash
# Builds SoundSetter.apk with the standalone Android SDK (no Gradle).
# Requires: JDK 17 in $HOME/toolchain/jdk-*, Android SDK in $HOME/android-sdk
# (platforms;android-34, build-tools;34.0.0) and Shizuku libs in
# $HOME/toolchain/shizuku-libs. See README.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JDK="$(ls -d "$HOME"/toolchain/jdk-*/ | head -1)"
JAVA_HOME="$JDK"; export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

SDK="$HOME/android-sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
SHIZ="$HOME/toolchain/shizuku-libs"

OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/dex" "$ROOT/dist" "$ROOT/keystore"

echo "== 1/6 aapt2: compile resources =="
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2: link manifest + resources =="
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name "1.0" \
  "$OUT/res.zip"

echo "== 3/6 javac =="
"$JAVA_HOME/bin/javac" -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$SHIZ/api/classes.jar:$SHIZ/aidl/classes.jar:$SHIZ/shared/classes.jar:$SHIZ/provider/classes.jar:$SHIZ/annotation-1.3.0.jar" \
  -d "$OUT/classes" \
  $(find "$ROOT/app/src/main/java" -name '*.java')

echo "== 4/6 d8: dex =="
"$BT/d8" --release --min-api 24 \
  --lib "$PLATFORM" \
  --lib "$SHIZ/annotation-1.3.0.jar" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class') \
  "$SHIZ/api/classes.jar" "$SHIZ/aidl/classes.jar" "$SHIZ/shared/classes.jar" "$SHIZ/provider/classes.jar"

echo "== 5/6 pack dex + zipalign =="
cp "$OUT/base.apk" "$OUT/withdex.apk"
if command -v zip >/dev/null 2>&1; then
  (cd "$OUT/dex" && zip -q -X "$OUT/withdex.apk" classes.dex)
else
  "$JAVA_HOME/bin/jar" uf "$OUT/withdex.apk" -C "$OUT/dex" classes.dex
fi
"$BT/zipalign" -f -p 4 "$OUT/withdex.apk" "$OUT/aligned.apk"

echo "== 6/6 apksigner =="
KS="$ROOT/keystore/soundsetter.keystore"
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass soundsetter \
    -keypass soundsetter -alias soundsetter -keyalg RSA -keysize 2048 \
    -validity 11000 \
    -dname "CN=Sound Setter, OU=Local Build, O=Local, L=Local, ST=NA, C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias soundsetter \
  --ks-pass pass:soundsetter --key-pass pass:soundsetter \
  --out "$ROOT/dist/SoundSetter-v1.0.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$ROOT/dist/SoundSetter-v1.0.apk" | head -8

echo "== badging =="
"$BT/aapt2" dump badging "$ROOT/dist/SoundSetter-v1.0.apk" | head -20

echo
echo "OK -> $ROOT/dist/SoundSetter-v1.0.apk"
ls -la "$ROOT/dist/"
