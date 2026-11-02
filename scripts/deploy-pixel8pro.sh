#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$HOME/.local/bin:$ANDROID_HOME/platform-tools:$PATH"

BUILD_VARIANT="debug"
WIRELESS_PAIR=0

for arg in "$@"; do
  case "$arg" in
    --wireless)
      WIRELESS_PAIR=1
      ;;
    --release)
      BUILD_VARIANT="release"
      ;;
    --debug)
      BUILD_VARIANT="debug"
      ;;
  esac
done

auto_connect_wireless_adb() {
  if ! command -v dns-sd >/dev/null 2>&1; then
    return 1
  fi
  echo "==> Discovering paired Wireless ADB service over mDNS (_adb-tls-connect._tcp)..."
  local endpoint
  endpoint="$(python3 - <<'PY'
import re
import subprocess
import sys

def run_dnssd(args, timeout=2.0):
    p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        out, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
    return out

browse = run_dnssd(["dns-sd", "-B", "_adb-tls-connect._tcp", "local."], timeout=2.0)
instances = []
for line in browse.splitlines():
    if "Add" in line and "_adb-tls-connect._tcp." in line:
        parts = line.split("_adb-tls-connect._tcp.")
        if len(parts) > 1:
            inst = parts[1].strip()
            if inst and inst not in instances:
                instances.append(inst)

for inst in instances:
    lookup = run_dnssd(["dns-sd", "-L", inst, "_adb-tls-connect._tcp", "local."], timeout=2.0)
    m = re.search(r"can be reached at\s+([^\s:]+):(\d+)", lookup)
    if not m:
        continue
    host, port = m.group(1), m.group(2)
    addr_out = run_dnssd(["dns-sd", "-G", "v4", host], timeout=2.0)
    ip_match = re.search(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b", addr_out)
    ip = ip_match.group(1) if ip_match else host
    print(f"{ip}:{port}")
    sys.exit(0)
PY
)"
  if [[ -n "$endpoint" ]]; then
    echo "==> Connecting to discovered Wireless ADB endpoint ($endpoint)..."
    adb connect "$endpoint" >/dev/null 2>&1 || true
  fi
}

if [[ "$WIRELESS_PAIR" -eq 1 ]]; then
  echo "=== Pixel 8 Pro Wireless ADB Setup ==="
  echo "1. On your Pixel 8 Pro: Settings -> System -> Developer options -> Wireless debugging (toggle ON & tap into it)."
  echo "2. If not yet paired with this Mac, tap 'Pair device with pairing code'."
  read -r -p "Enter Pairing IP:Port (or press Enter if already paired): " PAIR_ADDR
  if [[ -n "$PAIR_ADDR" ]]; then
    read -r -p "Enter 6-digit Wi-Fi pairing code: " PAIR_CODE
    adb pair "$PAIR_ADDR" "$PAIR_CODE"
  fi
  echo "3. Look at the main 'Wireless debugging' screen under 'IP address & Port' (note: this port differs from the pairing port)."
  read -r -p "Enter Connect IP:Port (or press Enter to auto-discover via Bonjour): " CONNECT_ADDR
  if [[ -n "$CONNECT_ADDR" ]]; then
    adb connect "$CONNECT_ADDR"
  else
    auto_connect_wireless_adb || true
  fi
fi

if [[ "$BUILD_VARIANT" == "release" ]]; then
  APK_PATH="app/build/outputs/apk/release/app-release.apk"
  echo "==> Building verified Release APK ($APK_PATH)..."
  ./gradlew assembleRelease
else
  APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$APK_PATH" ]]; then
    echo "APK not found at $APK_PATH; running verify-all.sh first..."
    ./scripts/verify-all.sh
  fi
fi

if ! adb devices | grep -qE '[[:space:]]device$'; then
  auto_connect_wireless_adb || true
fi

echo "==> Checking connected ADB devices..."
adb devices

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
