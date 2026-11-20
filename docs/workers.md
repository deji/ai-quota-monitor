# Working with Supervised Workers (T3 Code & Orca Orchestration)

Playbook for coordinating planning, implementation, testing, and code reviews in `ai-quota-monitor` from either **T3 Code** (`delegate_task`, global `t3-cheap-workers` skill) or **Orca Orchestration** (`orca` CLI, global `orca-cheap-workers` skill). Each harness has its own model matrix and option schema below.

---

## 1. Role & Model Matrix

Who plans, implements, and reviews is governed by [`AGENTS.md`](../AGENTS.md) and the active global skill (`t3-cheap-workers` in T3 Code; `orca-cheap-workers` in Orca). One branch/worktree per issue, one writer at a time, and a delegated child or planner worker does not start other workers.

### T3 Code (`t3-cheap-workers`, `delegate_task`)

Active for non-trivial work when running as `antigravity` / `gemini-4-neon` or `grok` / `grok-4.7` (medium or high). The coordinator calls `orchestrator_capabilities` once per thread, reads quota windows from `~/.t3/caches/{opencode,antigravity,grok}.json`, **writes the plan itself**, and delegates only **Implement** and **Review** via `delegate_task`. Never dispatch `xhigh`.

- Brief goes in `task` (there is no `prompt` field). `target.providerInstanceId` and `target.model` are separate fields.
- Provider-specific `target.options` keys from `orchestrator_capabilities`: OpenCode uses `variant`, Grok uses `reasoningEffort`, Antigravity uses `thought_level` (never `{effort: ...}`).
- Quota bands for Antigravity (`gemini-5h` / `gemini-weekly`) and Grok (`subscription`): `<= 50%` → `high`; `> 50%` and `< 80%` → `medium`; `>= 80%` → switch provider. OpenCode Go (`go_rolling`, `go_weekly`, `go_monthly`) uses `{"variant": "high"}` whenever all three are `< 80%`.

| Stage | Target (`providerInstanceId` / `model`) | `target.options` |
| :--- | :--- | :--- |
| **Plan** | Coordinator itself (`antigravity` / `gemini-4-neon`, or `grok` / `grok-4.7`) | *(not delegated)* |
| **Implement** | `opencode` / `opencode-go/deepseek-v4.1-flash` while `go_rolling`, `go_weekly`, `go_monthly` are `< 80%`; else `antigravity` / `gemini-4-hydrogen`; else `grok` / `grok-4.7` | `{"variant": "high"}` (OpenCode), `{"thought_level": "high"\|"medium"}` (Antigravity), or `{"reasoningEffort": "high"\|"medium"}` (Grok) |
| **Review** | Opposite top-tier model (`gemini-4-neon` ↔ `grok-4.7`); if unavailable, a cheap non-author model (`antigravity` / `gemini-4-hydrogen` or `opencode` / `opencode-go/deepseek-v4.1-flash`). Say so if only the author remains. | Quota-scaled `reasoningEffort` / `thought_level` (`high` or `medium`) or `{"variant": "high"}` |

### Orca (`orca-cheap-workers`, `orca` CLI)

In Orca, the open pane is the coordinator (it does not edit the repo) and dispatches every stage as a supervised worker using `orca account list --json` (`status == "ok"` and `usedPercent < 80`):

| Stage | Worker | Invocation |
| :--- | :--- | :--- |
| **Plan** | Hydrogen High or Argon High, else Grok High, else Gemini 3.8 Flash High, else opencode2 | `--agent antigravity --model gemini-4-hydrogen-high` (`Gemini Models`) or `--agent antigravity --model gemini-4-argon-high` (`Gemini 4 Argon`) with no extra `--effort`; else `--agent grok --model grok-4.7 --effort high` (`grok.weekly`); else `--agent antigravity --model gemini-3.8-flash-high`; else `--agent opencode2`. |
| **Implement** | opencode2, else Gemini 3.8 Flash High | `--agent opencode2` (pass neither `--model` nor `--effort`) while `opencodeGo.session` and `opencodeGo.weekly` are available; else `--agent antigravity --model gemini-3.8-flash-high`. |
| **Review** | The other expensive planner | Antigravity planned → `--agent grok --model grok-4.7 --effort medium`. Grok planned → `--agent antigravity --model gemini-4-hydrogen-high` or `gemini-4-argon-high`. If that window is low, use a cheap non-author worker; say so if only the author remains. Review is read-only. |

Do not pass `--model` or `--effort` to `--agent opencode2` on Orca `worker-start` (it uses the local OpenCode config for `opencode-go/deepseek-v4.1-flash`). Antigravity bakes effort into the model id (do not pass a separate `--effort`). The command samples below are illustrative for the selected agent; they are not a fixed assignment of Grok to every review.

---

## 2. Pre-Flight Quota Check

**T3 Code** keeps each provider's last probe in `~/.t3/caches/<provider>.json`. Read `usageLimits.windows[]` and treat a window as available when `usedPercent` is under 80 (and `resetsAt`, when present, has not passed). Never paste a cache file wholesale: it also records auth/setup metadata.

```bash
python3 - <<'PY'
import json, pathlib
root = pathlib.Path.home() / ".t3" / "caches"
for name in ("antigravity", "grok", "opencode"):
    path = root / f"{name}.json"
    if not path.is_file():
        continue
    limits = (json.loads(path.read_text()) or {}).get("usageLimits") or {}
    for window in limits.get("windows") or []:
        print(name, window.get("id"), window.get("usedPercent"), window.get("resetsAt", ""))
PY
```

**Orca** exposes the same numbers through `orca account list --json` (`result.rateLimits`; a window is available when `status` is `ok` and `usedPercent` is under 80).

Before starting a multi-worker wave, also check the repository's own collector if you want burn-rate context:

```bash
python3 -m collector collect --json
```

- Never log or paste raw tokens from `~/.grok/auth.json` or `~/.local/share/opencode/opencode.db`.
- Warn the owner before launching a wave if any required bucket is below 15% remaining.


---

## 3. Canonical GitHub Issue-First + Supervised Coordinator Loop

Every bug or feature follows the **GitHub Issue-First Workflow** enforced by [`scripts/gh-issue-workflow.sh`](../scripts/gh-issue-workflow.sh) and `.githooks/commit-msg`:

```bash
# 0. Create the GitHub Issue FIRST (scrubbed for secret/PII hygiene automatically)
./scripts/gh-issue-workflow.sh create "<title>" "<problem-and-scope>" "enhancement"

# 1. Confirm runtime & create/bind the orchestration run
orca status --json
orca orchestration run-create --objective "AI Quota Monitor - Issue #<issue-number>" --json

# 2. Gate 1 — Plan review BEFORE implementation, using the review worker from AGENTS.md.
# This sample is the command when that worker is Grok.
# (Ensure the target worktree is marked trusted = true in ~/.grok/trusted_folders.toml first)
orca orchestration worker-start \
  --task-title "Issue #<issue-number>: Gate 1 Plan Review (grok-4.7 medium)" \
  --spec "<read-only Gate 1 plan review prompt>" \
  --worktree "<worktreeSelector>" \
  --agent grok --model grok-4.7 --effort medium \
  --json
./scripts/gh-issue-workflow.sh comment <issue-number> plan "<plan-and-gate1-review-markdown-or-@file>" \
  --agent grok --model grok-4.7 --effort medium
# The plan must include one line: UI evidence: before-and-after
# or UI evidence: none.
# When shots are required, capture before editing UI files:
# ./scripts/gh-issue-workflow.sh before-shot <issue-number> --attach "$TMPDIR/before.png#Before: previous screen"

# 3. Dispatch an Implementer & Tester worker (quota-aware orca-cheap-workers routing)
# Primary: opencode2 on opencode-go/deepseek-v4.1-flash (pass neither --model nor --effort)
orca orchestration worker-start \
  --task-title "Issue #<issue-number>: <concise-task-title>" \
  --spec "$(cat <path-to-spec.md>)" \
  --worktree "<worktreeSelector>" \
  --agent opencode2 \
  --json

# Fallback only when OpenCode Go quota is >= 80% used or unavailable (no --effort for Antigravity):
# orca orchestration worker-start ... --agent antigravity --model gemini-3.8-flash-high --json

# (Optional) Send follow-up instructions to a running worker terminal (--type dispatch)
# orca orchestration send --to "<terminalId>" --type dispatch --summary "<summary>" --body "<instructions>" --json

# 4. Wait for worker completion, questions, or escalations
orca orchestration check --wait --types "worker_done,escalation,question" --timeout-ms 900000 --json

# 5. Record Implementation (## [IMPLEMENTATION]) and Testing (## [TESTING]) on the Issue
./scripts/gh-issue-workflow.sh comment <issue-number> implementation "<summary-of-changes>" \
  --agent opencode2 --model deepseek-v4.1-flash --effort high
./scripts/gh-issue-workflow.sh comment <issue-number> testing "<verify-all-output>" \
  --agent opencode2 --model deepseek-v4.1-flash --effort high \
  --attach "$TMPDIR/before.png#Before: previous screen" \
  --attach "$TMPDIR/after.png#After: screen after the change"
```

### T3 Code equivalent

In T3 Code (`t3-cheap-workers`), the coordinator (`antigravity` / `gemini-4-neon` or `grok` / `grok-4.7`) writes the plan itself, creates the issue and feature branch/worktree, and dispatches **Implement** and **Review** via `delegate_task` (`role: "implementation"` and `role: "review"`):
- Pass the brief in `task`, set `target.providerInstanceId` and `target.model` separately, and set the provider-specific option key in `target.options` (`{"variant": "high"}` for `opencode`, `{"reasoningEffort": "high"|"medium"}` for `grok`, `{"thought_level": "high"|"medium"}` for `antigravity`). Never dispatch `xhigh`.
- Use `isolation: "shared"` on the parent's feature branch/worktree for sequential implement → review (so the reviewer inspects the exact diff); use `isolation: "worktree"` only for parallel independent tasks.
- Fix small/mechanical review findings directly in the coordinator, and run every re-review as a fresh `delegate_task` (never `t3_thread_send`). Post each stage to the issue with `gh-issue-workflow.sh comment` and commit through `scripts/agent-commit.sh`.

### Dispatching the Read-Only Reviewer & Posting `## [REVIEW]`

Use the review worker from the coordinator gate in [`AGENTS.md`](../AGENTS.md). In Orca, when Antigravity planned, dispatch `--agent grok --model grok-4.7 --effort medium` once the target worktree path is marked `trusted = true` in `~/.grok/trusted_folders.toml`; when Grok planned, review with `--agent antigravity --model gemini-4-hydrogen-high` or `gemini-4-argon-high`. Post the verdict to the GitHub Issue.

```bash
# 1. Dispatch a supervised Grok 4.7 Medium reviewer worker via Orca Orchestration
orca orchestration worker-start \
  --task-title "Issue #<issue-number>: Gate Review (grok-4.7 medium)" \
  --spec "<read-only review prompt>" \
  --worktree "<worktreeSelector>" \
  --agent grok --model grok-4.7 --effort medium \
  --json

# 2. Or run the helper script (which also posts ## [REVIEW] to Issue #<issue-number>)
./scripts/review-plan-and-code.sh worktree "<worktreePath>" --issue <issue-number>

# 3. Verify all 4 stages ([PLAN], [IMPLEMENTATION], [TESTING], [REVIEW]) exist and close the issue after merge
./scripts/gh-issue-workflow.sh verify <issue-number>
./scripts/gh-issue-workflow.sh close <issue-number>
```

Note: In Orca `worker-start`, `--agent grok` requires `[folders."<worktreePath>"]\ntrusted = true` in `~/.grok/trusted_folders.toml` so newly created worktrees do not block on interactive TUI folder trust (`agent-trust-workspace`), and `--agent opencode2` takes neither `--model` nor `--effort`.

### Stage 4 — Final Gate 2 Review & Closeout

Once the Gate 2 review returns `APPROVED` and `./scripts/verify-all.sh` plus the emulator evidence are green with high confidence, the coordinator finishes the issue in the same turn. Do not wait for the owner to nudge it:

1. `./scripts/gh-issue-workflow.sh verify <issue-number>` then `./scripts/gh-issue-workflow.sh close <issue-number>`.
2. Merge the issue worktree and push to `origin/main`.
3. Deploy the verified APK to the physical Pixel 8 Pro with `./scripts/deploy-pixel8pro.sh`.

The only acceptable pause is a genuinely blocked deploy: when `./scripts/deploy-pixel8pro.sh` reports `SSLV3_ALERT_CERTIFICATE_UNKNOWN`, Wireless Debugging pairing has expired and the owner must tap **Pair device with pairing code** and supply the 6-digit code. Ask for that code; do not fall back to the emulator as a stand-in for the owner's device.

### Attribution, agent commits, titles, and PRs

Every stage comment is attributed so a reader can tell who wrote it:

```markdown
## [IMPLEMENTATION]

_Contributor: `t3-code` (`gemini-4-neon`, effort: `high`)_

...body...
```

```bash
# Agent attribution (falls back to AQM_AGENT / AQM_MODEL / AQM_EFFORT)
./scripts/gh-issue-workflow.sh comment <n> testing "<output>" \
  --agent t3-code --model gemini-4-neon --effort high
# Human note
./scripts/gh-issue-workflow.sh comment <n> review "<notes>" --human
```

`--agent` must match `^[a-z0-9][a-z0-9-]*$`, `--model` must match `^[a-z0-9][a-z0-9._/-]*$`, and `--effort` is `low|medium|high|xhigh|default`. `--human` cannot be mixed with the other three. The script composes the `## [STAGE]` header, strips a pasted duplicate, prepends the `_Contributor:_` line, and scrubs the final comment through `.githooks/commit-msg`. `verify` refuses a stage comment whose first non-empty line is not exactly `## [STAGE]` or that has no `_Contributor:_` line.

Agents commit through `scripts/agent-commit.sh`:

```bash
./scripts/agent-commit.sh --author-name "T3 Code" --author-email "t3-code@bots.invalid" \
  -m "feat: ..." -m "Refs #6"
```

(or the same values in `GIT_AUTHOR_NAME`/`GIT_AUTHOR_EMAIL`). It requires a `.invalid` bot identity (never a GitHub noreply address), appends `Co-authored-by: Oladeji Famakinwa <id+login@users.noreply.github.com>` (resolved through `gh api user`, or `AQM_CO_AUTHOR_EMAIL`), allowlists only safe `git commit` flags (`-a`, `--all`, `--allow-empty`, `-q`, `--quiet`, `--dry-run`, `-v`, `--verbose`, plus a `-- pathspec` tail), unsets `AQM_SKIP_ISSUE_REF`/`AQM_SKIP_ATTRIBUTION`, validates the composed message with `.githooks/commit-msg`, and commits with `git -c core.hooksPath=<repo>/.githooks commit`.

`.githooks/commit-msg` enforces the rest: no secrets, PII or local home paths, a `Refs #<n>` line once `origin` exists (or with `AQM_REQUIRE_ISSUE_REF=1`), no GitHub auto-closing keywords (even on merges, and even with `AQM_SKIP_ISSUE_REF=1`), a non-bot `Co-authored-by:` trailer whenever the author or committer is bot-shaped, and the `SEC-G2` UI before-shot receipt for a staged UI change.

Titles and pull requests:

- `scripts/check-title.sh` runs locally and in CI (`.github/workflows/title-check.yml`). An agent PR title reads `[<agent> <model> <effort>] <summary>`; an agent issue title reads `[<agent>] <summary>` or `[<agent> <model> <effort>] <summary>`. On PRs it also rejects GitHub auto-closing keywords in the PR body and `base..head` commits (even on human PRs) while leaving human titles untouched.
- PRs use `.github/pull_request_template.md`: a `Refs #N` plan line, a `Models: plan: … · build: … · review: …` line, the `./scripts/test-workflow.sh` + `./scripts/verify-all.sh` output block, and the `Opened by <Agent>, Deji's build agent.` footer the title check detects.
- `./scripts/test-workflow.sh` is the offline gate for all of the above (stub `gh`, no network) and is wired into `./scripts/verify-all.sh` and CI. It covers `agent-commit.sh`, `.githooks/commit-msg`, `check-title.sh`, `gh-issue-workflow.sh`, and a UI commit refused without a `before-shot` receipt.

---

## 4. Task-Spec Contract (Required in Every Brief)

Every worker brief must be self-contained (<80 lines) and explicitly state:
1. **Target & Required Reading**: Which files/packages are in scope and which `docs/*` and `.agents/skills/*` files to read first.
2. **Change**: Exact deliverable to produce.
3. **Constraints**:
   - Do not modify `gradle/libs.versions.toml` or `build.gradle.kts` dependencies unless explicitly authorized by the brief.
   - Zero WebViews; 100% native Jetpack Compose.
   - Zero secrets, tokens, or personal paths (`/Users/...`).
4. **Ownership**: Exact files the worker may create or edit, and whether committing is authorized.
5. **Observable Acceptance Gates (`./scripts/verify-all.sh`)**:
   - `./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` (the `--no-daemon` flag is inside `verify-all.sh`, so a piped `verify-all.sh | tail` never hangs on a daemon that inherited the pipe's file descriptors)
   - `python3 -m unittest discover -s collector/tests -v` (including `test_learnings_and_docs_sync_gate`)
   - `./scripts/test-workflow.sh` (offline workflow suite: issue attribution, `Refs #<n>`/no auto-close, agent commits, UI before-shot receipt, title gate)
   - Pre-commit secret hygiene check (`.githooks/pre-commit`)
   - **Unprompted End-of-Task Learnings & Doc-Sync Check**: Update `docs/lessons.md`, `docs/bugs/index.md`, `docs/status.md`, `README.md`, and `.agents/skills/orca-quota-engineering/SKILL.md` in the same change before reporting `worker_done` or task completion.

---

## 5. Validation & Settlement Accounting

Before acknowledging a `worker_done` delivery:
1. Inspect `git status --short` and `git diff --stat` in the worker's worktree.
2. Re-run the acceptance test suite and `.githooks/pre-commit` check.
3. Choose the settled worker's disposition (`worker-release`, `worker-retain`, or immediate follow-up `worker-start --terminal`) before running `orca orchestration check --ack <delivery_id>`.
4. Never edit code directly from the review report in the coordinator turn if a follow-up implementation fix is needed—dispatch the fix to a quota-routed implementer worker (`opencode2` primary, `gemini-3.8-flash-high` fallback).
