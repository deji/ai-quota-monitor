#!/usr/bin/env bash
set -euo pipefail

# Enforce the GitHub Issue-First Workflow for ai-quota-monitor:
# 1. Every bug or feature MUST be created as a GitHub Issue first.
# 2. All 4 lifecycle stages MUST be posted as structured comments on the issue:
#    - ## [PLAN]
#    - ## [IMPLEMENTATION]
#    - ## [TESTING]
#    - ## [REVIEW]
# 3. Every outgoing issue body/comment is scrubbed by .githooks/commit-msg before
#    calling `gh` so secrets, tokens, or /Users/... paths can never be posted publicly.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

check_hygiene_text() {
  local text="$1"
  local tmp_file
  tmp_file="$(mktemp)"
  trap 'rm -f "$tmp_file"' RETURN
  printf '%s\n' "$text" > "$tmp_file"
  AQM_SKIP_ISSUE_REF=1 sh "$REPO_ROOT/.githooks/commit-msg" "$tmp_file"
}

usage() {
  cat >&2 <<'EOF'
Usage:
  ./scripts/gh-issue-workflow.sh create "<title>" "<body>" [label1,label2]
  ./scripts/gh-issue-workflow.sh comment <issue-number> <plan|implementation|testing|review> "<body-or-@filepath>"
  ./scripts/gh-issue-workflow.sh verify <issue-number>
  ./scripts/gh-issue-workflow.sh close <issue-number>
EOF
  exit 1
}

CMD="${1:-}"
if [[ -z "$CMD" ]]; then
  usage
fi
shift

case "$CMD" in
  create)
    TITLE="${1:-}"
    BODY="${2:-}"
    LABELS="${3:-}"
    if [[ -z "$TITLE" || -z "$BODY" ]]; then
      usage
    fi
    if [[ "$BODY" == @* ]]; then
      BODY="$(cat "${BODY#@}")"
    fi
    check_hygiene_text "$TITLE"
    check_hygiene_text "$BODY"
    if [[ -n "$LABELS" ]]; then
      gh issue create --title "$TITLE" --body "$BODY" --label "$LABELS"
    else
      gh issue create --title "$TITLE" --body "$BODY"
    fi
    ;;

  comment)
    ISSUE_NUM="${1:-}"
    STAGE_RAW="${2:-}"
    BODY="${3:-}"
    if [[ -z "$ISSUE_NUM" || -z "$STAGE_RAW" || -z "$BODY" ]]; then
      usage
    fi
    if [[ "$BODY" == @* ]]; then
      BODY="$(cat "${BODY#@}")"
    fi
    STAGE_UPPER="$(printf '%s' "$STAGE_RAW" | tr '[:lower:]' '[:upper:]')"
    case "$STAGE_UPPER" in
      PLAN|IMPLEMENTATION|TESTING|REVIEW)
        ;;
      *)
        echo "ERROR: Invalid stage '$STAGE_RAW'. Expected one of: plan, implementation, testing, review." >&2
        exit 1
        ;;
    esac
    FULL_COMMENT="## [${STAGE_UPPER}]"$'\n\n'"${BODY}"
    check_hygiene_text "$FULL_COMMENT"
    gh issue comment "$ISSUE_NUM" --body "$FULL_COMMENT"
    ;;

  verify)
    ISSUE_NUM="${1:-}"
    if [[ -z "$ISSUE_NUM" ]]; then
      usage
    fi
    ISSUE_JSON="$(gh issue view "$ISSUE_NUM" --json number,title,state,comments)"
    python3 - "$ISSUE_NUM" "$ISSUE_JSON" <<'PY'
import json
import sys

issue_num = sys.argv[1]
data = json.loads(sys.argv[2])
comments = [c.get("body", "") for c in data.get("comments", [])]
all_text = "\n".join(comments)

required_headers = [
    "## [PLAN]",
    "## [IMPLEMENTATION]",
    "## [TESTING]",
    "## [REVIEW]",
]
missing = [h for h in required_headers if h not in all_text]
if missing:
    print(
        f"ERROR: GitHub Issue #{issue_num} ('{data.get('title')}') is missing mandatory lifecycle comments: {missing}\n"
        "Every issue must record ## [PLAN], ## [IMPLEMENTATION], ## [TESTING], and ## [REVIEW] comments before completion.",
        file=sys.stderr,
    )
    sys.exit(1)

print(f"Verified GitHub Issue #{issue_num}: all 4 lifecycle stages ([PLAN], [IMPLEMENTATION], [TESTING], [REVIEW]) are documented.")
PY
    ;;

  close)
    ISSUE_NUM="${1:-}"
    if [[ -z "$ISSUE_NUM" ]]; then
      usage
    fi
    "$0" verify "$ISSUE_NUM"
    gh issue close "$ISSUE_NUM"
    ;;

  *)
    usage
    ;;
esac
