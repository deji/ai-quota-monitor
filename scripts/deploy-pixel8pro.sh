#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$HOME/.local/bin:$ANDROID_HOME/platform-tools:$PATH"

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found at $APK_PATH; building assembleDebug first..."
  ./scripts/verify-all.sh
fi

echo "==> Checking connected ADB devices..."
adb devices -l

echo "==> Installing $APK_PATH onto connected Pixel 8 Pro / Android device..."
adb install -r "$APK_PATH"

echo "==> Launching AI Quota Monitor (MainActivity)..."
adb shell am start -n com.aiquotamonitor/.ui.MainActivity
echo "Done."
