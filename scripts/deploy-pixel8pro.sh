#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$HOME/.local/bin:$ANDROID_HOME/platform-tools:$PATH"

if [[ "${1:-}" == "--wireless" ]]; then
  echo "=== Pixel 8 Pro Wireless ADB Setup ==="
  echo "1. On your Pixel 8 Pro: Settings -> System -> Developer options -> Wireless debugging (toggle ON & tap into it)."
  echo "2. If not yet paired with this Mac, tap 'Pair device with pairing code'."
  read -r -p "Enter Pairing IP:Port (or press Enter if already paired): " PAIR_ADDR
  if [[ -n "$PAIR_ADDR" ]]; then
    read -r -p "Enter 6-digit Wi-Fi pairing code: " PAIR_CODE
    adb pair "$PAIR_ADDR" "$PAIR_CODE"
  fi
  echo "3. Look at the main 'Wireless debugging' screen under 'IP address & Port' (note: this port differs from the pairing port)."
  read -r -p "Enter Connect IP:Port (e.g. 192.168.1.50:41555): " CONNECT_ADDR
  if [[ -n "$CONNECT_ADDR" ]]; then
    adb connect "$CONNECT_ADDR"
  fi
fi

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found at $APK_PATH; building assembleDebug first..."
  ./scripts/verify-all.sh
fi

echo "==> Checking connected ADB devices..."
adb devices -l

if ! adb devices | grep -qE '[[:space:]]device$'; then
  echo ""
  echo "ERROR: No connected Android device found."
  echo "To connect your Pixel 8 Pro wirelessly, run:"
  echo "  ./scripts/deploy-pixel8pro.sh --wireless"
  echo "Or see the step-by-step Wireless Debugging guide in README.md."
  exit 1
fi

echo "==> Installing $APK_PATH onto connected Pixel 8 Pro..."
adb install -r "$APK_PATH"

echo "==> Launching AI Quota Monitor (MainActivity)..."
adb shell am start -n com.aiquotamonitor/.ui.MainActivity
echo "Done."
