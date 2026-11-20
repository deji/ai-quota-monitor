# AGENTS.md — ai-quota-monitor

Read [`docs/plan.md`](docs/plan.md) first for architectural authority, then use the **Documentation map** below to load only the specs relevant to your task.
This repository is intended to be hosted publicly on GitHub: **never** put tokens, API keys, passwords, account IDs, device serials, personal emails, or local home paths (`/Users/...`) into any committed file, test fixture, commit message, or PR description.

## What this is

A native Android application (Kotlin + Jetpack Compose, Material 3 Expressive, zero WebViews) and companion quota-collection layer for monitoring AI model subscription quotas, reset windows, and burn-rate trends across multiple coding-agent and model subscriptions (`agy` / Antigravity, `opencode go`, `grok` Build, and future providers) on a Pixel 8 Pro (Android 16/17).

## Coordinator gate

Every non-trivial session goes through a coordinator, whichever harness runs it. Routing lives here and in the global `t3-cheap-workers` (T3 Code) and `orca-cheap-workers` (Orca) skills plus [`docs/workers.md`](docs/workers.md). The two harnesses have distinct routing rules; if this file and the active skill disagree, follow the active skill.

**T3 Code (`t3-cheap-workers`, `delegate_task`).** Active when running as `antigravity` `gemini-4-neon` or `grok` `grok-4.7` (medium/high); a delegated child does its own task and starts no subagents. The coordinator calls `orchestrator_capabilities` once, checks `usageLimits.windows[]` in `~/.t3/caches/{opencode,antigravity,grok}.json` (`< 80%` available; Antigravity/Grok effort `<= 50%` → `high`, `> 50%` and `< 80%` → `medium`), and writes the plan itself. Only implement and review are `delegate_task`s (brief in `task`; separate `target.providerInstanceId` and `target.model`; provider option keys `variant` for OpenCode, `reasoningEffort` for Grok, `thought_level` for Antigravity; never `xhigh`). Implement with `opencode` `opencode-go/deepseek-v4.1-flash` (`{"variant":"high"}`) while `go_rolling`/`go_weekly`/`go_monthly` are `< 80%`, else `antigravity` `gemini-4-hydrogen` (`thought_level` on `gemini-5h`/`gemini-weekly`), else `grok` `grok-4.7` (`reasoningEffort` on `subscription`). Review with the opposite top-tier model (`gemini-4-neon` ↔ `grok-4.7`), else a cheap non-author model (`gemini-4-hydrogen` or DeepSeek); say so if only the author remains. Sequential implement → review uses `isolation: "shared"` on a branch/worktree; `isolation: "worktree"` is for parallel independent tasks.

**Orca (`orca-cheap-workers`, `orca` CLI).** The open pane is the coordinator (loads `orca-cli`, `orchestration`, `orca-cheap-workers`; checks `orca account list --json` where `status == "ok"` and `usedPercent < 80`) and dispatches every stage into one worktree per issue (one writer at a time; a planner does not start other workers). Plan at high effort with `--agent antigravity --model gemini-4-hydrogen-high` or `gemini-4-argon-high` (no extra `--effort`), else `--agent grok --model grok-4.7 --effort high`, else `gemini-3.8-flash-high`, else `--agent opencode2`. Implement with `--agent opencode2` (pass neither `--model` nor `--effort`), else `--agent antigravity --model gemini-3.8-flash-high`. Review with the other expensive planner (Antigravity planned → `--agent grok --model grok-4.7 --effort medium`; Grok planned → Hydrogen or Argon high; else a cheap non-author worker).

Issue comments, specs, and repo invariants stay in [`docs/workers.md`](docs/workers.md) and [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md). Verification scripts: `./scripts/verify-all.sh`, `./scripts/test-workflow.sh`, and `./scripts/review-plan-and-code.sh`.

Closeout is proactive, not nudged. Once Gate 2 review is `APPROVED` and `./scripts/verify-all.sh` plus the emulator evidence pass with high confidence, the coordinator closes the GitHub Issue, merges and pushes to `origin/main`, and deploys the verified APK to the owner's Pixel 8 Pro with `./scripts/deploy-pixel8pro.sh` in the same turn, without waiting for the owner to ask; it prompts the owner only when `SSLV3_ALERT_CERTIFICATE_UNKNOWN` means a 6-digit pairing code is required.

## Layout

```text
.agents/skills/          Project-local agent skills (including orca-quota-engineering, Stitch, Android CLI, Compose, Kotlin)
.stitch/                 Stitch design system (DESIGN.md), screen metadata, and downloaded prototype assets
.githooks/               pre-commit hygiene, commit-msg issue/attribution/UI-receipt rules, pre-push range audit
.github/                 CI/CD pipelines (lint, test, APK build, secret hygiene, agent title gate) + PR template
app/                     Native Android application (Kotlin, Jetpack Compose, Material 3, Glance widgets)
collector/               Companion macOS quota collector, launchd template, & AES-256-GCM relay service
docs/                    Architecture, provider wire specs, Stitch design docs, worker playbook, lessons, status, and bug tracker
scripts/                 Verification, issue workflow, agent commits, title gate, workflow tests, Grok 3-gate review, and ADB deploy
skills-lock.json         Locked skill versions installed via npx skills
```

## Hard rules

- **100% Native Android UI (No WebViews)**: All screens, charts, gauges, and animations must be built with native Jetpack Compose and Material 3. Never wrap a web page in a `WebView`.
- **Public-repo secret & PII hygiene**:
  - Enable the pre-commit hook on every clone/worktree: `git config core.hooksPath .githooks`.
  - Never commit `.env`, `local.properties`, `*.jks`, `*.keystore`, `auth.json`, `*.db`, or unredacted captures.
  - Never include real tokens (`Bearer ...`, `eyJ...`, `xai-...`), user IDs, device serial numbers, or local user paths (`/Users/<username>`) in source code, tests, or docs. Use `$HOME` or `~` in documentation and synthetic fixtures (`00000000-0000-0000-0000-000000000000`, `t3-code@bots.invalid`, `user@example.com`) in tests.
- **Scrubbed logs and errors**: Neither the collector nor the Android app may ever log `Authorization` headers, session tokens, or cookies in Logcat, crash reports, or HTTP error messages.
- **Do not invent provider endpoints or wire shapes**: [`docs/providers.md`](docs/providers.md) records the verified quota commands and HTTP wire shapes. Two upstream endpoints (`opencode go` and `grok`) are undocumented and can change shape—always parse defensively and back parsers with fixture tests.
- **GitHub Issue-First Workflow (Mandatory)**:
  - Every bug or feature MUST be created as a GitHub Issue **before** any implementation starts (`./scripts/gh-issue-workflow.sh create "<title>" "<body>"`). Agent-opened titles are enforced by `./scripts/check-title.sh` (CI: `.github/workflows/title-check.yml`).
  - Every stage of work MUST be appended as an **attributed** `## [PLAN]`/`## [IMPLEMENTATION]`/`## [TESTING]`/`## [REVIEW]` comment:
    1. `./scripts/gh-issue-workflow.sh comment <issue-number> <stage> "<body-or-@file>" --agent <slug> --model <model> [--effort <level>]` (or `--human`; `AQM_AGENT`/`AQM_MODEL`/`AQM_EFFORT` fill in defaults). `verify` refuses unattributed stages.
    2. The plan comment chooses `UI evidence: before-and-after` or `UI evidence: none`. For before-and-after, run `before-shot <issue> --attach <file-outside-the-repo>#Before: <old screen>` before editing UI files; `commit-msg` rejects the UI commit until that receipt exists. The testing comment attaches that same before shot plus `--attach <file>#After: <new screen>`. Never commit captures. Collector-only work says `No emulator UI was exercised.`
    3. Commit with `Refs #<n>` and never a GitHub auto-closing keyword (`Closes`, `Fixes`, `Resolves`, …): `close` runs `verify` first, so an issue closes only after all four stages exist.
  - Agents commit through `./scripts/agent-commit.sh` with an `<agent>@bots.invalid` identity and an explicit owner `Co-authored-by:` trailer. PRs use `.github/pull_request_template.md` (a `Models:` line and the `Opened by …` footer the title check detects).
- **Update docs & codify learnings in the same change (unprompted)**: The implementer worker, before it finishes, updates [`docs/lessons.md`](docs/lessons.md), [`docs/bugs/index.md`](docs/bugs/index.md), [`docs/status.md`](docs/status.md), [`README.md`](README.md) (for any run/test/deploy workflow change), and [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md), then runs `./scripts/verify-all.sh` and `./scripts/test-workflow.sh`. The coordinator checks that this happened. The coordinator does not do the edit itself.
- **Keep `AGENTS.md` a signpost, never a running log**: Record progress in [`docs/status.md`](docs/status.md), design details in [`docs/design/`](docs/design/README.md), and defects in [`docs/bugs/`](docs/bugs/README.md).

## Documentation map (progressive discovery)

| Topic | Where to read / update |
| :--- | :--- |
| Architecture, options analysis, & phased build plan | [`docs/plan.md`](docs/plan.md) |
| Provider quota wire specs (`agy`, `opencode go`, `grok`) & normalization schema | [`docs/providers.md`](docs/providers.md) |
| Stitch UI/UX workflow, `DESIGN.md`, screen inventory, & Compose mapping | [`docs/design/README.md`](docs/design/README.md) |
| T3 Code & Orca orchestration playbook, issue attribution, titles, and agent commits | [`docs/workers.md`](docs/workers.md) |
| Architectural lessons, invariants, & historical bug post-mortems | [`docs/lessons.md`](docs/lessons.md), [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md) |
| Current project state, installed skills, & open decision gates | [`docs/status.md`](docs/status.md) |
| Bug tracker lifecycle, filing template, & defect registry | [`docs/bugs/README.md`](docs/bugs/README.md), [`docs/bugs/index.md`](docs/bugs/index.md) |
