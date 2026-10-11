#!/usr/bin/env bash
set -euo pipefail

echo "Emulator install working directory: $(pwd)"
APK="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is not set}/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "APK not found at absolute path: $APK"
  echo "APK files in the workspace:"
  find "$GITHUB_WORKSPACE" -type f -name '*.apk' -print || true
  exit 1
fi

echo "Found APK: $APK"
PACKAGE="$("$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer" manifest application-id "$APK")"
DEVICE="${ANDROID_SERIAL:-emulator-5554}"
adb -s "$DEVICE" devices
adb -s "$DEVICE" wait-for-device
timeout 60s adb -s "$DEVICE" install -r "$APK"
timeout 45s adb -s "$DEVICE" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1
sleep 5
if ! timeout 20s adb -s "$DEVICE" shell pidof "$PACKAGE"; then
  echo "Vanta did not remain running after launch. Recent Android logs:"
  adb -s "$DEVICE" logcat -d -t 300 || true
  exit 1
fi
echo "Vanta installed and remained running on the cloud emulator."
