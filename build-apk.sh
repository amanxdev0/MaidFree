#!/bin/bash
# Manual APK build for Sweep — no Gradle needed.
# Uses: aapt2 (compile+link), kotlinc, d8, zipalign, apksigner.
set -e
export JAVA_HOME=~/toolchain/jdk17
export PATH=$JAVA_HOME/bin:$PATH

SDK=~/android-sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
KOTLINC=~/toolchain/kotlin/kotlinc/bin/kotlinc
STDLIB=~/toolchain/kotlin/kotlinc/lib/kotlin-stdlib.jar
SRC=~/workspace/maidfree/app/src/main
OUT=~/workspace/maidfree/build-out
# Shizuku API (dev.rikka.shizuku:api:12.2.0 + provider:12.2.0, MIT license)
# Jars are fetched from Maven Central on first build and cached in libs/.
SHIZUKU_JARS=~/workspace/maidfree/libs/shizuku-api.jar:~/workspace/maidfree/libs/shizuku-provider.jar
fetch_shizuku() {
  local ver=12.2.0 base=https://repo1.maven.org/maven2/dev/rikka/shizuku
  local libdir=~/workspace/maidfree/libs
  mkdir -p "$libdir"
  for art in "api:shizuku-api" "provider:shizuku-provider"; do
    local mod="${art%%:*}" jar="${art##*:}.jar"
    if [ ! -f "$libdir/$jar" ]; then
      echo "-- fetching $mod $ver --"
      curl -sL -o "$libdir/$mod.aar" "$base/$mod/$ver/$mod-$ver.aar"
      unzip -o -q "$libdir/$mod.aar" -d "$libdir/$mod-tmp" "classes.jar"
      mv "$libdir/$mod-tmp/classes.jar" "$libdir/$jar"
      rm -rf "$libdir/$mod-tmp" "$libdir/$mod.aar"
    fi
  done
}
fetch_shizuku

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1. aapt2 compile =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled.zip"

echo "== 2. aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --java "$OUT/gen" "$OUT/compiled.zip"

echo "== 3. kotlinc =="
find "$SRC/java" -name "*.kt" > "$OUT/sources.txt"
find "$OUT/gen" -name "*.java" >> "$OUT/sources.txt"
"$KOTLINC" @"$OUT/sources.txt" -classpath "$AJAR:$SHIZUKU_JARS" -d "$OUT/classes" -jvm-target 17 -nowarn

echo "== 4. d8 =="
(cd "$OUT/classes" && "$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" .)
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$OUT/dex" "$OUT/classes.jar" "$STDLIB" ~/workspace/maidfree/libs/shizuku-api.jar ~/workspace/maidfree/libs/shizuku-provider.jar

echo "== 5. add classes.dex =="
cp "$OUT/base.apk" "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/app-unsigned.apk" classes.dex)

echo "== 6. zipalign =="
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "== 7. sign =="
KS=~/workspace/maidfree/debug.keystore
if [ ! -f "$KS" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -alias sweep \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=MaidFree Debug, OU=Dev, O=Sweep, L=Delhi, C=IN" -noprompt
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/maidfree.apk" "$OUT/app-aligned.apk"

echo "== 8. verify =="
"$BT/apksigner" verify --print-certs "$OUT/maidfree.apk" | head -3
"$BT/aapt" dump badging "$OUT/maidfree.apk" | head -5
ls -la "$OUT/maidfree.apk"
echo "BUILD OK: $OUT/maidfree.apk"
