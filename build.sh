#!/bin/bash
# Build VIO Bridge without Gradle: aapt2 → javac → d8 → zipalign → apksigner.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
OUT=build
KEY=$HOME/.android/vio-bridge.keystore

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 36 --version-code 13 --version-name 1.3

javac --release 17 -Xlint:-options -cp "$JAR" -d "$OUT/classes" $(find src -name '*.java')

"$BT/d8" --release --lib "$JAR" --min-api 29 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q ../base.apk classes.dex)
"$BT/zipalign" -p -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

if [ ! -f "$KEY" ]; then
    mkdir -p "$(dirname "$KEY")"
    keytool -genkeypair -keystore "$KEY" -storepass vio-bridge -keypass vio-bridge -alias vio \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=VIO Bridge" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:vio-bridge --out "$OUT/vio-bridge.apk" "$OUT/aligned.apk"
echo "Built $OUT/vio-bridge.apk"
