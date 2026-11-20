#!/usr/bin/env bash
# scripts/test-workflow.sh — offline, deterministic tests for the agent workflow.
#
# Covers scripts/agent-commit.sh, .githooks/commit-msg, scripts/check-title.sh
# and scripts/gh-issue-workflow.sh. Every test runs in a throwaway directory
# under $TMPDIR with a stub `gh` first in PATH, so the suite makes zero network
# calls and never touches the real repository or the real GitHub API.
#
# Run it with: ./scripts/test-workflow.sh

set -u

REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
AGENT_COMMIT="$REPO_ROOT/scripts/agent-commit.sh"
CHECK_TITLE="$REPO_ROOT/scripts/check-title.sh"
ISSUE_WORKFLOW="$REPO_ROOT/scripts/gh-issue-workflow.sh"
HOOK="$REPO_ROOT/.githooks/commit-msg"

TMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/aqm-workflow-test.XXXXXX") || exit 1
trap 'rm -rf "$TMP_ROOT"' EXIT INT TERM HUP

PASS=0
FAIL=0

ok() {
  PASS=$((PASS + 1))
  printf 'ok   %s\n' "$1"
}

no() {
  FAIL=$((FAIL + 1))
  printf 'FAIL %s: %s\n' "$1" "${2:-no detail}" >&2
}

last_error() {
  if [ -s "$TMP_ROOT/last.err" ]; then
    head -c 500 "$TMP_ROOT/last.err" | tr '\n' ' '
  else
    head -c 300 "$TMP_ROOT/last.out" 2>/dev/null | tr '\n' ' '
  fi
}

expect_ok() {
  _name=$1
  shift
  "$@" >"$TMP_ROOT/last.out" 2>"$TMP_ROOT/last.err"
  _status=$?
  if [ "$_status" -eq 0 ]; then
    ok "$_name"
  else
    no "$_name" "expected success, got exit $_status — $(last_error)"
  fi
}

expect_fail() {
  _name=$1
  shift
  "$@" >"$TMP_ROOT/last.out" 2>"$TMP_ROOT/last.err"
  _status=$?
  if [ "$_status" -eq 0 ]; then
    no "$_name" "expected failure, got exit 0"
  else
    ok "$_name"
  fi
}

# Run a command inside a fixture repository with Git's ambient environment
# variables removed: this suite itself may run under a hook or a worktree.
run_in() {
  _run_dir=$1
  shift
  (
    unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX
    unset GIT_OBJECT_DIRECTORY GIT_COMMON_DIR GIT_NAMESPACE
    cd "$_run_dir" || exit 1
    "$@"
  )
}

# --- offline gh stub ------------------------------------------------------

STUB_BIN="$TMP_ROOT/bin"
mkdir -p "$STUB_BIN"

cat > "$STUB_BIN/gh" <<'STUB'
#!/bin/sh
if [ -n "${AQM_GH_LOG:-}" ]; then
  printf '%s\n' "$*" >> "$AQM_GH_LOG"
fi
case "$1 $2" in
  "api user")
    if [ -n "${AQM_GH_USER_TSV:-}" ]; then printf '%s\n' "$AQM_GH_USER_TSV"; fi
    ;;
  "issue view")
    if [ -n "${AQM_GH_ISSUE_JSON:-}" ] && [ -f "${AQM_GH_ISSUE_JSON:-}" ]; then
      cat "$AQM_GH_ISSUE_JSON"
    fi
    ;;
  "issue create")
    printf 'https://github.invalid/example/repo/issues/6\n'
    ;;
esac
exit 0
STUB
chmod +x "$STUB_BIN/gh"

export PATH="$STUB_BIN:$PATH"
export AQM_GH_LOG="$TMP_ROOT/gh.log"
: > "$AQM_GH_LOG"

# The fixture repos install only the commit-msg hook: the repo's pre-commit
# hook runs the Android/collector gates, which are meaningless (and slow) in a
# throwaway tree.
STUB_HOOKS="$TMP_ROOT/hooks"
mkdir -p "$STUB_HOOKS"
ln -s "$HOOK" "$STUB_HOOKS/commit-msg"

new_repo() {
  _repo="$TMP_ROOT/repos/$1"
  mkdir -p "$_repo"
  run_in "$_repo" git init -q . || return 1
  run_in "$_repo" git config user.name "Fixture Human" || return 1
  run_in "$_repo" git config user.email "human@example.com" || return 1
  run_in "$_repo" git config commit.gpgsign false || return 1
  run_in "$_repo" git config core.hooksPath "$STUB_HOOKS" || return 1
  printf 'seed\n' > "$_repo/seed.txt"
  run_in "$_repo" git add seed.txt || return 1
  run_in "$_repo" git commit -q -m "seed: fixture repository" -m "Refs #6" || return 1
  printf '%s\n' "$_repo"
}

stage_change() {
  printf 'change %s\n' "$2" > "$1/$2.txt"
  run_in "$1" git add "$2.txt"
}

assert_log_contains() {
  if grep -Fq -e "$2" "$AQM_GH_LOG"; then
    ok "$1"
  else
    no "$1" "gh log does not contain: $2"
  fi
}

assert_log_lacks() {
  if grep -Fq -e "$2" "$AQM_GH_LOG"; then
    no "$1" "gh log unexpectedly contains: $2"
  else
    ok "$1"
  fi
}

printf '== agent-commit.sh ==\n'

repo_ac=$(new_repo agent-commit) || {
  printf 'cannot create a fixture repository\n' >&2
  exit 1
}

expect_fail "agent-commit rejects a missing bot identity" \
  run_in "$repo_ac" env -u GIT_AUTHOR_NAME -u GIT_AUTHOR_EMAIL -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL -u AQM_CO_AUTHOR_EMAIL \
  "$AGENT_COMMIT" -m "test: no identity"

expect_fail "agent-commit rejects a partial GIT_AUTHOR_* pair" \
  run_in "$repo_ac" env -u GIT_AUTHOR_EMAIL -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  GIT_AUTHOR_NAME="Fixture Bot" \
  "$AGENT_COMMIT" -m "test: partial author"

expect_fail "agent-commit rejects a partial GIT_COMMITTER_* pair" \
  run_in "$repo_ac" env -u GIT_COMMITTER_EMAIL \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" GIT_COMMITTER_NAME="Fixture Bot" \
  "$AGENT_COMMIT" -m "test: partial committer"

expect_fail "agent-commit rejects an email outside .invalid" \
  run_in "$repo_ac" env -u GIT_AUTHOR_NAME -u GIT_AUTHOR_EMAIL -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  "$AGENT_COMMIT" --author-name "Fixture Bot" --author-email "fixture@example.com" -m "test: bad domain"

expect_fail "agent-commit rejects a GitHub noreply bot email" \
  run_in "$repo_ac" env -u GIT_AUTHOR_NAME -u GIT_AUTHOR_EMAIL -u GIT_COMMITTER_NAME -u GIT_COMMITTER_EMAIL \
  "$AGENT_COMMIT" --author-name "Fixture Bot" --author-email "4242+fixture@users.noreply.github.com" -m "test: noreply"

expect_fail "agent-commit refuses --no-verify" \
  run_in "$repo_ac" env GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  AQM_CO_AUTHOR_EMAIL="4242+deji@users.noreply.github.com" \
  "$AGENT_COMMIT" -m "test: unsafe flag" --no-verify

expect_fail "agent-commit refuses --amend" \
  run_in "$repo_ac" env GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  AQM_CO_AUTHOR_EMAIL="4242+deji@users.noreply.github.com" \
  "$AGENT_COMMIT" -m "test: unsafe flag" --amend

for bypass_flag in -an -sn --no-veri --amen --auth; do
  expect_fail "agent-commit refuses bypass option $bypass_flag" \
    run_in "$repo_ac" env GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
    AQM_CO_AUTHOR_EMAIL="4242+deji@users.noreply.github.com" \
    "$AGENT_COMMIT" -m "test: bypass" -m "Refs #6" "$bypass_flag"
done

stage_change "$repo_ac" skip-env-bypass
expect_fail "agent-commit refuses an auto-closing keyword even with AQM_SKIP_ISSUE_REF=1" \
  run_in "$repo_ac" env AQM_SKIP_ISSUE_REF=1 \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  AQM_CO_AUTHOR_EMAIL="4242+deji@users.noreply.github.com" \
  "$AGENT_COMMIT" -m "test: Fixes #6"

# A staged UI change with no before-shot receipt is refused by the hook.
mkdir -p "$repo_ac/app/src/main/java/com/aiquotamonitor/ui"
printf 'fun changed() = 1\n' > "$repo_ac/app/src/main/java/com/aiquotamonitor/ui/AgentCommitUi.kt"
run_in "$repo_ac" git add app
expect_fail "agent-commit refuses a UI commit without a before-shot receipt" \
  run_in "$repo_ac" env GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  AQM_CO_AUTHOR_EMAIL="4242+deji@users.noreply.github.com" \
  "$AGENT_COMMIT" -m "feat: change the screen" -m "Refs #6"
run_in "$repo_ac" git reset -q HEAD
rm -rf "$repo_ac/app"

export AQM_GH_USER_TSV
AQM_GH_USER_TSV=$(printf '4242\tdeji')

stage_change "$repo_ac" owner-resolution
: > "$AQM_GH_LOG"
expect_ok "agent-commit resolves the owner co-author through gh" \
  run_in "$repo_ac" env -u AQM_CO_AUTHOR_EMAIL \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  "$AGENT_COMMIT" -m "docs: record the agent workflow" -m "Refs #6"

assert_log_contains "agent-commit calls gh api user when AQM_CO_AUTHOR_EMAIL is unset" "api user"

ac_body=$(run_in "$repo_ac" git log -1 --format=%B)
case "$ac_body" in
  *"Co-authored-by: Oladeji Famakinwa <4242+deji@users.noreply.github.com>"*)
    ok "agent-commit appends the owner Co-authored-by trailer"
    ;;
  *)
    no "agent-commit appends the owner Co-authored-by trailer" "body was: $ac_body"
    ;;
esac

ac_ident=$(run_in "$repo_ac" git log -1 --format='%cn <%ce>')
case "$ac_ident" in
  "Fixture Bot <fixture@bots.invalid>")
    ok "agent-commit defaults the committer to the bot identity"
    ;;
  *)
    no "agent-commit defaults the committer to the bot identity" "committer was: $ac_ident"
    ;;
esac

stage_change "$repo_ac" owner-email-env
: > "$AQM_GH_LOG"
expect_ok "agent-commit uses AQM_CO_AUTHOR_EMAIL without calling gh" \
  run_in "$repo_ac" env AQM_CO_AUTHOR_EMAIL="4242+deji@users.noreply.github.com" \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  "$AGENT_COMMIT" -m "docs: attribute through the environment" -m "Refs #6"

if [ -s "$AQM_GH_LOG" ]; then
  no "agent-commit skips gh when AQM_CO_AUTHOR_EMAIL is set" "gh log: $(cat "$AQM_GH_LOG")"
else
  ok "agent-commit skips gh when AQM_CO_AUTHOR_EMAIL is set"
fi

stage_change "$repo_ac" wrong-owner
AQM_GH_USER_TSV=$(printf '4242\tnot-the-owner')
export AQM_GH_USER_TSV
expect_fail "agent-commit refuses a gh login that is not the expected owner" \
  run_in "$repo_ac" env -u AQM_CO_AUTHOR_EMAIL \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  "$AGENT_COMMIT" -m "docs: wrong owner" -m "Refs #6"
AQM_GH_USER_TSV=$(printf '4242\tdeji')
export AQM_GH_USER_TSV

printf '\n== .githooks/commit-msg ==\n'

repo_cm=$(new_repo commit-msg) || {
  printf 'cannot create a fixture repository\n' >&2
  exit 1
}

write_msg() {
  printf '%s\n' "$1" > "$repo_cm/msg.txt"
}

write_msg 'feat: add the workflow

Refs #6'
expect_ok "commit-msg accepts a human commit with Refs #6" \
  run_in "$repo_cm" env -u AQM_REQUIRE_ISSUE_REF -u AQM_SKIP_ISSUE_REF -u AQM_SKIP_ATTRIBUTION \
  sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: add the workflow'
expect_fail "commit-msg requires an issue reference when AQM_REQUIRE_ISSUE_REF=1" \
  run_in "$repo_cm" env AQM_REQUIRE_ISSUE_REF=1 sh "$HOOK" "$repo_cm/msg.txt"

run_in "$repo_cm" git remote add origin 'https://github.invalid/example/repo.git'
expect_fail "commit-msg requires an issue reference when origin exists" \
  run_in "$repo_cm" env -u AQM_REQUIRE_ISSUE_REF sh "$HOOK" "$repo_cm/msg.txt"

for keyword in Fixes Closes Resolves; do
  write_msg "feat: add the workflow

$keyword #6"
  expect_fail "commit-msg rejects the $keyword auto-closing keyword" \
    run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"
done

write_msg 'feat: add the workflow

Closes #6'
expect_fail "commit-msg rejects the auto-closing keyword inline with other text" \
  run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: add the workflow

Fixes octo-org/octo-repo#6'
expect_fail "commit-msg rejects cross-repo OWNER/REPO#N auto-closing keyword" \
  run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: keep hash line

Refs #6

# Fixes #6'
expect_fail "commit-msg rejects hash-prefixed auto-closing keyword lines" \
  run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: use prefix #6 in test fixture

Refs #6'
expect_ok "commit-msg does not false-positive on prefix #6" \
  run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'Merge branch fixture into main'
: > "$repo_cm/.git/MERGE_HEAD"
expect_ok "commit-msg allows a real merge commit without an issue reference" \
  run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'Merge branch fixture into main

Fixes #6'
expect_fail "commit-msg still rejects auto-closing keywords on a real merge commit" \
  run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"
rm -f "$repo_cm/.git/MERGE_HEAD"

expect_fail "commit-msg still rejects auto-closing keywords even with AQM_SKIP_ISSUE_REF=1" \
  run_in "$repo_cm" env AQM_SKIP_ISSUE_REF=1 sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: bot change

Refs #6'
expect_fail "commit-msg rejects a bot commit without a non-bot Co-authored-by trailer" \
  run_in "$repo_cm" env \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  GIT_COMMITTER_NAME="Fixture Bot" GIT_COMMITTER_EMAIL="fixture@bots.invalid" \
  sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: bot change

Refs #6

Co-authored-by: Fixture Owner'
expect_fail "commit-msg rejects a malformed Co-authored-by trailer missing <email>" \
  run_in "$repo_cm" env \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  GIT_COMMITTER_NAME="Fixture Bot" GIT_COMMITTER_EMAIL="fixture@bots.invalid" \
  sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: bot change

Refs #6

Co-authored-by: Fixture Owner <4242+deji@users.noreply.github.com>'
expect_fail "commit-msg rejects a bot identity outside the .invalid TLD" \
  run_in "$repo_cm" env \
  GIT_AUTHOR_NAME="Fixture Bot [bot]" GIT_AUTHOR_EMAIL="4242+fixture@users.noreply.github.com" \
  GIT_COMMITTER_NAME="Fixture Bot [bot]" GIT_COMMITTER_EMAIL="4242+fixture@users.noreply.github.com" \
  sh "$HOOK" "$repo_cm/msg.txt"

write_msg 'feat: bot change

Refs #6

Co-authored-by: Fixture Owner <4242+deji@users.noreply.github.com>'
expect_ok "commit-msg accepts a .invalid bot identity with a human Co-authored-by" \
  run_in "$repo_cm" env \
  GIT_AUTHOR_NAME="Fixture Bot" GIT_AUTHOR_EMAIL="fixture@bots.invalid" \
  GIT_COMMITTER_NAME="Fixture Bot" GIT_COMMITTER_EMAIL="fixture@bots.invalid" \
  sh "$HOOK" "$repo_cm/msg.txt"

# Negative fixtures are assembled at run time: this repository must never
# contain a literal real email, non-synthetic UUID or API key, even inside a
# test that exists to reject one.
scan_case() {
  _scan_name=$1
  _scan_text=$2
  printf '%s\n\nRefs #6\n' "$_scan_text" > "$repo_cm/scan.txt"
  expect_fail "commit-msg rejects $_scan_name" \
    run_in "$repo_cm" sh "$HOOK" "$repo_cm/scan.txt"
}

jwt_head="eyJhbGciOiJIUzI1NiJ9"
jwt_body="eyJzdWIiOiIxMjM0NTY3ODkwIn0"

scan_case "a private key block" '-----BEGIN RSA '"PRIVATE KEY-----"
scan_case "a JSON web token" "${jwt_head}.${jwt_body}"
scan_case "an sk_live API key" "sk_live_""abcdefghijklmnopqrstuvwx"
scan_case "an AWS access key" "AKIA""IOSFODNN7EXAMPLE"
scan_case "a GitHub token" "ghp_""abcdefghijklmnopqrstuvwxyz0123456789"
scan_case "a GitHub fine-grained token" "github_pat_""abcdefghijklmnopqrstuvwxyz"
scan_case "an xAI key" "xai-""abcdefghijklmnopqrst"
scan_case "a Google API key" "AIza""abcdefghijklmnopqrstuvwxyz0123456789"
scan_case "an Anthropic key" "sk-ant-""abcdefghijklmnopqrstuvwx"
scan_case "a generic sk- key" "sk-""abcdefghijklmnopqrstuvwx"
scan_case "a GitLab token" "glpat-""abcdefghijklmnopqrstuvwx"
scan_case "a Slack token" "xoxb-""1234567890abcdef"
scan_case "a Google OAuth token" "ya29.""abcdefghijklmnopqrstuvwx"
scan_case "a macOS home path" "/Users/""fixture/notes.md"
scan_case "a Linux home path" "/home/""fixture/notes.md"
scan_case "a real email address" "someone@""realdomain.org"
scan_case "a non-synthetic UUID" "22222222-3333-""4444-5555-666666666666"

write_msg 'feat: synthetic identifiers

Refs #6
meter 00000000-0000-4000-8000-000000000000
bot fixture@bots.invalid human someone@example.test service noreply@github.com
owner 4242+deji@users.noreply.github.com test someone@example.invalid'
expect_ok "commit-msg allows synthetic identifiers and allowlisted mailboxes" \
  run_in "$repo_cm" sh "$HOOK" "$repo_cm/msg.txt"

printf '\n== scripts/check-title.sh ==\n'

repo_ct=$(new_repo check-title) || {
  printf 'cannot create a fixture repository\n' >&2
  exit 1
}

expect_ok "check-title accepts a full agent PR title" \
  "$CHECK_TITLE" --pr "[t3-code grok-4.7 high] Add the issue workflow"

expect_ok "check-title accepts the default effort" \
  "$CHECK_TITLE" --pr "[claude sonnet-4.6 default] Add the issue workflow"

expect_fail "check-title rejects a PR title without model and effort" \
  "$CHECK_TITLE" --pr "[t3-code] Add the issue workflow"

expect_fail "check-title rejects a PR title without effort" \
  "$CHECK_TITLE" --pr "[t3-code grok-4.7] Add the issue workflow"

expect_fail "check-title rejects an unknown effort" \
  "$CHECK_TITLE" --pr "[t3-code grok-4.7 urgent] Add the issue workflow"

expect_fail "check-title rejects an uppercase agent slug" \
  "$CHECK_TITLE" --pr "[T3-CODE grok-4.7 high] Add the issue workflow"

expect_ok "check-title accepts an agent-only issue title" \
  "$CHECK_TITLE" --issue "[t3-code] Add the issue workflow"

expect_ok "check-title accepts a full agent issue title" \
  "$CHECK_TITLE" --issue "[codex gpt-5.4 xhigh] Add the issue workflow"

expect_fail "check-title rejects a two-part agent issue title" \
  "$CHECK_TITLE" --issue "[t3-code grok-4.7] Add the issue workflow"

expect_fail "check-title rejects a human issue title when --issue forces agent mode" \
  "$CHECK_TITLE" --issue "Add the issue workflow"

expect_ok "check-title leaves a human PR title untouched" \
  "$CHECK_TITLE" --event pull_request --title "Add the issue workflow"

expect_ok "check-title leaves a human bracket title untouched" \
  "$CHECK_TITLE" --event issues --title "[Bugfix] Handle the null case"

expect_fail "check-title applies the PR shape when the body carries the agent footer" \
  "$CHECK_TITLE" --event pull_request --title "Add the issue workflow" \
  --body "Opened by T3 Code, Deji's build agent."

expect_ok "check-title accepts a valid agent title with the agent footer" \
  "$CHECK_TITLE" --event pull_request --title "[t3-code grok-4.7 high] Add the issue workflow" \
  --body "Opened by T3 Code, Deji's build agent."

title_base=$(run_in "$repo_ct" git rev-parse HEAD)
stage_change "$repo_ct" bot-commit-change
run_in "$repo_ct" git -c user.name="Fixture Bot" -c user.email="fixture@bots.invalid" \
  commit -q -m "chore: bot change" -m "Refs #6" \
  -m "Co-authored-by: Fixture Owner <4242+deji@users.noreply.github.com>"
title_head=$(run_in "$repo_ct" git rev-parse HEAD)

expect_fail "check-title enforces the agent title for a PR range containing a bot commit" \
  run_in "$repo_ct" "$CHECK_TITLE" --event pull_request --title "Add the issue workflow" \
  --base-sha "$title_base" --head-sha "$title_head"

human_base=$(run_in "$repo_ct" git rev-parse HEAD)
stage_change "$repo_ct" human-commit-change
run_in "$repo_ct" git commit -q -m "chore: human change" -m "Refs #6"
human_head=$(run_in "$repo_ct" git rev-parse HEAD)

expect_ok "check-title leaves a human PR range untouched" \
  run_in "$repo_ct" "$CHECK_TITLE" --event pull_request --title "Add the issue workflow" \
  --base-sha "$human_base" --head-sha "$human_head"

expect_ok "check-title accepts a provider-qualified model containing a slash" \
  "$CHECK_TITLE" --pr "[opencode opencode-go/deepseek-v4.1-flash high] Add the issue workflow"

expect_fail "check-title fails closed on an unresolvable commit range" \
  run_in "$repo_ct" "$CHECK_TITLE" --event pull_request --title "Add the issue workflow" \
  --base-sha "0000000000000000000000000000000000000000" \
  --head-sha "1111111111111111111111111111111111111111"

expect_fail "check-title fails closed when GITHUB_EVENT_PATH is missing for pull_request" \
  env GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$TMP_ROOT/does-not-exist.json" \
  "$CHECK_TITLE"

printf '{not valid json' > "$TMP_ROOT/corrupt-event.json"
expect_fail "check-title fails closed when GITHUB_EVENT_PATH is corrupt JSON" \
  env GITHUB_EVENT_NAME=issues GITHUB_EVENT_PATH="$TMP_ROOT/corrupt-event.json" \
  "$CHECK_TITLE"

printf '{"pull_request":{"title":"Human PR title","body":"Refs #6","base":{},"head":{}}}\n' > "$TMP_ROOT/missing-sha-pr.json"
expect_fail "check-title fails closed when pull_request event payload omits base/head SHAs" \
  env GITHUB_EVENT_NAME=pull_request GITHUB_EVENT_PATH="$TMP_ROOT/missing-sha-pr.json" \
  "$CHECK_TITLE"

expect_fail "check-title rejects a PR body containing an auto-closing keyword" \
  "$CHECK_TITLE" --event pull_request --title "Human PR title" \
  --body "Fixes #6"

expect_fail "check-title rejects a PR body containing a cross-repo auto-closing keyword" \
  "$CHECK_TITLE" --event pull_request --title "Human PR title" \
  --body "Closes: octo-org/octo-repo#100"

expect_ok "check-title ignores auto-closing keywords inside HTML comments in PR body" \
  "$CHECK_TITLE" --event pull_request --title "Human PR title" \
  --body "Refs #6 <!-- do not write Fixes #6 -->"

ac_base=$(run_in "$repo_ct" git rev-parse HEAD)
stage_change "$repo_ct" autoclose-commit
run_in "$repo_ct" git commit --no-verify -q -m "chore: Fixes #6"
ac_head=$(run_in "$repo_ct" git rev-parse HEAD)
expect_fail "check-title rejects a PR whose commits contain an auto-closing keyword" \
  run_in "$repo_ct" "$CHECK_TITLE" --event pull_request --title "Human PR title" \
  --base-sha "$ac_base" --head-sha "$ac_head"

printf '\n== scripts/gh-issue-workflow.sh ==\n'

repo_iw=$(new_repo issue-workflow) || {
  printf 'cannot create a fixture repository\n' >&2
  exit 1
}

: > "$AQM_GH_LOG"
expect_ok "gh-issue-workflow create posts a clean issue" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" create "Add the issue workflow" \
  "Track the GitHub Issue-first workflow and bot attribution."
assert_log_contains "gh-issue-workflow create passes the title to gh" "issue create --title Add the issue workflow"
assert_log_contains "gh-issue-workflow create passes the body to gh" "Track the GitHub Issue-first workflow"

: > "$AQM_GH_LOG"
printf 'Body read from a file.\n' > "$TMP_ROOT/body.md"
expect_ok "gh-issue-workflow create reads a body from an @filepath" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" create "Add the issue workflow" "@$TMP_ROOT/body.md" "docs,workflow"
assert_log_contains "gh-issue-workflow create reads the body file" "Body read from a file."
assert_log_contains "gh-issue-workflow create forwards labels" "--label docs,workflow"

: > "$AQM_GH_LOG"
expect_fail "gh-issue-workflow create blocks a local home path in the body" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" create "Add the issue workflow" \
  "See /Users/""fixture/notes.md for the plan."
assert_log_lacks "gh-issue-workflow create does not reach gh after a hygiene failure" "issue create"

: > "$AQM_GH_LOG"
expect_fail "gh-issue-workflow create enforces agent issue titles" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" create "[t3-code grok-4.7] Add the issue workflow" \
  "Opened by T3 Code, Deji's build agent."
assert_log_lacks "gh-issue-workflow create does not reach gh after a title failure" "issue create"

expect_ok "gh-issue-workflow create accepts a valid agent issue title" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" create "[t3-code] Add the issue workflow" \
  "Opened by T3 Code, Deji's build agent."

expect_fail "gh-issue-workflow create rejects an auto-closing keyword in the body" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" create "Add the issue workflow" \
  "This body Fixes #6."

: > "$AQM_GH_LOG"
for stage in implementation review; do
  expect_ok "gh-issue-workflow comment posts the $stage stage with agent attribution" \
    run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 "$stage" "Stage body for $stage." \
    --agent t3-code --model grok-4.7 --effort high
done
expect_ok "gh-issue-workflow comment posts a plan with UI evidence: none and attribution" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 plan "Plan body for plan.

UI evidence: none" --agent t3-code --model gemini-4-neon --effort high
assert_log_contains "gh-issue-workflow comment posts to the issue" "issue comment 6 --body"
assert_log_contains "gh-issue-workflow comment writes the plan header" "## [PLAN]"
assert_log_contains "gh-issue-workflow comment writes the implementation header" "## [IMPLEMENTATION]"
assert_log_contains "gh-issue-workflow comment writes the review header" "## [REVIEW]"
assert_log_contains "gh-issue-workflow comment records agent, model and effort" \
  '_Contributor: `t3-code` (`grok-4.7`, effort: `high`)_'

expect_ok "gh-issue-workflow comment falls back to AQM_AGENT/AQM_MODEL/AQM_EFFORT" \
  run_in "$repo_iw" env AQM_AGENT=codex AQM_MODEL=gpt-5.4 AQM_EFFORT=xhigh \
  "$ISSUE_WORKFLOW" comment 6 review "Attribution from the environment."
assert_log_contains "gh-issue-workflow comment uses the environment attribution" \
  '_Contributor: `codex` (`gpt-5.4`, effort: `xhigh`)_'

expect_ok "gh-issue-workflow comment supports human attribution" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 review "Human review notes." --human
assert_log_contains "gh-issue-workflow comment records the human contributor" '_Contributor: human_'

expect_fail "gh-issue-workflow comment rejects missing attribution" \
  run_in "$repo_iw" env -u AQM_AGENT -u AQM_MODEL -u AQM_EFFORT \
  "$ISSUE_WORKFLOW" comment 6 plan "No attribution at all.

UI evidence: none"

expect_fail "gh-issue-workflow comment rejects --human mixed with --agent" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 plan "Mixed attribution.

UI evidence: none" --human --agent t3-code

expect_fail "gh-issue-workflow comment rejects a bad stage" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 retrospective "Bad stage." --agent t3-code --model grok-4.7

expect_fail "gh-issue-workflow comment rejects a non-numeric issue number" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment "#6" plan "Bad number." --agent t3-code --model grok-4.7

: > "$AQM_GH_LOG"
expect_ok "gh-issue-workflow comment strips a duplicate stage heading" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 plan "## [PLAN]

Real plan body.

UI evidence: none" --agent t3-code --model grok-4.7
header_count=$(grep -o '## \[PLAN\]' "$AQM_GH_LOG" | wc -l | tr -d ' ')
if [ "$header_count" = "1" ]; then
  ok "gh-issue-workflow comment writes exactly one stage heading"
else
  no "gh-issue-workflow comment writes exactly one stage heading" "found $header_count"
fi

: > "$AQM_GH_LOG"
expect_fail "gh-issue-workflow comment blocks a leaked identifier" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 testing \
  "Leaked meter 22222222-3333-""4444-5555-666666666666" --agent t3-code --model grok-4.7
assert_log_lacks "gh-issue-workflow comment does not reach gh after a hygiene failure" "issue comment"

: > "$AQM_GH_LOG"
expect_fail "gh-issue-workflow comment rejects an auto-closing keyword" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 plan \
  "This plan Fixes #6 directly.

UI evidence: none" --agent t3-code --model grok-4.7
assert_log_lacks "gh-issue-workflow comment does not reach gh after an auto-close failure" "issue comment"

: > "$AQM_GH_LOG"
expect_fail "gh-issue-workflow comment rejects a cross-repo auto-closing keyword" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 plan \
  "Fixes octo-org/octo-repo#6

UI evidence: none" --agent t3-code --model grok-4.7
assert_log_lacks "gh-issue-workflow comment does not reach gh after cross-repo auto-close" "issue comment"

: > "$AQM_GH_LOG"
expect_fail "gh-issue-workflow comment rejects a hash-prefixed auto-closing heading" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" comment 6 plan \
  "# Fixes #6

UI evidence: none" --agent t3-code --model grok-4.7
assert_log_lacks "gh-issue-workflow comment does not reach gh after hash-prefixed auto-close" "issue comment"

# --- before-shot + attachments + attribution ------------------------------

evidence_root="$TMP_ROOT/evidence"
mkdir -p "$evidence_root"
run_in "$evidence_root" git init -q . || exit 1
run_in "$evidence_root" git config user.name "Fixture Human" || exit 1
run_in "$evidence_root" git config user.email "human@example.com" || exit 1
printf 'base\n' > "$evidence_root/README"
run_in "$evidence_root" git add README || exit 1
run_in "$evidence_root" git commit -q --no-verify -m "base" || exit 1
run_in "$evidence_root" git branch -M main || exit 1

printf 'synthetic-before-bytes\n' > "$TMP_ROOT/before.png"
printf 'synthetic-after-bytes\n' > "$TMP_ROOT/after.png"

expect_ok "gh-issue-workflow before-shot registers the pre-change screenshot" \
  run_in "$repo_iw" env AQM_UI_EVIDENCE_ROOT="$evidence_root" \
  AQM_UI_EVIDENCE_PLAN="UI evidence: before-and-after" \
  "$ISSUE_WORKFLOW" before-shot 7 --attach "$TMP_ROOT/before.png#Before: old screen"

: > "$AQM_GH_LOG"
expect_ok "gh-issue-workflow testing comment uploads Before+After with attribution" \
  run_in "$repo_iw" env AQM_UI_EVIDENCE_ROOT="$evidence_root" \
  AQM_UI_EVIDENCE_PLAN="UI evidence: before-and-after" \
  "$ISSUE_WORKFLOW" comment 7 testing "Verified on the emulator." \
  --attach "$TMP_ROOT/after.png#After: new screen" \
  --attach "$TMP_ROOT/before.png#Before: old screen" \
  --agent t3-code --model gemini-4-neon --effort high
assert_log_contains "gh-issue-workflow testing comment records the contributed attribution" \
  '_Contributor: `t3-code` (`gemini-4-neon`, effort: `high`)_'
assert_log_contains "gh-issue-workflow testing comment forwards an attachment" "--attach"

# --- issue verification ---------------------------------------------------

cat > "$TMP_ROOT/issue-complete.json" <<'JSON'
{"number":6,"title":"[t3-code] Add the issue workflow","state":"OPEN","comments":[
{"body":"## [PLAN]\n\n_Contributor: `t3-code` (`gemini-4-neon`, effort: `high`)_\n\nplan body"},
{"body":"## [IMPLEMENTATION]\n\n_Contributor: `opencode` (`deepseek-v4.1-flash`, effort: `high`)_\n\nimplementation body"},
{"body":"## [TESTING]\n\n_Contributor: `t3-code` (`gemini-4-neon`, effort: `high`)_\n\ntesting body"},
{"body":"## [REVIEW]\n\n_Contributor: `grok` (`grok-4.7`, effort: `high`)_\n\nreview body"}]}
JSON

cat > "$TMP_ROOT/issue-incomplete.json" <<'JSON'
{"number":6,"title":"[t3-code] Add the issue workflow","state":"OPEN","comments":[
{"body":"## [PLAN]\n\n_Contributor: human_\n\nplan body"},
{"body":"## [IMPLEMENTATION]\n\n_Contributor: human_\n\nimplementation body"},
{"body":"## [REVIEW]\n\n_Contributor: human_\n\nreview body"}]}
JSON

cat > "$TMP_ROOT/issue-stuffed-single.json" <<'JSON'
{"number":6,"title":"[t3-code] Add the issue workflow","state":"OPEN","comments":[
{"body":"## [PLAN]\n\n_Contributor: human_\n\n## [IMPLEMENTATION]\n\n## [TESTING]\n\n## [REVIEW]"}]}
JSON

cat > "$TMP_ROOT/issue-unattributed.json" <<'JSON'
{"number":6,"title":"[t3-code] Add the issue workflow","state":"OPEN","comments":[
{"body":"## [PLAN]\n\nplan body without contributor"},
{"body":"## [IMPLEMENTATION]\n\n_Contributor: human_\n\nimplementation body"},
{"body":"## [TESTING]\n\n_Contributor: human_\n\ntesting body"},
{"body":"## [REVIEW]\n\n_Contributor: human_\n\nreview body"}]}
JSON

export AQM_GH_ISSUE_JSON="$TMP_ROOT/issue-complete.json"
expect_ok "gh-issue-workflow verify passes when all four attributed stage comments exist" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" verify 6

AQM_GH_ISSUE_JSON="$TMP_ROOT/issue-incomplete.json"
export AQM_GH_ISSUE_JSON
expect_fail "gh-issue-workflow verify fails when a stage is missing" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" verify 6

AQM_GH_ISSUE_JSON="$TMP_ROOT/issue-stuffed-single.json"
export AQM_GH_ISSUE_JSON
expect_fail "gh-issue-workflow verify rejects a single comment stuffing all four stage headings" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" verify 6

AQM_GH_ISSUE_JSON="$TMP_ROOT/issue-unattributed.json"
export AQM_GH_ISSUE_JSON
expect_fail "gh-issue-workflow verify rejects a stage comment missing a _Contributor: line" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" verify 6

: > "$AQM_GH_LOG"
expect_fail "gh-issue-workflow close refuses an incomplete issue" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" close 6
assert_log_lacks "gh-issue-workflow close does not call gh issue close when incomplete" "issue close"

AQM_GH_ISSUE_JSON="$TMP_ROOT/issue-complete.json"
export AQM_GH_ISSUE_JSON
: > "$AQM_GH_LOG"
expect_ok "gh-issue-workflow close closes a verified issue" \
  run_in "$repo_iw" "$ISSUE_WORKFLOW" close 6
assert_log_contains "gh-issue-workflow close calls gh issue close" "issue close 6"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
exit 0
