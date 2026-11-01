#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [[ -d "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home" ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$HOME/.local/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> [1/5] Verifying git hooks path & pre-commit secret guard..."
git config core.hooksPath .githooks
sh .githooks/pre-commit

echo "==> [2/5] Running Mac Collector & Repo Hygiene Test Suite..."
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s collector/tests -v

echo "==> [3/5] Running Android Lint, JUnit Tests, and Debug APK Build..."
./gradlew lintDebug testDebugUnitTest assembleDebug

echo "==> [4/5] Verifying Built APK Artifact..."
ls -lh app/build/outputs/apk/debug/app-debug.apk

echo "==> [5/5] Verifying Learnings, Documentation & Skill Sync..."
REQUIRED_DOCS=(
  "AGENTS.md"
  "README.md"
  "docs/plan.md"
  "docs/providers.md"
  "docs/lessons.md"
  "docs/bugs/index.md"
  "docs/status.md"
  "docs/workers.md"
  ".agents/skills/orca-quota-engineering/SKILL.md"
  "scripts/gh-issue-workflow.sh"
  "scripts/review-plan-and-code.sh"
)
for doc in "${REQUIRED_DOCS[@]}"; do
  if [[ ! -s "$doc" ]]; then
    echo "ERROR: Required documentation/skill/workflow file missing or empty: $doc" >&2
    exit 1
  fi
done
if [[ ! -x "scripts/gh-issue-workflow.sh" ]]; then
  echo "ERROR: scripts/gh-issue-workflow.sh must be executable" >&2
  exit 1
fi

# Ensure every defect ID recorded in docs/bugs/index.md is also documented in docs/lessons.md
python3 - <<'PY'
import re, sys

with open("docs/bugs/index.md", encoding="utf-8") as f:
    bugs_text = f.read()
with open("docs/lessons.md", encoding="utf-8") as f:
    lessons_text = f.read()

bug_ids = re.findall(r"\|\s*\*\*([A-Z]+-[A-Z0-9]+)", bugs_text)
if not bug_ids:
    print("ERROR: No bug IDs found in docs/bugs/index.md", file=sys.stderr)
    sys.exit(1)

missing = [bid for bid in bug_ids if bid not in lessons_text]
if missing:
    print(
        f"ERROR: Defect IDs in docs/bugs/index.md are missing post-mortem entries in docs/lessons.md: {missing}\n"
        "Always document learnings in docs/lessons.md and .agents/skills/orca-quota-engineering/SKILL.md before finishing.",
        file=sys.stderr,
    )
    sys.exit(1)

print(f"Verified {len(bug_ids)} defect IDs cross-referenced between docs/bugs/index.md and docs/lessons.md.")
PY

echo "All 5 verification gates PASSED."
