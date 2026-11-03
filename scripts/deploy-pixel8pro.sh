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
TARGET_DEVICE=""
EMULATOR_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --wireless)
      WIRELESS_PAIR=1
      shift
      ;;
    --release)
      BUILD_VARIANT="release"
      shift
      ;;
    --debug)
      BUILD_VARIANT="debug"
      shift
      ;;
    --device|-s)
      TARGET_DEVICE="$2"
      shift 2
      ;;
    --emulator)
      EMULATOR_ONLY=1
      shift
      ;;
    --help|-h)
      echo "Usage: ./scripts/deploy-pixel8pro.sh [--debug|--release] [--device <serial>] [--emulator] [--wireless]"
      echo ""
      echo "Options:"
      echo "  --debug               Build and deploy debug APK (default)"
      echo "  --release             Build and deploy release APK"
      echo "  --device, -s <serial> Target a specific ADB device/emulator serial"
      echo "  --emulator            Automatically target the running local emulator (e.g. emulator-5554)"
      echo "  --wireless            Trigger interactive mDNS/Wi-Fi pairing for Pixel 8 Pro"
      echo "  --help, -h            Show this help message"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

auto_connect_wireless_adb() {
  if ! command -v dns-sd >/dev/null 2>&1; then
    return 1
  fi
  echo "==> Discovering paired Wireless ADB service over mDNS (_adb-tls-connect._tcp)..."
  local endpoints
  endpoints="$(python3 - <<'PY'
import re
import subprocess
import sys

def run_cmd(args, timeout=2.0):
    p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        out, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
    return out

browse = run_cmd(["dns-sd", "-B", "_adb-tls-connect._tcp", "local."], timeout=2.0)
instances = []
for line in browse.splitlines():
    if "Add" in line and "_adb-tls-connect._tcp." in line:
        parts = line.split("_adb-tls-connect._tcp.")
        if len(parts) > 1:
            inst = parts[1].strip()
            if inst and inst not in instances:
                instances.append(inst)

ts_ip = None
for ts_bin in ("/Applications/Tailscale.app/Contents/MacOS/Tailscale", "tailscale"):
    try:
        ts_out = subprocess.check_output([ts_bin, "status"], text=True, stderr=subprocess.DEVNULL)
        for line in ts_out.splitlines():
            if "pixel-8-pro" in line.lower() or "android" in line.lower():
                m_ip = re.search(r"\b(100\.\d{1,3}\.\d{1,3}\.\d{1,3})\b", line)
                if m_ip:
                    ts_ip = m_ip.group(1)
                    break
        if ts_ip:
            break
    except Exception:
        pass

for inst in instances:
    lookup = run_cmd(["dns-sd", "-L", inst, "_adb-tls-connect._tcp", "local."], timeout=2.0)
    m = re.search(r"can be reached at\s+([^\s:]+):(\d+)", lookup)
    if not m:
        continue
    host, port = m.group(1), m.group(2)
    addr_out = run_cmd(["dns-sd", "-G", "v4", host], timeout=2.0)
    ip_match = re.search(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b", addr_out)
    lan_ip = ip_match.group(1) if ip_match else host
    candidates = []
    if lan_ip:
        candidates.append(f"{lan_ip}:{port}")
    if ts_ip:
        candidates.append(f"{ts_ip}:{port}")
    print(" ".join(candidates))
    sys.exit(0)
PY
)"
  if [[ -n "$endpoints" ]]; then
    for ep in $endpoints; do
      echo "==> Connecting to discovered Wireless ADB endpoint ($ep)..."
      adb connect "$ep" >/dev/null 2>&1 || true
      if adb devices | grep -qE '[[:space:]]device$'; then
        break
      fi
    done
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

if [[ "$EMULATOR_ONLY" -eq 1 ]]; then
  TARGET_DEVICE="$(adb devices | grep -E '^emulator-[0-9]+[[:space:]]+device' | awk '{print $1}' | head -n 1 || true)"
  if [[ -z "$TARGET_DEVICE" ]]; then
    echo "ERROR: --emulator requested but no running emulator found in 'adb devices'." >&2
    echo "Start an emulator via 'orca emulator attach Pixel_8_Pro_Virtual' or Android Studio." >&2
    exit 1
  fi
fi

if [[ -z "$TARGET_DEVICE" ]]; then
  if ! adb devices | grep -qE '[[:space:]]device$'; then
    auto_connect_wireless_adb || true
  fi
fi

echo "==> Checking connected ADB devices..."
adb devices

CONNECTED_DEVICES=()
while IFS= read -r dev; do
  [[ -n "$dev" ]] && CONNECTED_DEVICES+=("$dev")
done < <(adb devices | grep -E '[[:space:]]device$' | awk '{print $1}')

if [[ ${#CONNECTED_DEVICES[@]} -eq 0 ]]; then
  echo ""
  echo "ERROR: No connected Android device or emulator found."
  echo "To connect your Pixel 8 Pro wirelessly, run:"
  echo "  ./scripts/deploy-pixel8pro.sh --wireless"
  echo "Or attach a local virtual device:"
  echo "  orca emulator attach Pixel_8_Pro_Virtual"
  exit 1
fi

if [[ -z "$TARGET_DEVICE" ]]; then
  if [[ ${#CONNECTED_DEVICES[@]} -eq 1 ]]; then
    TARGET_DEVICE="${CONNECTED_DEVICES[0]}"
  else
    echo ""
    echo "ERROR: Multiple devices connected (${CONNECTED_DEVICES[*]})."
    echo "To avoid deploying to the wrong device (e.g. personal hardware), specify:"
    echo "  --device <serial>    (e.g. ./scripts/deploy-pixel8pro.sh --device ${CONNECTED_DEVICES[0]})"
    echo "  --emulator           (to target an active local emulator)"
    exit 1
  fi
fi

ADB_TARGET=(-s "$TARGET_DEVICE")
echo "==> Deploying to target device: $TARGET_DEVICE"

echo "==> Installing $APK_PATH onto $TARGET_DEVICE..."
adb "${ADB_TARGET[@]}" install -r "$APK_PATH"

echo "==> Launching AI Quota Monitor (MainActivity)..."
adb "${ADB_TARGET[@]}" shell am start -n com.aiquotamonitor/.ui.MainActivity
echo "Done."
