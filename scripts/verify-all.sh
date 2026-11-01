#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$HOME/.local/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> [1/4] Verifying git hooks path & pre-commit secret guard..."
git config core.hooksPath .githooks
sh .githooks/pre-commit

echo "==> [2/4] Running Mac Collector & Repo Hygiene Test Suite..."
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s collector/tests -v

echo "==> [3/4] Running Android Lint, JUnit Tests, and Debug APK Build..."
./gradlew lintDebug testDebugUnitTest assembleDebug

echo "==> [4/4] Verifying Built APK Artifact..."
ls -lh app/build/outputs/apk/debug/app-debug.apk
echo "All verification gates PASSED."
