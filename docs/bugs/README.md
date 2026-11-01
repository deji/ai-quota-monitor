# Bug & Feature Tracker Protocol (GitHub Issue-First Workflow)

Every bug or feature in `ai-quota-monitor` **must** be filed as a GitHub Issue first via [`scripts/gh-issue-workflow.sh`](../../scripts/gh-issue-workflow.sh) and also logged in [`docs/bugs/index.md`](index.md) + [`docs/lessons.md`](../lessons.md).

## Mandatory GitHub Issue Lifecycle (`scripts/gh-issue-workflow.sh`)

1. **Create Issue First**:
   ```bash
   ./scripts/gh-issue-workflow.sh create "<title>" "<symptom-or-feature-scope>" "bug"
   ```
2. **Document All 4 Stages as Issue Comments**:
   - **Plan (`## [PLAN]`)**: `./scripts/gh-issue-workflow.sh comment <issue-number> plan "<plan>"`
   - **Implementation (`## [IMPLEMENTATION]`)**: `./scripts/gh-issue-workflow.sh comment <issue-number> implementation "<diff-summary-and-commits>"`
   - **Testing (`## [TESTING]`)**: `./scripts/gh-issue-workflow.sh comment <issue-number> testing "<verify-all-and-device-test-results>"`
   - **Review (`## [REVIEW]`)**: `./scripts/review-plan-and-code.sh worktree "<worktree-path>" --issue <issue-number>`
3. **Verify & Close**:
   ```bash
   ./scripts/gh-issue-workflow.sh close <issue-number>
   ```
   (`close` runs `verify` first and fails closed if any of `## [PLAN]`, `## [IMPLEMENTATION]`, `## [TESTING]`, or `## [REVIEW]` is missing from the issue comments.)

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
