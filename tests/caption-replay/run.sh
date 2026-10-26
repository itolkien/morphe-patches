#!/usr/bin/env bash
set -euo pipefail
apk=${1:?Target APK required}
tests=${2:?Instrumentation APK required}
serial=${3:-emulator-5580}
port=${CAPTION_ADB_PORT:-5047}
# Explicitly target only the disposable emulator; never choose the first connected device.
[[ "$serial" == emulator-* ]] || { echo 'A disposable emulator serial is required' >&2; exit 2; }
adb -P "$port" -s "$serial" install -r --no-streaming "$apk"
adb -P "$port" -s "$serial" install -r -t "$tests"
result=$(adb -P "$port" -s "$serial" shell am instrument -w app.morphe.captionreplay/app.morphe.captionreplay.CaptionReplay)
printf '%s\n' "$result"
[[ "$result" == *'PASS TOTAL '* && "$result" != *'FAIL '* ]]
