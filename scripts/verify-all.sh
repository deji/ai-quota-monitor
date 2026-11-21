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

echo "==> [2/5] Running Mac Collector, Repo Hygiene & Workflow Test Suites..."
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s collector/tests -v
./scripts/test-workflow.sh

echo "==> [3/5] Running Android Lint, JUnit Tests, and Debug APK Build..."
AQM_GRADLE_TIMEOUT_SECONDS="${AQM_GRADLE_TIMEOUT_SECONDS:-300}" REPO_ROOT="$REPO_ROOT" python3 - <<'PY'
import os, signal, subprocess, sys

timeout_s = int(os.environ.get("AQM_GRADLE_TIMEOUT_SECONDS", "300"))
repo_root = os.environ["REPO_ROOT"]
cmd = ["./gradlew", "--no-daemon", "lintDebug", "testDebugUnitTest", "assembleDebug"]
proc = subprocess.Popen(cmd, start_new_session=True)
try:
    sys.exit(proc.wait(timeout=timeout_s))
except subprocess.TimeoutExpired:
    print(f"\nERROR: Gradle verification timed out after {timeout_s}s. Capturing jstack before termination...", file=sys.stderr, flush=True)
    try:
        ps = subprocess.run(["ps", "-axo", "pid=,command="], capture_output=True, text=True, timeout=10)
        for line in ps.stdout.splitlines():
            if "GradleWorkerMain" in line and repo_root in line:
                pid = line.strip().split(None, 1)[0]
                print(f"--- jstack {pid} ---", file=sys.stderr, flush=True)
                try:
                    subprocess.run(["jstack", pid], stdout=sys.stderr, stderr=sys.stderr, timeout=15)
                except Exception as exc:
                    print(f"jstack {pid} failed: {exc}", file=sys.stderr, flush=True)
    except Exception as exc:
        print(f"Unable to inspect Gradle workers before termination: {exc}", file=sys.stderr, flush=True)
    finally:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            pass
    sys.exit(124)
PY

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
  "scripts/deploy-pixel8pro.sh"
  "scripts/agent-commit.sh"
  "scripts/check-title.sh"
  "scripts/test-workflow.sh"
  ".github/pull_request_template.md"
  ".github/workflows/title-check.yml"
)
for doc in "${REQUIRED_DOCS[@]}"; do
  if [[ ! -s "$doc" ]]; then
    echo "ERROR: Required documentation/skill/workflow file missing or empty: $doc" >&2
    exit 1
  fi
done
for script in "scripts/gh-issue-workflow.sh" "scripts/review-plan-and-code.sh" "scripts/deploy-pixel8pro.sh" "scripts/agent-commit.sh" "scripts/check-title.sh" "scripts/test-workflow.sh"; do
  if [[ ! -x "$script" ]]; then
    echo "ERROR: $script must be executable" >&2
    exit 1
  fi
done
AGENTS_LINES="$(wc -l < AGENTS.md | tr -d ' ')"
if [[ "$AGENTS_LINES" -gt 120 ]]; then
  echo "ERROR: AGENTS.md must stay concise (<= 120 lines, found $AGENTS_LINES). Move detailed learnings to docs/lessons.md." >&2
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
