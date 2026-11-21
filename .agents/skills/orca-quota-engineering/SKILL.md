---
name: orca-quota-engineering
description: Project-specific architecture, multi-agent orchestration workflow (coordinator gate in AGENTS.md covering T3 Code `delegate_task` and Orca `orca` workers, quota-routed planner, opencode2 implementer, and the other expensive planner as reviewer), agent attribution and title gates, cross-language AES-256-GCM relay invariants, provider wire parsing rules, and verification gates for ai-quota-monitor. Use whenever planning, implementing, testing, or reviewing changes in this repository.
---

# Orca Quota Engineering Skill (`ai-quota-monitor`)

Use this skill whenever planning, modifying, testing, or reviewing `collector/`, `app/`, `.stitch/`, or `docs/` in `ai-quota-monitor`. Detailed per-subsystem invariants (items 1–21) live in [`references/invariants.md`](./references/invariants.md) for progressive disclosure.

## 1. Mandatory Multi-Agent Orchestration Workflow (T3 Code & Orca)

All non-trivial work goes through the coordinator gate in [`AGENTS.md`](../../../AGENTS.md) and the active global skill (`t3-cheap-workers` in T3 Code; `orca-cheap-workers` in Orca):

- **T3 Code (`t3-cheap-workers`, `delegate_task`):** Coordinator (`antigravity/gemini-4-neon` or `grok/grok-4.7`) writes the plan itself and delegates Implement (`opencode/opencode-go/deepseek-v4.1-flash` `{"variant":"high"}` → `antigravity/gemini-4-hydrogen` → `grok/grok-4.7`) and Review (opposite top-tier model, else cheap non-author fallback). Quota comes from `~/.t3/caches/{opencode,antigravity,grok}.json` (`< 80%` available; Antigravity/Grok `<= 50%` → `high`, `> 50%` and `< 80%` → `medium`; never `xhigh`). Sequential implement → review uses `isolation: "shared"`; parallel independent tasks use `isolation: "worktree"`.
- **Orca (`orca-cheap-workers`, `orca` CLI):** Coordinator checks `orca account list --json` (`status == "ok"`, `usedPercent < 80`) and dispatches Plan (Hydrogen/Argon/Grok high), Implement (`opencode2` with no `--model`/`--effort`), and Review (opposite expensive planner) into one worktree per issue. Pre-trust new worktrees in `~/.grok/trusted_folders.toml` for Grok CLI workers; use `--type dispatch` with `orca orchestration send`.
- **Fast Worker Verification & Watchdog Budget (`APP-TEST1`):**
  - Delegated implementers run **fast compile + targeted unit tests** for the files they touched (e.g. `python3 -m unittest`, `./gradlew --no-daemon compileDebugKotlin testDebugUnitTest --tests "*NewTest*"`), **never** full `lintDebug`, unfiltered `testDebugUnitTest`, `assembleDebug`, or full `./scripts/verify-all.sh`. Workers must never bisect `main`, spawn extra worktrees, or thrash on an unrelated pre-existing test failure/hang — report it immediately to the coordinator.
  - The coordinator owns the single heavyweight `./scripts/verify-all.sh` pass, always attaches a `120s` `schedule` watchdog (`TimerCondition: "<taskId>"`) whenever a coordinator `run_command` backgrounds and the turn yields, and captures `jstack <pid>` before killing/retrying any hang.

### GitHub Issue-First Lifecycle (`scripts/gh-issue-workflow.sh`) & Review Gates
1. **Gate 0 — Issue + `## [PLAN]` (before code):**
   ```bash
   ./scripts/gh-issue-workflow.sh create "[t3-code] <title>" "<description>" "enhancement"
   ./scripts/gh-issue-workflow.sh comment <issue> plan "<plan>" --agent t3-code --model gemini-4-neon --effort high
   # Plan must contain exactly one line: UI evidence: before-and-after  OR  UI evidence: none
   ```
2. **Gate 1 — Plan Review:** Required before implementation on major architectural/UX features (or owner-approved design rounds recorded in `## [PLAN]`).
3. **Gate 2 — Implementation, Testing & Review:**
   - For `UI evidence: before-and-after`, capture & register `before-shot <issue> --attach "$TMPDIR/before.png#Before: ..."` **before** editing UI files.
   - Commit via `./scripts/agent-commit.sh` (`<agent>@bots.invalid` + owner `Co-authored-by:` trailer, `Refs #<issue>`, **never** auto-closing keywords like `Closes`/`Fixes`/`Resolves`).
   - Post attributed `## [IMPLEMENTATION]`, `## [TESTING]` (with Before + After attachments when UI changed, or `No emulator UI was exercised.` for non-UI changes), and non-author `## [REVIEW]`.
4. **Gate 3 — Cross-System Review & Close:** Verify cross-system contract whenever touching both `collector/` and `app/`, then run `./scripts/gh-issue-workflow.sh close <issue>` (runs `verify` first).

---

## 2. Core Invariants Quick Reference

Read [`references/invariants.md`](./references/invariants.md) for the complete specification of items 1–21 before modifying a subsystem:

| Area | IDs / Reference | Must-Hold Summary |
| :--- | :--- | :--- |
| **Native UI & Hygiene** | `SEC-G1/G2/G3`, `AQM-LINT-001..013` ([invariants.md §1–2, §9–10, §17](./references/invariants.md)) | 100% native Compose/Glance (zero WebViews); `git config core.hooksPath .githooks`; zero tokens, emails, serials, or local home paths; attributed commits/comments & no auto-closing keywords. |
| **Crypto & Wire Parsers** | `SYS-C1/C2`, `COL-P6`, `COL-H7/H8` ([invariants.md §3–5, §14](./references/invariants.md)) | Cross-language AES-256-GCM golden vector (`nonce`/`iv`, `capturedAt`); fail-closed parsers; unified `grok-weekly-credits` + `productBreakdown`; login LaunchAgent binds Tailscale (never silently loopback); tests never write live user config. |
| **Dashboard & Activity** | `APP-UX8`, `APP-UX1/2/3`, `APP-SYNC1` ([invariants.md §6, §8, §11, §13, §20](./references/invariants.md)) | Bank 3 `QuotaChronographCard` (`resolveWideProviderGroups` order, `inner * 0.72` centre reset), fixed `116dp` `SelectedPoolStrip`, Wildcard Hybrid `ActivityBoardCard` (`5h`/`24h` LED, `7d`/`30d` UTC-aligned Quilt, right-inclusive live edge at `now`), `Gemini Pool` naming, `SheetScrollGuard` (`AQM-LINT-011`), `syncMutex` disjoint from `settingsMutex`. |
| **Widgets & Icons** | `APP-UX6/7`, `APP-SYNC2`, `APP-ICO1/2`, `APP-VER1` ([invariants.md §7, §12, §15–16, §18–19](./references/invariants.md)) | `2x2` Quad Micro-Bento (`fillMaxHeight()`) & `4x3` Provider Deck (`<= 10` Glance children, `AQM-LINT-013`), WorkManager 15m periodic sync (never `OneTimeWorkRequest` in `onUpdate`), full-bleed `#0B0F12` adaptive icon + `<monochrome>`, `1.1.0-<4-char-sha>` version. |
| **Tests & Timeouts** | `APP-TEST1` ([invariants.md §21](./references/invariants.md)) | Never mix `runTest` virtual-time `withTimeout` with `Dispatchers.IO` + `runBlocking { gate.await() }`; 3m Gradle JUnit timeout (`app/build.gradle.kts`) + 5m `verify-all.sh` wall-clock timeout with auto-`jstack`. |

---

## 3. Standard Verification Gate (`scripts/verify-all.sh`)

Before completing any task or merging any branch, run:

```bash
./scripts/verify-all.sh
```

This executes 5 automated gates:
1. `.githooks/pre-commit` + repo-wide secret & PII scan (`test_repo_hygiene.py`)
2. Python collector unit test suite (`python3 -m unittest discover -s collector/tests -v`) plus the offline agent/workflow suite (`./scripts/test-workflow.sh`)
3. Android lint, JUnit unit tests, and debug APK build (`./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`) under a 5-minute wall-clock timeout (`AQM_GRADLE_TIMEOUT_SECONDS`, default `300`, auto-dumping `jstack` on timeout) plus a 3-minute per-task Gradle `Test` timeout in `app/build.gradle.kts`.
   - Never run `./gradlew` or `./scripts/verify-all.sh` concurrently across parallel worktrees against the same `~/.gradle` home; wait for sibling runs or isolate `GRADLE_USER_HOME`.
4. APK artifact verification (`app/build/outputs/apk/debug/app-debug.apk`)
5. **Learnings, Bug Ledger & Skill Sync Check**: Verifies living docs (`docs/lessons.md`, `docs/bugs/index.md`, `docs/status.md`, `README.md`, `SKILL.md`), workflow scripts, and that every defect ID in `docs/bugs/index.md` is cross-referenced in `docs/lessons.md`.

---

## 4. Mandatory Unprompted End-of-Task Closeout Protocol

**Never wait for the user to ask you to update docs, record lessons, or update skills/scripts.** The implementer worker updates docs before finishing; the coordinator verifies it happened.

1. **Capture Learnings (`docs/lessons.md`)**: Record non-obvious root causes and review findings in [`docs/lessons.md`](../../../docs/lessons.md) (thematic section + §5 Historical Bug Ledger).
2. **Sync Defect & Status Trackers (`docs/bugs/index.md`, `docs/status.md`)**: Ensure every defect ID is logged in [`docs/bugs/index.md`](../../../docs/bugs/index.md) and cross-referenced in `docs/lessons.md`.
3. **Update `README.md`**: Update immediately if any CLI flag, port, UI path, or deploy step changed.
4. **Codify Reusable Rules (`SKILL.md`, `references/invariants.md`, `scripts/`)**: Keep `SKILL.md` concise (append detailed subsystem specs to `references/invariants.md`) and automate invariants in `scripts/verify-all.sh` or tests.
5. **Proactive Merge, Push & Deploy (coordinator, no nudge)**: Once Gate 2 review is `APPROVED` and `./scripts/verify-all.sh` plus any required emulator evidence pass with high confidence, close the GitHub Issue, merge and push to `origin/main`, rebuild `./gradlew --no-daemon assembleDebug` at final `HEAD` (when `app/` changed so `BuildConfig.VERSION_NAME` embeds the merged 4-char SHA), restart the Mac collector LaunchAgent (`launchctl kickstart -k "gui/$(id -u)/com.aiquotamonitor.collector"`) whenever `collector/` changed, stop any local emulator (`adb -s emulator-5554 emu kill`), and run `./scripts/deploy-pixel8pro.sh` (when `app/` changed) in the same turn. Ask the owner for a 6-digit Wireless Debugging pairing code via `request_secret` only if `SSLV3_ALERT_CERTIFICATE_UNKNOWN` occurs.
