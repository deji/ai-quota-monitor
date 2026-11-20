#!/usr/bin/env bash
set -euo pipefail

# Enforce the GitHub Issue-First Workflow for ai-quota-monitor:
# 1. Every bug or feature MUST be created as a GitHub Issue first.
# 2. All 4 lifecycle stages MUST be posted as attributed comments on the issue:
#    - ## [PLAN]
#    - ## [IMPLEMENTATION]
#    - ## [TESTING]
#    - ## [REVIEW]
# 3. Every outgoing issue title/body/comment is scrubbed by .githooks/commit-msg
#    before calling `gh` so secrets, tokens, or /Users/... paths can never be
#    posted publicly. GitHub auto-closing keywords are rejected there too: an
#    issue closes only through `close`, after `verify` sees all four stages.
#
# Attribution defaults come from AQM_AGENT, AQM_MODEL and AQM_EFFORT; `--human`
# records a human contribution instead. Agent-opened issue titles are checked by
# scripts/check-title.sh.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CHECK_TITLE="$REPO_ROOT/scripts/check-title.sh"

check_hygiene_text() {
  local text="$1"
  local tmp_file
  local status=0
  tmp_file="$(mktemp)"
  printf '%s\n' "$text" > "$tmp_file"
  AQM_SKIP_ISSUE_REF=1 AQM_SKIP_ATTRIBUTION=1 sh "$REPO_ROOT/.githooks/commit-msg" "$tmp_file" || status=$?
  rm -f "$tmp_file"
  return "$status"
}

EVIDENCE_ROOT="${AQM_UI_EVIDENCE_ROOT:-$REPO_ROOT}"

# Visual UI paths. A commit that touches one of these is a UI change.
is_ui_path() {
  case "$1" in
    app/src/main/res/*|app/src/main/java/com/aiquotamonitor/ui/*|app/src/main/java/com/aiquotamonitor/widget/*)
      return 0
      ;;
  esac
  return 1
}

ui_changes_since_clean() {
  local path base
  {
    git -C "$EVIDENCE_ROOT" diff --name-only
    git -C "$EVIDENCE_ROOT" diff --cached --name-only
    git -C "$EVIDENCE_ROOT" ls-files --others --exclude-standard
    base="$(git -C "$EVIDENCE_ROOT" merge-base HEAD main 2>/dev/null || git -C "$EVIDENCE_ROOT" merge-base HEAD master 2>/dev/null || true)"
    if [[ -n "$base" ]]; then
      git -C "$EVIDENCE_ROOT" diff --name-only "$base" HEAD
    fi
  } | while IFS= read -r path; do
    if [[ -n "$path" ]] && is_ui_path "$path"; then
      printf '%s\n' "$path"
    fi
  done | awk '!seen[$0]++'
}

plan_evidence_mode() {
  local plan_text="$1"
  local before_count none_count
  before_count="$(printf '%s\n' "$plan_text" | grep -cx 'UI evidence: before-and-after' || true)"
  none_count="$(printf '%s\n' "$plan_text" | grep -cx 'UI evidence: none' || true)"
  if [[ "$before_count" -eq 1 && "$none_count" -eq 0 ]]; then
    printf '%s\n' "before-and-after"
    return 0
  fi
  if [[ "$none_count" -eq 1 && "$before_count" -eq 0 ]]; then
    printf '%s\n' "none"
    return 0
  fi
  return 1
}

latest_plan_text() {
  if [[ -n "${AQM_UI_EVIDENCE_PLAN:-}" ]]; then
    printf '%s\n' "$AQM_UI_EVIDENCE_PLAN"
    return 0
  fi
  gh issue view "$1" --json comments --jq '[.comments[].body | select(startswith("## [PLAN]"))] | last // ""'
}

usage() {
  cat >&2 <<'EOF'
Usage:
  ./scripts/gh-issue-workflow.sh create "<title>" "<body>" [label1,label2]
  ./scripts/gh-issue-workflow.sh comment <issue-number> <plan|implementation|testing|review> \
      "<body-or-@filepath>" [--attach <image[#alt]>]... \
      [--agent <slug> --model <model>] [--effort <low|medium|high|xhigh|default>] [--human]
  ./scripts/gh-issue-workflow.sh before-shot <issue-number> --attach <image#Before: description>
  ./scripts/gh-issue-workflow.sh verify <issue-number>
  ./scripts/gh-issue-workflow.sh close <issue-number>

Every stage comment is attributed. Pass --agent/--model/--effort (or set
AQM_AGENT/AQM_MODEL/AQM_EFFORT), or --human for a human note.

Every plan comment must contain exactly one of these lines:
  UI evidence: before-and-after
  UI evidence: none
When the plan says before-and-after, capture the before shot before editing UI
files. The commit hook rejects a UI commit until that shot is registered:
  ./scripts/gh-issue-workflow.sh before-shot <issue-number> --attach "$TMPDIR/before.png#Before: previous screen"
The testing comment then attaches that same before shot and the after shot:
  --attach "$TMPDIR/before.png#Before: previous screen"
  --attach "$TMPDIR/after.png#After: screen after the change"
Save captures outside the repo. The local path is not posted. A plan of
"UI evidence: none" does not require screenshots. Collector-only testing
that never opened the UI must include this exact sentence instead:
  No emulator UI was exercised.

Use 'Refs #<number>' in commits and comments. GitHub auto-closing keywords
(Closes, Fixes, Resolves, ...) are rejected; the issue is closed only by
`./scripts/gh-issue-workflow.sh close <number>` after verify passes.
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
    if ! "$CHECK_TITLE" --event issues --title "$TITLE" --body "$BODY"; then
      echo "ERROR: Refusing to create the issue: an agent-authored issue title must read '[<agent>] <summary>' or '[<agent> <model> <effort>] <summary>'." >&2
      exit 1
    fi
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
    if ! [[ "$ISSUE_NUM" =~ ^[0-9]+$ ]]; then
      echo "ERROR: Invalid issue number '$ISSUE_NUM' (expected digits only)." >&2
      exit 1
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
    shift 3
    ATTACH_FLAGS=()
    BEFORE_FLAGS=()
    AFTER_FLAGS=()
    OTHER_FLAGS=()
    BEFORE_SOURCE=""
    EVIDENCE_DIR=""
    HUMAN=0
    AGENT=""
    MODEL=""
    EFFORT=""
    cleanup_evidence() {
      if [[ -n "$EVIDENCE_DIR" && -d "$EVIDENCE_DIR" ]]; then
        rm -rf "$EVIDENCE_DIR"
      fi
    }
    trap cleanup_evidence EXIT
    evidence_n=0
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --human)
          HUMAN=1
          shift
          ;;
        --agent)
          if [[ -z "${2:-}" ]]; then
            echo "ERROR: --agent requires a value." >&2
            exit 1
          fi
          AGENT="$2"
          shift 2
          ;;
        --agent=*)
          AGENT="${1#*=}"
          shift
          ;;
        --model)
          if [[ -z "${2:-}" ]]; then
            echo "ERROR: --model requires a value." >&2
            exit 1
          fi
          MODEL="$2"
          shift 2
          ;;
        --model=*)
          MODEL="${1#*=}"
          shift
          ;;
        --effort)
          if [[ -z "${2:-}" ]]; then
            echo "ERROR: --effort requires a value." >&2
            exit 1
          fi
          EFFORT="$2"
          shift 2
          ;;
        --effort=*)
          EFFORT="${1#*=}"
          shift
          ;;
        --attach)
          if [[ -z "${2:-}" ]]; then
            echo "ERROR: --attach requires an image path, optionally followed by #alt text." >&2
            exit 1
          fi
          spec="$2"
          shift 2
          src_path="${spec%%#*}"
          if [[ "$spec" == *"#"* ]]; then
            alt_text="${spec#*#}"
          else
            alt_text="Emulator evidence"
          fi
          if [[ -z "$alt_text" ]]; then
            echo "ERROR: --attach alt text is empty." >&2
            exit 1
          fi
          if [[ ! -f "$src_path" ]]; then
            echo "ERROR: Screenshot does not exist or is not a file." >&2
            exit 1
          fi
          resolved="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$src_path")"
          case "$resolved" in
            "$REPO_ROOT"/*)
              echo "ERROR: Screenshots must be saved outside the repository so they are not committed. Write them under the system temp directory and pass that path to --attach." >&2
              exit 1
              ;;
          esac
          ext="$(printf '%s' "${resolved##*.}" | tr '[:upper:]' '[:lower:]')"
          case "$ext" in
            png|jpg|jpeg|webp|gif)
              ;;
            *)
              echo "ERROR: --attach accepts png, jpg, jpeg, webp, or gif screenshots." >&2
              exit 1
              ;;
          esac
          size="$(wc -c < "$resolved" | tr -d ' ')"
          if [[ "$size" -eq 0 || "$size" -gt 10485760 ]]; then
            echo "ERROR: Screenshot must be non-empty and at most 10 MB." >&2
            exit 1
          fi
          check_hygiene_text "$alt_text"
          if [[ -z "$EVIDENCE_DIR" ]]; then
            EVIDENCE_DIR="$(mktemp -d)"
          fi
          evidence_n=$((evidence_n + 1))
          safe_copy="$EVIDENCE_DIR/emulator-evidence-${evidence_n}.${ext}"
          cp "$resolved" "$safe_copy"
          lower_alt="$(printf '%s' "$alt_text" | tr '[:upper:]' '[:lower:]')"
          is_before=0
          is_after=0
          case "$lower_alt" in
            *before*) is_before=1 ;;
          esac
          case "$lower_alt" in
            *after*) is_after=1 ;;
          esac
          if [[ "$is_before" -eq 1 && "$is_after" -eq 0 ]]; then
            if [[ -z "$BEFORE_SOURCE" ]]; then
              BEFORE_SOURCE="$resolved"
            fi
            BEFORE_FLAGS+=(--attach "${safe_copy}#${alt_text}")
          elif [[ "$is_after" -eq 1 && "$is_before" -eq 0 ]]; then
            AFTER_FLAGS+=(--attach "${safe_copy}#${alt_text}")
          else
            OTHER_FLAGS+=(--attach "${safe_copy}#${alt_text}")
          fi
          ;;
        *)
          echo "ERROR: Unexpected argument '$1'. Use --attach <image[#alt]>, --agent, --model, --effort, or --human." >&2
          exit 1
          ;;
      esac
    done
    if [[ ${#BEFORE_FLAGS[@]} -gt 0 ]]; then
      ATTACH_FLAGS+=("${BEFORE_FLAGS[@]}")
    fi
    if [[ ${#AFTER_FLAGS[@]} -gt 0 ]]; then
      ATTACH_FLAGS+=("${AFTER_FLAGS[@]}")
    fi
    if [[ ${#OTHER_FLAGS[@]} -gt 0 ]]; then
      ATTACH_FLAGS+=("${OTHER_FLAGS[@]}")
    fi
    if [[ "$STAGE_UPPER" == "PLAN" ]]; then
      if ! plan_evidence_mode "$BODY" >/dev/null; then
        echo "ERROR: A plan comment must contain exactly one line, either 'UI evidence: before-and-after' or 'UI evidence: none'." >&2
        exit 1
      fi
    fi
    if [[ "$HUMAN" -eq 1 ]]; then
      if [[ -n "$AGENT" || -n "$MODEL" || -n "$EFFORT" ]]; then
        echo "ERROR: --human cannot be combined with --agent, --model, or --effort." >&2
        exit 1
      fi
      CONTRIBUTOR='_Contributor: human_'
    else
      if [[ -z "$AGENT" ]]; then AGENT="${AQM_AGENT:-}"; fi
      if [[ -z "$MODEL" ]]; then MODEL="${AQM_MODEL:-}"; fi
      if [[ -z "$EFFORT" ]]; then EFFORT="${AQM_EFFORT:-}"; fi
      if [[ -z "$AGENT" ]]; then
        echo "ERROR: Agent attribution required: pass --agent <slug> --model <model> (or --human for a human comment)." >&2
        exit 1
      fi
      if [[ -z "$MODEL" ]]; then
        echo "ERROR: Agent attribution required: pass --model <model> (or --human for a human comment)." >&2
        exit 1
      fi
      if ! printf '%s' "$AGENT" | grep -Eq '^[a-z0-9][a-z0-9-]*$'; then
        echo "ERROR: --agent must match ^[a-z0-9][a-z0-9-]*$ (got: $AGENT)." >&2
        exit 1
      fi
      if ! printf '%s' "$MODEL" | grep -Eq '^[a-z0-9][a-z0-9._/-]*$'; then
        echo "ERROR: --model must match ^[a-z0-9][a-z0-9._/-]*$ (got: $MODEL)." >&2
        exit 1
      fi
      if [[ -n "$EFFORT" ]]; then
        case "$EFFORT" in
          low|medium|high|xhigh|default)
            ;;
          *)
            echo "ERROR: --effort must be one of: low, medium, high, xhigh, default." >&2
            exit 1
            ;;
        esac
        CONTRIBUTOR="_Contributor: \`${AGENT}\` (\`${MODEL}\`, effort: \`${EFFORT}\`)_"
      else
        CONTRIBUTOR="_Contributor: \`${AGENT}\` (\`${MODEL}\`)_"
      fi
    fi
    if [[ "$STAGE_UPPER" == "TESTING" ]]; then
      receipt="$EVIDENCE_ROOT/.aqm-ui-evidence/$ISSUE_NUM/receipt"
      plan_text="$(latest_plan_text "$ISSUE_NUM" 2>/dev/null || true)"
      evidence_mode="$(plan_evidence_mode "$plan_text" || true)"
      if [[ -z "$evidence_mode" && -f "$receipt" ]]; then
        evidence_mode="$(grep '^mode=' "$receipt" | head -n 1 | cut -d= -f2- || true)"
      fi
      if [[ "$evidence_mode" == "before-and-after" ]]; then
        if printf '%s\n' "$BODY" | grep -Fq 'No emulator UI was exercised.'; then
          echo "ERROR: The plan requires 'UI evidence: before-and-after'; collector-only opt-out is not permitted." >&2
          exit 1
        fi
        if [[ ${#BEFORE_FLAGS[@]} -lt 2 || ${#AFTER_FLAGS[@]} -lt 2 ]]; then
          echo "ERROR: The plan requires a Before screenshot and an After screenshot. Capture the before shot with before-shot before editing UI files, then attach both on this testing comment." >&2
          exit 1
        fi
        if [[ ! -f "$receipt" ]]; then
          echo "ERROR: No before-shot receipt for issue $ISSUE_NUM. Run before-shot before editing UI files." >&2
          exit 1
        fi
        want_sha="$(grep '^sha256=' "$receipt" | head -n 1 | cut -d= -f2- || true)"
        got_sha="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$BEFORE_SOURCE")"
        if [[ "$got_sha" != "$want_sha" ]]; then
          echo "ERROR: The Before screenshot does not match the one registered before the UI change." >&2
          exit 1
        fi
      elif printf '%s\n' "$BODY" | grep -Eiq 'emulator|screencap|screenshot|pixel_8|home screen|home-screen|launcher'; then
        if ! printf '%s\n' "$BODY" | grep -Fq 'No emulator UI was exercised.'; then
          if [[ "$evidence_mode" == "none" ]]; then
            :
          else
            echo "ERROR: Emulator evidence is not registered. The plan must say 'UI evidence: before-and-after' or 'UI evidence: none', and a before-and-after change must run before-shot before any UI edit." >&2
            exit 1
          fi
        fi
      fi
    fi
    # Drop a leading "## [STAGE]" heading (and the blank lines under it) so a
    # pasted body cannot duplicate the header this script composes.
    BODY_CLEAN="$(printf '%s\n' "$BODY" | awk -v stage_upper="$STAGE_UPPER" '
      BEGIN { skipping = 1 }
      {
        if (skipping) {
          line = $0
          if (toupper(line) ~ ("^## \\[" stage_upper "\\][[:space:]]*$")) { next }
          if (line ~ /^[[:space:]]*$/) { next }
          skipping = 0
        }
        print
      }')"
    FULL_COMMENT="## [${STAGE_UPPER}]"$'\n\n'"${CONTRIBUTOR}"$'\n\n'"${BODY_CLEAN}"
    check_hygiene_text "$FULL_COMMENT"
    # Bash 3.2 with `set -u` rejects an empty "${array[@]}".
    if [[ ${#ATTACH_FLAGS[@]} -eq 0 ]]; then
      gh issue comment "$ISSUE_NUM" --body "$FULL_COMMENT"
    else
      gh issue comment "$ISSUE_NUM" --body "$FULL_COMMENT" "${ATTACH_FLAGS[@]}"
    fi
    ;;

  before-shot)
    ISSUE_NUM="${1:-}"
    if [[ -z "$ISSUE_NUM" ]]; then
      usage
    fi
    if ! [[ "$ISSUE_NUM" =~ ^[0-9]+$ ]]; then
      echo "ERROR: Invalid issue number '$ISSUE_NUM' (expected digits only)." >&2
      exit 1
    fi
    shift
    src_path=""
    alt_text=""
    while [[ $# -gt 0 ]]; do
      if [[ "$1" != "--attach" || -z "${2:-}" ]]; then
        echo "ERROR: before-shot takes --attach <image#Before: description>." >&2
        exit 1
      fi
      spec="$2"
      shift 2
      src_path="${spec%%#*}"
      if [[ "$spec" == *"#"* ]]; then
        alt_text="${spec#*#}"
      else
        alt_text=""
      fi
    done
    plan_text="$(latest_plan_text "$ISSUE_NUM")"
    if ! mode="$(plan_evidence_mode "$plan_text")"; then
      echo "ERROR: Issue #$ISSUE_NUM has no plan line 'UI evidence: before-and-after' or 'UI evidence: none'." >&2
      exit 1
    fi
    head_sha="$(git -C "$EVIDENCE_ROOT" rev-parse HEAD)"
    receipt_dir="$EVIDENCE_ROOT/.aqm-ui-evidence/$ISSUE_NUM"
    mkdir -p "$receipt_dir"
    if [[ "$mode" == "none" ]]; then
      printf 'issue=%s\nmode=none\nhead=%s\n' "$ISSUE_NUM" "$head_sha" > "$receipt_dir/receipt"
      echo "Plan says no UI screenshots. Registered that decision for issue #$ISSUE_NUM."
      exit 0
    fi
    lower_alt="$(printf '%s' "$alt_text" | tr '[:upper:]' '[:lower:]')"
    case "$lower_alt" in
      *before*)
        case "$lower_alt" in
          *after*)
            echo "ERROR: The before shot alt text must contain Before and must not contain After." >&2
            exit 1
            ;;
        esac
        ;;
      *)
        echo "ERROR: before-shot requires --attach <image#Before: what the screen showed>." >&2
        exit 1
        ;;
    esac
    if [[ ! -f "$src_path" ]]; then
      echo "ERROR: Screenshot does not exist or is not a file." >&2
      exit 1
    fi
    resolved="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$src_path")"
    case "$resolved" in
      "$EVIDENCE_ROOT"/*|"$REPO_ROOT"/*)
        echo "ERROR: Screenshots must be saved outside the repository so they are not committed. Write them under the system temp directory and pass that path to --attach." >&2
        exit 1
        ;;
    esac
    check_hygiene_text "$alt_text"
    dirty="$(ui_changes_since_clean || true)"
    if [[ -n "$dirty" ]]; then
      echo "ERROR: UI files already differ from the pre-change tree. Capture the before screenshot before editing them:" >&2
      printf '%s\n' "$dirty" >&2
      exit 1
    fi
    ext="$(printf '%s' "${resolved##*.}" | tr '[:upper:]' '[:lower:]')"
    case "$ext" in
      png|jpg|jpeg|webp|gif) ;;
      *)
        echo "ERROR: --attach accepts png, jpg, jpeg, webp, or gif screenshots." >&2
        exit 1
        ;;
    esac
    sha="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$resolved")"
    cp "$resolved" "$receipt_dir/before.$ext"
    printf 'issue=%s\nmode=before-and-after\nhead=%s\nsha256=%s\nalt=%s\nimage=.aqm-ui-evidence/%s/before.%s\n' \
      "$ISSUE_NUM" "$head_sha" "$sha" "$alt_text" "$ISSUE_NUM" "$ext" > "$receipt_dir/receipt"
    echo "Registered the before screenshot for issue #$ISSUE_NUM at commit $head_sha."
    ;;

  verify)
    ISSUE_NUM="${1:-}"
    if [[ -z "$ISSUE_NUM" ]]; then
      usage
    fi
    if ! [[ "$ISSUE_NUM" =~ ^[0-9]+$ ]]; then
      echo "ERROR: Invalid issue number '$ISSUE_NUM' (expected digits only)." >&2
      exit 1
    fi
    ISSUE_JSON_TMP="$(mktemp)"
    cleanup_json() {
      rm -f "$ISSUE_JSON_TMP"
    }
    trap cleanup_json EXIT
    if ! gh issue view "$ISSUE_NUM" --json number,title,state,comments > "$ISSUE_JSON_TMP"; then
      echo "ERROR: Cannot read GitHub Issue #$ISSUE_NUM." >&2
      exit 1
    fi
    python3 - "$ISSUE_NUM" "$ISSUE_JSON_TMP" <<'PY'
import json
import re
import sys

issue_num = sys.argv[1]
with open(sys.argv[2], "r", encoding="utf-8") as fh:
    raw = fh.read()

try:
    data = json.loads(raw)
except ValueError:
    print("ERROR: gh returned output that is not JSON", file=sys.stderr)
    sys.exit(1)

STAGES = ("PLAN", "IMPLEMENTATION", "TESTING", "REVIEW")
HEADER_RE = re.compile(r"^## \[(PLAN|IMPLEMENTATION|TESTING|REVIEW)\][ \t]*$")
CONTRIBUTOR_RE = re.compile(r"(?m)^_Contributor:[ \t]+.+_[ \t]*$")

comments = data.get("comments") or []
found = set()
unattributed = set()
for comment in comments:
    body = (comment.get("body") or "").lstrip("\r\n")
    if not body:
        continue
    first_line = body.splitlines()[0]
    match = HEADER_RE.match(first_line)
    if not match:
        continue
    stage = match.group(1)
    if CONTRIBUTOR_RE.search(body):
        found.add(stage)
    else:
        unattributed.add(stage)

missing = [f"## [{s}]" for s in STAGES if s not in found]
if missing:
    print(
        "ERROR: GitHub Issue #%s ('%s') is missing required attributed lifecycle comments: %s"
        % (data.get("number"), data.get("title"), ", ".join(missing)),
        file=sys.stderr,
    )
    missing_attr = [f"## [{s}]" for s in STAGES if s in unattributed and s not in found]
    if missing_attr:
        print(
            "ERROR: stage comment(s) found without a '_Contributor: ..._' line: %s"
            % ", ".join(missing_attr),
            file=sys.stderr,
        )
    print(
        "Post each stage as its own comment with ./scripts/gh-issue-workflow.sh comment.",
        file=sys.stderr,
    )
    sys.exit(1)

print(
    "Verified GitHub Issue #%s: all 4 attributed lifecycle stages ([PLAN], [IMPLEMENTATION], [TESTING], [REVIEW]) are documented."
    % data.get("number")
)
PY
    ;;

  close)
    ISSUE_NUM="${1:-}"
    if [[ -z "$ISSUE_NUM" ]]; then
      usage
    fi
    if ! [[ "$ISSUE_NUM" =~ ^[0-9]+$ ]]; then
      echo "ERROR: Invalid issue number '$ISSUE_NUM' (expected digits only)." >&2
      exit 1
    fi
    if ! "$0" verify "$ISSUE_NUM"; then
      echo "ERROR: Refusing to close issue #$ISSUE_NUM: verification failed (all four attributed stage comments must exist)." >&2
      exit 1
    fi
    gh issue close "$ISSUE_NUM"
    ;;

  *)
    usage
    ;;
esac
