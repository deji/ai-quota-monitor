# Working with Supervised Workers (Orca Orchestration)

Playbook for coordinating planning, implementation, testing, and code reviews in `ai-quota-monitor` using **Orca Orchestration**.

---

## 1. Role & Model Matrix

Per the project's orchestration rules, every stage uses a dedicated agent, model, and effort level:

| Stage / Role | Agent | Model ID | Effort | Invocation Policy |
| :--- | :--- | :--- | :--- | :--- |
| **Planner & Coordinator** | `agy` (Antigravity) | `gemini-4-argon-high` | `high` | Runs in the primary coordinator session. Owns architecture, Stitch prototyping, task specs, dispatch, and final verification. |
| **Implementer & Tester** | `agy` (Antigravity) | `gemini-3.8-flash-high` | `high` | Dispatched as supervised Orca workers per task/module. Owns code edits, unit/UI tests, Gradle builds, and emulator/device verification. |
| **Code Reviewer** | `grok` (Grok Build CLI) | `grok-4.7` | `medium` | Dispatched read-only on the diff/worktree after implementation and green tests. Must never modify files or commit. |

---

## 2. Pre-Flight Quota Check

Before starting a multi-worker wave, check current model quotas (`gemini-argon-wtu-weekly`, `gemini-5h`, `opencode-rolling`, and `grok-weekly-credits` + token expiry) using the repository's built-in collector:

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

# 2. Gate 1 — Dispatch a supervised Grok 4.7 Medium reviewer worker for Plan Review BEFORE implementation
# (Ensure the target worktree is marked trusted = true in ~/.grok/trusted_folders.toml first)
orca orchestration worker-start \
  --task-title "Issue #<issue-number>: Gate 1 Plan Review (grok-4.7 medium)" \
  --spec "<read-only Gate 1 plan review prompt>" \
  --worktree "<worktreeSelector>" \
  --agent grok \
  --json
./scripts/gh-issue-workflow.sh comment <issue-number> plan "<plan-and-gate1-review-markdown-or-@file>"

# 3. Dispatch an Implementer & Tester worker (agy with Gemini 3.8 Flash High)
orca orchestration worker-start \
  --task-title "Issue #<issue-number>: <concise-task-title>" \
  --spec "$(cat <path-to-spec.md>)" \
  --worktree "<worktreeSelector>" \
  --agent antigravity \
  --model gemini-3.8-flash-high \
  --effort high \
  --json

# (Optional) Send follow-up instructions to a running worker terminal (--type dispatch)
# orca orchestration send --to "<terminalId>" --type dispatch --summary "<summary>" --body "<instructions>" --json

# 4. Wait for worker completion, questions, or escalations
orca orchestration check --wait --types "worker_done,escalation,question" --timeout-ms 900000 --json

# 5. Record Implementation (## [IMPLEMENTATION]) and Testing (## [TESTING]) on the Issue
./scripts/gh-issue-workflow.sh comment <issue-number> implementation "<summary-of-changes>"
./scripts/gh-issue-workflow.sh comment <issue-number> testing "<verify-all-output>"
```

### Dispatching the Read-Only Reviewer (`grok` with `grok-4.7` Medium) & Posting `## [REVIEW]`

For **Gate 1 (Plan Review)** and **Gate 2 (Worktree Review)**, dispatch `grok` as a supervised Orca orchestration worker (`--agent grok`, which launches with `default = "grok-4.7"` and `default_reasoning_effort = "medium"` from `~/.grok/config.toml` once the target worktree path is marked `trusted = true` in `~/.grok/trusted_folders.toml`), and post its `## [REVIEW]` verdict to the GitHub Issue:

```bash
# 1. Dispatch a supervised Grok 4.7 Medium reviewer worker via Orca Orchestration
orca orchestration worker-start \
  --task-title "Issue #<issue-number>: Gate Review (grok-4.7 medium)" \
  --spec "<read-only review prompt>" \
  --worktree "<worktreeSelector>" \
  --agent grok \
  --json

# 2. Or run the helper script (which also posts ## [REVIEW] to Issue #<issue-number>)
./scripts/review-plan-and-code.sh worktree "<worktreePath>" --issue <issue-number>

# 3. Verify all 4 stages ([PLAN], [IMPLEMENTATION], [TESTING], [REVIEW]) exist and close the issue after merge
./scripts/gh-issue-workflow.sh verify <issue-number>
./scripts/gh-issue-workflow.sh close <issue-number>
```

Note: In Orca `worker-start`, `--agent grok` reads its model (`grok-4.7`) and effort (`medium`) from `~/.grok/config.toml` rather than CLI flags, and requires `[folders."<worktreePath>"]\ntrusted = true` in `~/.grok/trusted_folders.toml` so newly created worktrees do not block on interactive TUI folder trust (`agent-trust-workspace`).

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
   - `./gradlew lintDebug testDebugUnitTest assembleDebug`
   - `python3 -m unittest discover -s collector/tests -v` (including `test_learnings_and_docs_sync_gate`)
   - Pre-commit secret hygiene check (`.githooks/pre-commit`)
   - **Unprompted End-of-Task Learnings & Doc-Sync Check**: Update `docs/lessons.md`, `docs/bugs/index.md`, `docs/status.md`, `README.md`, and `.agents/skills/orca-quota-engineering/SKILL.md` in the same change before reporting `worker_done` or task completion.

---

## 5. Validation & Settlement Accounting

Before acknowledging a `worker_done` delivery:
1. Inspect `git status --short` and `git diff --stat` in the worker's worktree.
2. Re-run the acceptance test suite and `.githooks/pre-commit` check.
3. Choose the settled worker's disposition (`worker-release`, `worker-retain`, or immediate follow-up `worker-start --terminal`) before running `orca orchestration check --ack <delivery_id>`.
4. Never edit code directly from the review report in the coordinator turn if a follow-up implementation fix is needed—dispatch the fix to an implementer worker (`gemini-3.8-flash-high`).
