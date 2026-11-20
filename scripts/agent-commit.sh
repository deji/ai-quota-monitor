#!/usr/bin/env bash
# scripts/agent-commit.sh — commit helper for build agents (bots).
#
# A bot commit is never anonymous. This helper:
#   * requires an explicit bot identity in the reserved `.invalid` TLD,
#   * refuses unsafe `git commit` options that would rewrite or skip the hooks,
#   * appends a `Co-authored-by:` trailer naming the human owner,
#   * commits through `git commit`, so `.githooks/commit-msg` still runs.
#
# usage:
#   scripts/agent-commit.sh (--author-name NAME --author-email EMAIL |
#                            GIT_AUTHOR_NAME=NAME GIT_AUTHOR_EMAIL=EMAIL)
#                           (-m MESSAGE [-m MESSAGE ...] | -F FILE)
#                           [git commit options] [-- pathspec ...]
#
# Environment:
#   GIT_AUTHOR_NAME / GIT_AUTHOR_EMAIL        bot identity (must be a pair)
#   GIT_COMMITTER_NAME / GIT_COMMITTER_EMAIL  optional different committer (pair)
#   AQM_CO_AUTHOR_NAME                        owner display name
#                                             (default: Oladeji Famakinwa)
#   AQM_CO_AUTHOR_EMAIL                       owner address; resolved through
#                                             `gh api user` when unset
#   AQM_EXPECTED_OWNER_LOGIN                  expected GitHub login (default: deji)
#
# The bot email must end in `.invalid` and must not be a GitHub noreply address.
# Examples: `t3-code@bots.invalid`, `claude-opus@bots.invalid`.

set -u
set -o pipefail

usage() {
  cat <<'USAGE'
usage:
  scripts/agent-commit.sh (--author-name NAME --author-email EMAIL |
                           GIT_AUTHOR_NAME=NAME GIT_AUTHOR_EMAIL=EMAIL)
                          (-m MESSAGE [-m MESSAGE ...] | -F FILE)
                          [git commit options] [-- pathspec ...]

Environment:
  GIT_AUTHOR_NAME / GIT_AUTHOR_EMAIL        bot identity (must be a pair)
  GIT_COMMITTER_NAME / GIT_COMMITTER_EMAIL  optional different committer (pair)
  AQM_CO_AUTHOR_NAME                        owner display name
  AQM_CO_AUTHOR_EMAIL                       owner address; resolved through
                                            `gh api user` when unset
  AQM_EXPECTED_OWNER_LOGIN                  expected GitHub login (default: deji)

The bot email must end in `.invalid` and must not be a GitHub noreply address.
Examples: `t3-code@bots.invalid`, `claude-opus@bots.invalid`.
USAGE
}

die() {
  printf 'agent-commit: %s\n' "$1" >&2
  exit 1
}

author_name_arg=""
author_email_arg=""
msg_file=""
msg_args=0
forwarded=()
clean_tmp=""
final_tmp=""

# Remove every temp file this run creates, however it ends.
cleanup() {
  if [ -n "${msg_file:-}" ]; then rm -f "$msg_file"; fi
  if [ -n "${clean_tmp:-}" ]; then rm -f "$clean_tmp"; fi
  if [ -n "${final_tmp:-}" ]; then rm -f "$final_tmp"; fi
  return 0
}
trap cleanup EXIT INT TERM HUP

new_tmp() {
  mktemp "${TMPDIR:-/tmp}/aqm-agent-commit-$1.XXXXXX"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --author-name)
      [ $# -ge 2 ] || die "--author-name needs a value"
      author_name_arg=$2
      shift 2
      ;;
    --author-name=*)
      author_name_arg=${1#*=}
      shift
      ;;
    --author-email)
      [ $# -ge 2 ] || die "--author-email needs a value"
      author_email_arg=$2
      shift 2
      ;;
    --author-email=*)
      author_email_arg=${1#*=}
      shift
      ;;
    -m|--message)
      [ $# -ge 2 ] || die "$1 needs a value"
      [ -n "$msg_file" ] || msg_file=$(new_tmp msg) || die "cannot create a temp file"
      printf '%s\n\n' "$2" >> "$msg_file"
      msg_args=1
      shift 2
      ;;
    -m?*)
      [ -n "$msg_file" ] || msg_file=$(new_tmp msg) || die "cannot create a temp file"
      printf '%s\n\n' "${1#-m}" >> "$msg_file"
      msg_args=1
      shift
      ;;
    --message=*)
      [ -n "$msg_file" ] || msg_file=$(new_tmp msg) || die "cannot create a temp file"
      printf '%s\n\n' "${1#*=}" >> "$msg_file"
      msg_args=1
      shift
      ;;
    -F|--file)
      [ $# -ge 2 ] || die "$1 needs a value"
      [ -f "$2" ] || die "message file not found: $2"
      [ -n "$msg_file" ] || msg_file=$(new_tmp msg) || die "cannot create a temp file"
      cat "$2" >> "$msg_file"
      printf '\n' >> "$msg_file"
      msg_args=1
      shift 2
      ;;
    --file=*)
      [ -f "${1#*=}" ] || die "message file not found: ${1#*=}"
      [ -n "$msg_file" ] || msg_file=$(new_tmp msg) || die "cannot create a temp file"
      cat "${1#*=}" >> "$msg_file"
      printf '\n' >> "$msg_file"
      msg_args=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -a|--all|--allow-empty|-q|--quiet|--dry-run|-v|--verbose)
      forwarded+=("$1")
      shift
      ;;
    --)
      # Everything after `--` is a pathspec and is forwarded verbatim.
      forwarded+=("--")
      shift
      while [ $# -gt 0 ]; do
        forwarded+=("$1")
        shift
      done
      ;;
    -*)
      die "refusing to forward unapproved git commit option: $1 (allowed: -a, --all, --allow-empty, -q, --quiet, --dry-run, -v, --verbose)"
      ;;
    *)
      forwarded+=("$1")
      shift
      ;;
  esac
done

[ "$msg_args" -eq 1 ] || die "a commit message is required (-m MESSAGE or -F FILE)"

msg_tmp=$msg_file
clean_tmp=$(new_tmp clean) || die "cannot create a temp file"
final_tmp=$(new_tmp final) || die "cannot create a temp file"

[ -s "$msg_tmp" ] || die "the commit message is empty"

# --- identity -------------------------------------------------------------

if [ -n "$author_name_arg" ] && [ -z "$author_email_arg" ]; then
  die "--author-name requires --author-email"
fi
if [ -z "$author_name_arg" ] && [ -n "$author_email_arg" ]; then
  die "--author-email requires --author-name"
fi

author_name=${author_name_arg:-${GIT_AUTHOR_NAME:-}}
author_email=${author_email_arg:-${GIT_AUTHOR_EMAIL:-}}

if [ -z "$author_name_arg" ] && [ -z "$author_email_arg" ]; then
  if [ -n "${GIT_AUTHOR_NAME:-}" ] && [ -z "${GIT_AUTHOR_EMAIL:-}" ]; then
    die "GIT_AUTHOR_NAME is set without GIT_AUTHOR_EMAIL; pass the bot identity as a pair"
  fi
  if [ -z "${GIT_AUTHOR_NAME:-}" ] && [ -n "${GIT_AUTHOR_EMAIL:-}" ]; then
    die "GIT_AUTHOR_EMAIL is set without GIT_AUTHOR_NAME; pass the bot identity as a pair"
  fi
fi

if [ -z "$author_name" ] || [ -z "$author_email" ]; then
  die "explicit bot identity required: pass --author-name/--author-email or set GIT_AUTHOR_NAME/GIT_AUTHOR_EMAIL"
fi

if [ -n "${GIT_COMMITTER_NAME:-}" ] && [ -n "${GIT_COMMITTER_EMAIL:-}" ]; then
  committer_name=$GIT_COMMITTER_NAME
  committer_email=$GIT_COMMITTER_EMAIL
elif [ -n "${GIT_COMMITTER_NAME:-}" ] || [ -n "${GIT_COMMITTER_EMAIL:-}" ]; then
  die "GIT_COMMITTER_NAME and GIT_COMMITTER_EMAIL must be set together"
else
  committer_name=$author_name
  committer_email=$author_email
fi

validate_name() {
  _name=$1
  _which=$2
  [ -n "$_name" ] || die "$_which name is empty"
  case "$_name" in
    *'<'*|*'>'*) die "$_which name must not contain '<' or '>'" ;;
  esac
  if printf '%s' "$_name" | grep -q '[[:cntrl:]]'; then
    die "$_which name must not contain control characters"
  fi
  _trimmed=$(printf '%s' "$_name" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  [ -n "$_trimmed" ] || die "$_which name is blank"
}

validate_bot_email() {
  _email=$1
  _which=$2
  case "$_email" in
    *@users.noreply.github.com|noreply@github.com)
      die "$_which email must not be a GitHub noreply address; use <agent>@bots.invalid"
      ;;
  esac
  case "$_email" in
    *.invalid) ;;
    *) die "$_which email '$1' must end in .invalid (for example <agent>@bots.invalid)" ;;
  esac
  if ! printf '%s' "$_email" | grep -Eq '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.invalid$'; then
    die "$_which email is not a valid address"
  fi
}

validate_name "$author_name" "author"
validate_bot_email "$author_email" "author"
validate_name "$committer_name" "committer"
validate_bot_email "$committer_email" "committer"

# --- owner co-author ------------------------------------------------------

owner_name=${AQM_CO_AUTHOR_NAME:-Oladeji Famakinwa}
owner_email=${AQM_CO_AUTHOR_EMAIL:-}

if [ -z "$owner_email" ]; then
  gh_out=$(gh api user --jq '[.id, .login] | @tsv' 2>/dev/null) || die "cannot resolve the owner co-author: set AQM_CO_AUTHOR_EMAIL, or authenticate gh so 'gh api user' works"
  gh_id=$(printf '%s\n' "$gh_out" | awk 'NR == 1 { print $1 }')
  gh_login=$(printf '%s\n' "$gh_out" | awk 'NR == 1 { print $2 }')
  case "$gh_id" in
    ''|*[!0-9]*) die "gh api user did not return a numeric account id" ;;
  esac
  [ -n "$gh_login" ] || die "gh api user did not return a login"
  expected_login=${AQM_EXPECTED_OWNER_LOGIN:-deji}
  if [ "$gh_login" != "$expected_login" ]; then
    die "gh account '$gh_login' is not the expected owner '$expected_login'; refusing to attribute the commit"
  fi
  owner_email="${gh_id}+${gh_login}@users.noreply.github.com"
fi

validate_name "$owner_name" "owner co-author"
if ! printf '%s' "$owner_email" | grep -Eq '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$'; then
  die "owner co-author email is not a valid address"
fi

# --- message --------------------------------------------------------------

git stripspace < "$msg_tmp" > "$clean_tmp" || die "cannot normalize the commit message"
[ -s "$clean_tmp" ] || die "the commit message is empty"

if ! git interpret-trailers --if-exists addIfDifferent --where end \
    --trailer "Co-authored-by: ${owner_name} <${owner_email}>" "$clean_tmp" > "$final_tmp"; then
  die "cannot append the Co-authored-by trailer"
fi

# Never allow ambient skip flags to bypass the commit-msg guard.
unset AQM_SKIP_ISSUE_REF AQM_SKIP_ATTRIBUTION

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT_REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REPO_TOP=$(git rev-parse --show-toplevel 2>/dev/null || true)

# Validate the composed message directly with the tracked commit-msg hook so
# even a checkout without core.hooksPath configured cannot skip it.
if [ -f "$SCRIPT_REPO_ROOT/.githooks/commit-msg" ]; then
  GIT_AUTHOR_NAME="$author_name" \
  GIT_AUTHOR_EMAIL="$author_email" \
  GIT_COMMITTER_NAME="$committer_name" \
  GIT_COMMITTER_EMAIL="$committer_email" \
  sh "$SCRIPT_REPO_ROOT/.githooks/commit-msg" "$final_tmp" || exit $?
fi

git_hook_opts=()
if [ -n "$REPO_TOP" ] && [ -d "$REPO_TOP/.githooks" ]; then
  git_hook_opts=(-c "core.hooksPath=$REPO_TOP/.githooks")
fi

GIT_AUTHOR_NAME="$author_name" \
GIT_AUTHOR_EMAIL="$author_email" \
GIT_COMMITTER_NAME="$committer_name" \
GIT_COMMITTER_EMAIL="$committer_email" \
git ${git_hook_opts[@]+"${git_hook_opts[@]}"} commit -F "$final_tmp" ${forwarded[@]+"${forwarded[@]}"}
