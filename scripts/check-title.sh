#!/usr/bin/env bash
# scripts/check-title.sh — enforce agent (bot) title conventions on PRs/issues.
#
# Every agent-authored pull request and issue carries its identity in the title:
#
#   PR    [<agent> <model> <effort>] <summary>
#   issue [<agent>] <summary>            or   [<agent> <model> <effort>] <summary>
#
# Effort is one of low, medium, high, xhigh, default. Titles written by humans
# are left alone: the check only applies when the title, the body footer, or a
# bot-authored commit in the PR shows that an agent opened it.
#
# CI use: reads GITHUB_EVENT_NAME and GITHUB_EVENT_PATH.
# Local use:
#   scripts/check-title.sh --pr "[t3-code grok-4.7 high] Add the workflow"
#   scripts/check-title.sh --issue "[t3-code] Add the workflow"
#   scripts/check-title.sh --event pull_request --title "..." --body "..." \
#       --base-sha <sha> --head-sha <sha> [--bot-commit] [--force-agent]
#
# Environment:
#   AQM_BASE_SHA / AQM_HEAD_SHA   commit range inspected on a pull request
#   AQM_CT_*                      set by this script for the embedded checker

set -u

mode=""
title=""
body=""
bot_commit=0
force_agent=0
base_sha="${AQM_BASE_SHA:-}"
head_sha="${AQM_HEAD_SHA:-}"
event_name="${GITHUB_EVENT_NAME:-}"
event_path="${GITHUB_EVENT_PATH:-}"

usage() {
  cat <<'USAGE'
usage: check-title.sh [--pr TITLE | --issue TITLE]
                      [--event pull_request|issues --title TITLE] [--body BODY]
                      [--bot-commit] [--force-agent]
                      [--base-sha SHA --head-sha SHA]
USAGE
}

need_value() {
  if [ "$2" -lt 1 ]; then
    printf 'check-title: %s needs a value\n' "$1" >&2
    exit 2
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --pr)
      need_value "$1" $(( $# - 1 ))
      mode="pr"
      title=$2
      force_agent=1
      shift 2
      ;;
    --pr=*)
      mode="pr"
      title=${1#*=}
      force_agent=1
      shift
      ;;
    --issue)
      need_value "$1" $(( $# - 1 ))
      mode="issue"
      title=$2
      force_agent=1
      shift 2
      ;;
    --issue=*)
      mode="issue"
      title=${1#*=}
      force_agent=1
      shift
      ;;
    --event)
      need_value "$1" $(( $# - 1 ))
      event_name=$2
      case "$event_name" in
        pull_request) mode="pr" ;;
        issues) mode="issue" ;;
        *) mode="" ;;
      esac
      shift 2
      ;;
    --event=*)
      event_name=${1#*=}
      case "$event_name" in
        pull_request) mode="pr" ;;
        issues) mode="issue" ;;
        *) mode="" ;;
      esac
      shift
      ;;
    --title)
      need_value "$1" $(( $# - 1 ))
      title=$2
      shift 2
      ;;
    --title=*)
      title=${1#*=}
      shift
      ;;
    --body)
      need_value "$1" $(( $# - 1 ))
      body=$2
      shift 2
      ;;
    --body=*)
      body=${1#*=}
      shift
      ;;
    --base-sha)
      need_value "$1" $(( $# - 1 ))
      base_sha=$2
      shift 2
      ;;
    --head-sha)
      need_value "$1" $(( $# - 1 ))
      head_sha=$2
      shift 2
      ;;
    --bot-commit)
      bot_commit=1
      shift
      ;;
    --force-agent)
      force_agent=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'check-title: unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

# Derive the mode from the CI event when nothing on the command line set it.
if [ -z "$mode" ]; then
  case "$event_name" in
    pull_request) mode="pr" ;;
    issues) mode="issue" ;;
    *) mode="" ;;
  esac
fi

# Nothing to validate: a human push, workflow_dispatch, or a local no-argument
# run. Never fail an unrelated job.
if [ -z "$mode" ]; then
  exit 0
fi

AQM_CT_MODE="$mode"
AQM_CT_TITLE="$title"
AQM_CT_BODY="$body"
AQM_CT_FORCE="$force_agent"
AQM_CT_BOT_COMMIT="$bot_commit"
AQM_CT_BASE="$base_sha"
AQM_CT_HEAD="$head_sha"
AQM_CT_EVENT_NAME="$event_name"
AQM_CT_EVENT_PATH="$event_path"
export AQM_CT_MODE AQM_CT_TITLE AQM_CT_BODY AQM_CT_FORCE AQM_CT_BOT_COMMIT
export AQM_CT_BASE AQM_CT_HEAD AQM_CT_EVENT_NAME AQM_CT_EVENT_PATH

python3 - <<'PY'
import json
import os
import re
import subprocess
import sys

EFFORTS = {"low", "medium", "high", "xhigh", "default"}
AGENT_SLUGS = {
    "t3-code",
    "claude",
    "codex",
    "opencode",
    "opencode2",
    "grok",
    "gemini",
    "agy",
    "antigravity",
    "wendy",
    "pi",
}

PR_TITLE = re.compile(
    r"^\[[a-z0-9][a-z0-9-]* [a-z0-9][a-z0-9._/-]* (low|medium|high|xhigh|default)\] .+$"
)
ISSUE_TITLE = re.compile(
    r"^\[[a-z0-9][a-z0-9-]*( [a-z0-9][a-z0-9._/-]* (low|medium|high|xhigh|default))?\] .+$"
)
FOOTER = re.compile(r"Opened by .*Deji's build (agent|bot)\.", re.IGNORECASE)
HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
AUTOCLOSE_RE = re.compile(
    r"(?i)(?:^|[^A-Za-z0-9_])(close|closes|closed|fix|fixes|fixed|resolve|resolves|resolved):?[ \t]+(?:[A-Za-z0-9._-]+/[A-Za-z0-9._-]+)?#[0-9]+"
)


def env(name, default=""):
    value = os.environ.get(name)
    return default if value is None else value


def load_event(path, name):
    if not path or not os.path.isfile(path):
        print(
            "check-title: GITHUB_EVENT_PATH is missing or unreadable for event '%s'" % name,
            file=sys.stderr,
        )
        sys.exit(1)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            event = json.load(handle)
    except (OSError, ValueError) as exc:
        print("check-title: failed to parse event payload: %s" % exc, file=sys.stderr)
        sys.exit(1)
    if not isinstance(event, dict):
        print("check-title: event payload is not a JSON object", file=sys.stderr)
        sys.exit(1)
    if name == "pull_request":
        pr = event.get("pull_request")
        if not isinstance(pr, dict):
            print("check-title: pull_request object missing from event payload", file=sys.stderr)
            sys.exit(1)
        pr_base = (pr.get("base") or {}).get("sha") or ""
        pr_head = (pr.get("head") or {}).get("sha") or ""
        if not pr_base or not pr_head:
            print("check-title: pull_request event payload is missing base or head SHA", file=sys.stderr)
            sys.exit(1)
        return {
            "mode": "pr",
            "title": pr.get("title") or "",
            "body": pr.get("body") or "",
            "base": pr_base,
            "head": pr_head,
        }
    if name == "issues":
        issue = event.get("issue")
        if not isinstance(issue, dict):
            print("check-title: issue object missing from event payload", file=sys.stderr)
            sys.exit(1)
        return {
            "mode": "issue",
            "title": issue.get("title") or "",
            "body": issue.get("body") or "",
            "base": "",
            "head": "",
        }
    return None


mode = env("AQM_CT_MODE")
title = env("AQM_CT_TITLE")
body = env("AQM_CT_BODY")
force_agent = env("AQM_CT_FORCE") == "1"
bot_commit = env("AQM_CT_BOT_COMMIT") == "1"
base = env("AQM_CT_BASE")
head = env("AQM_CT_HEAD")
event_name = env("AQM_CT_EVENT_NAME")
event_path = env("AQM_CT_EVENT_PATH")

event = None
if not title:
    # CI supplies the title through the event payload; an explicit --title wins.
    if event_name in ("pull_request", "issues"):
        event = load_event(event_path, event_name)
if event is not None:
    if not mode:
        mode = event["mode"]
    title = event["title"]
    if not body:
        body = event["body"]
    base = base or event["base"]
    head = head or event["head"]

if not mode:
    sys.exit(0)


def has_autoclose(text):
    cleaned = HTML_COMMENT_RE.sub("", text or "")
    return bool(AUTOCLOSE_RE.search(cleaned))


def is_bot_shaped(name, email):
    name = (name or "").lower()
    email = (email or "").lower()
    if email.endswith("@bots.invalid") or "[bot]" in email:
        return True
    return "[bot]" in name or "(bot)" in name


SHA_RE = re.compile(r"^[0-9a-fA-F]{40,64}$")


def inspect_pr_commits(base_sha, head_sha):
    """Return (has_bot, autoclose_shas) for base_sha..head_sha. Exits 1 on error."""
    if not base_sha and not head_sha:
        return False, []
    if not base_sha or not head_sha:
        print("check-title: base and head SHA must both be set", file=sys.stderr)
        sys.exit(1)
    for label, sha in (("base", base_sha), ("head", head_sha)):
        if not SHA_RE.match(sha):
            print("check-title: invalid %s SHA: %s" % (label, sha), file=sys.stderr)
            sys.exit(1)
    try:
        for sha in (base_sha, head_sha):
            verified = subprocess.run(
                ["git", "rev-parse", "--verify", "--quiet", "--end-of-options", sha + "^{commit}"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            if verified.returncode != 0:
                print("check-title: commit %s is missing locally; cannot inspect PR commits" % sha, file=sys.stderr)
                sys.exit(1)
    except OSError as exc:
        print("check-title: cannot run git to inspect PR commits: %s" % exc, file=sys.stderr)
        sys.exit(1)
    try:
        log = subprocess.run(
            ["git", "log", "-z", "--format=%H%x1f%an%x1f%ae%x1f%cn%x1f%ce%x1f%B",
             "--end-of-options", base_sha + ".." + head_sha],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        print("check-title: cannot run git log to inspect PR commits: %s" % exc, file=sys.stderr)
        sys.exit(1)
    if log.returncode != 0:
        print("check-title: git log failed for %s..%s" % (base_sha, head_sha), file=sys.stderr)
        sys.exit(1)
    has_bot = False
    autoclose_shas = []
    for record in log.stdout.split(b"\0"):
        if not record.strip():
            continue
        line = record.decode("utf-8", "replace")
        fields = line.split("\x1f", 5)
        if len(fields) != 6:
            print("check-title: malformed git log record", file=sys.stderr)
            sys.exit(1)
        commit_sha, an, ae, cn, ce, commit_body = fields
        if is_bot_shaped(an, ae) or is_bot_shaped(cn, ce):
            has_bot = True
        if has_autoclose(commit_body):
            autoclose_shas.append(commit_sha[:12])
    return has_bot, autoclose_shas


def title_looks_agent(title_text):
    match = re.match(r"^\[([^\]]*)\]", title_text)
    if not match:
        return False
    parts = match.group(1).split()
    if not parts:
        return False
    if parts[0].lower() in AGENT_SLUGS:
        return True
    if (
        len(parts) == 3
        and re.match(r"^[a-z0-9][a-z0-9-]*$", parts[0])
        and parts[2].lower() in EFFORTS
    ):
        return True
    return False


has_bot_range = False
autoclose_commits = []
if mode == "pr" and (base or head):
    has_bot_range, autoclose_commits = inspect_pr_commits(base, head)

if mode == "pr":
    if has_autoclose(body):
        print(
            "check-title: pull request body contains a GitHub auto-closing keyword on an issue number; "
            "use 'Refs #<number>' instead so the issue closes via ./scripts/gh-issue-workflow.sh close",
            file=sys.stderr,
        )
        sys.exit(1)
    if autoclose_commits:
        print(
            "check-title: commit(s) %s contain a GitHub auto-closing keyword on an issue number; "
            "use 'Refs #<number>' instead so the issue closes via ./scripts/gh-issue-workflow.sh close"
            % ", ".join(autoclose_commits),
            file=sys.stderr,
        )
        sys.exit(1)

active = (
    force_agent
    or bot_commit
    or bool(FOOTER.search(body))
    or title_looks_agent(title)
    or has_bot_range
)

if not active:
    print("check-title: no agent markers found; leaving the title untouched")
    sys.exit(0)

if mode == "pr":
    pattern = PR_TITLE
    expected = "[<agent> <model> <effort>] <summary>"
    example = "[t3-code grok-4.7 high] Add the GitHub Issue workflow"
else:
    pattern = ISSUE_TITLE
    expected = "[<agent>] <summary> or [<agent> <model> <effort>] <summary>"
    example = "[t3-code] Add the GitHub Issue workflow"

if pattern.match(title):
    print("check-title: ok — agent title matches %s" % expected)
    sys.exit(0)

print("check-title: agent-authored title does not match the required shape", file=sys.stderr)
print("  expected: %s" % expected, file=sys.stderr)
print("  example:  %s" % example, file=sys.stderr)
print("  got:      %s" % title, file=sys.stderr)
print("  effort:   low | medium | high | xhigh | default", file=sys.stderr)
sys.exit(1)
PY
