# Bug & Feature Tracker Protocol (GitHub Issue-First Workflow)

Every bug or feature in `ai-quota-monitor` **must** be filed as a GitHub Issue first via [`scripts/gh-issue-workflow.sh`](../../scripts/gh-issue-workflow.sh) and also logged in [`docs/bugs/index.md`](index.md) + [`docs/lessons.md`](../lessons.md).

## Mandatory GitHub Issue Lifecycle (`scripts/gh-issue-workflow.sh`)

1. **Create Issue First**:
   ```bash
   ./scripts/gh-issue-workflow.sh create "<title>" "<symptom-or-feature-scope>" "bug"
   ```
   An agent-opened issue title is validated by [`scripts/check-title.sh`](../../scripts/check-title.sh): `[<agent>] <summary>` or `[<agent> <model> <effort>] <summary>`.
2. **Document All 4 Stages as Issue Comments** (each one attributed):
   - **Plan (`## [PLAN]`)**: `./scripts/gh-issue-workflow.sh comment <issue-number> plan "<plan>" --agent <slug> --model <model> [--effort <level>]`. The body contains exactly one of `UI evidence: before-and-after` or `UI evidence: none`.
   - **Before the UI edit**: when the plan says `before-and-after`, `./scripts/gh-issue-workflow.sh before-shot <issue-number> --attach "$TMPDIR/before.png#Before: previous screen"`. The commit hook rejects a UI commit until this receipt exists.
   - **Implementation (`## [IMPLEMENTATION]`)**: `./scripts/gh-issue-workflow.sh comment <issue-number> implementation "<diff-summary-and-commits>" --agent <slug> --model <model> [--effort <level>]`
   - **Testing (`## [TESTING]`)**: `./scripts/gh-issue-workflow.sh comment <issue-number> testing "<verify-all-and-device-test-results>" --agent <slug> --model <model> [--effort <level>] --attach "$TMPDIR/before.png#Before: previous screen" --attach "$TMPDIR/after.png#After: screen after the change"`. The Before file must be the one registered before the edit. Collector-only work includes `No emulator UI was exercised.`
   - **Review (`## [REVIEW]`)**: `./scripts/review-plan-and-code.sh worktree "<worktree-path>" --issue <issue-number>` (posts with `--agent grok --model grok-4.7 --effort medium`), or `--human` for the owner's own note.
   - Attribution falls back to `AQM_AGENT` / `AQM_MODEL` / `AQM_EFFORT`, so a dispatched worker can carry its identity in the environment. `--agent` must match `^[a-z0-9][a-z0-9-]*$` and `--model` `^[a-z0-9][a-z0-9._/-]*$`; `--human` cannot be mixed with them.
3. **Verify & Close**:
   ```bash
   ./scripts/gh-issue-workflow.sh close <issue-number>
   ```
   (`close` runs `verify` first and fails closed unless `## [PLAN]`, `## [IMPLEMENTATION]`, `## [TESTING]`, and `## [REVIEW]` each exist as their own comment, start with that exact header, and carry a `_Contributor:` line.)

## Commits, titles, and the workflow gate

- Commit with `Refs #<n>`. Never write a GitHub auto-closing keyword (`Closes`, `Fixes`, `Resolves`, …) in a commit, PR body, or comment: `.githooks/commit-msg` rejects it (even on merges and when `AQM_SKIP_ISSUE_REF=1`) and `scripts/check-title.sh` rejects it in PR bodies and `base..head` commits. The issue closes only through `close` after `verify`.
- Agents commit through `./scripts/agent-commit.sh` with an `<agent>@bots.invalid` identity; it appends the owner `Co-authored-by:` trailer, allowlists only safe `git commit` flags, and enforces `.githooks/commit-msg`.
- `./scripts/test-workflow.sh` runs the whole workflow offline (stub `gh`, no network): attribution, `Refs #<n>`, no auto-close, agent commits, UI before-shot receipt, and the title gate. It is wired into `./scripts/verify-all.sh` and CI.

## Lifecycle States

- `open` — Reproduced or identified; awaiting fix.
- `fixed` — Code and unit/UI test fix landed; awaiting independent verification.
- `verified` — Acceptance checklist walked, tests green, and verified on emulator/device.

## Filing Template (`docs/bugs/BUG-NNN-short-slug.md`)

```markdown
---
id: BUG-NNN
title: Short descriptive title
status: open
severity: blocker | major | minor
discovered_by: planner | implementer | reviewer | owner
---

## Symptom & Root Cause
Describe what failed and why.

## Fix
Describe the code and test changes.

## Acceptance
- [ ] Unit / Compose UI test added and passing
- [ ] No secrets or private paths in fixtures
- [ ] Verified on emulator / Pixel 8 Pro

## Log
- YYYY-MM-DD: Filed / fixed / verified.
```
