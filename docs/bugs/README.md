# Bug Tracker Protocol

Every non-trivial defect discovered during implementation, review (`grok-4.7`), or device testing on the Pixel 8 Pro is tracked in `docs/bugs/`.

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
