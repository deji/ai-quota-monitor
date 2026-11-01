#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-cross-system}"
TARGET_DIR="${2:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

case "$MODE" in
  plan)
    PROMPT="Perform a read-only architectural review of docs/plan.md, docs/providers.md, docs/design/README.md, docs/workers.md, and docs/lessons.md. Check for security gaps, wire-protocol edge cases, crypto envelope mismatches, and operational blind spots. Do NOT modify any files. Output a structured Markdown review with Verdict (APPROVED or CHANGES_REQUESTED)."
    ;;
  worktree)
    PROMPT="Perform a read-only code, architecture, security/secret-hygiene, and test review of the changes in this worktree against docs/plan.md, docs/providers.md, docs/lessons.md, and AGENTS.md. Do NOT modify any files. Output a structured Markdown review with Verdict (APPROVED or CHANGES_REQUESTED) and findings by severity."
    ;;
  cross-system)
    PROMPT="Perform a read-only cross-system review of docs/plan.md, docs/providers.md, collector/, and app/ together. Verify: 1) Relay envelope interoperability and shared golden test vector, 2) Secret Gist raw URL & optional GitHub token support, 3) Tailscale daemon single-flight lock, launchd plist, and private-IP cleartext guard, 4) Unified Smart Prime state machine and active-hours sync, 5) Fail-closed provider parsers and nullable resetsAt, and 6) Plan-to-code alignment. Do NOT modify any files. Output a structured Markdown review with Verdict (APPROVED or CHANGES_REQUESTED)."
    ;;
  *)
    echo "Usage: $0 [plan|worktree|cross-system] [target-dir]" >&2
    exit 1
    ;;
esac

exec grok -m grok-4.7 --effort medium --always-approve --cwd "$TARGET_DIR" -p "$PROMPT"
