#!/usr/bin/env bash
set -euo pipefail
# Build a test-only instrumentation APK. Sign with the SAME key as the target clone.
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK containing platform android-36 and build-tools 36.0.0}"
base=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
out=${1:?Output directory required}
key=${2:?Target app signing keystore required}
mkdir -p "$out/classes" "$out/dex"
bt="$ANDROID_HOME/build-tools/36.0.0"
api="$ANDROID_HOME/platforms/android-36/android.jar"
javac --release 17 -classpath "$api" -d "$out/classes" "$base/CaptionReplay.java"
jar cf "$out/replay.jar" -C "$out/classes" .
"$bt/d8" --min-api 28 --lib "$api" --output "$out/dex" "$out/replay.jar"
"$bt/aapt2" link -I "$api" --manifest "$base/AndroidManifest.xml" -A "$base/fixtures" -o "$out/unsigned.apk"
(cd "$out/dex" && zip -q "$out/unsigned.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$bt/apksigner" sign --ks "$key" --ks-key-alias morphe --ks-pass pass:captionlab --key-pass pass:captionlab --out "$out/caption-replay-tests.apk" "$out/aligned.apk"
"$bt/apksigner" verify "$out/caption-replay-tests.apk"
