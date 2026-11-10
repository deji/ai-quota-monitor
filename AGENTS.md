# AGENTS.md — ai-quota-monitor

Read [`docs/plan.md`](docs/plan.md) first for architectural authority, then use the **Documentation map** below to load only the specs relevant to your task.
This repository is intended to be hosted publicly on GitHub: **never** put tokens, API keys, passwords, account IDs, device serials, personal emails, or local home paths (`/Users/...`) into any committed file, test fixture, commit message, or PR description.

## What this is

A native Android application (Kotlin + Jetpack Compose, Material 3 Expressive, zero WebViews) and companion quota-collection layer for monitoring AI model subscription quotas, reset windows, and burn-rate trends across multiple coding-agent and model subscriptions (`agy` / Antigravity, `opencode go`, `grok` Build, and future providers) on a Pixel 8 Pro (Android 16/17).

## Coordinator gate

Every session in this repo goes through Orca orchestration. Follow the global `orca-cheap-workers` skill for who plans, implements, and reviews. If this file and [`docs/workers.md`](docs/workers.md) disagree on that routing, this file and `orca-cheap-workers` win.

The pane a person opens is the coordinator, including a Grok chat. A prompt with a Dispatch ID is a worker and does only that spec. The coordinator loads `orca-cli`, `orchestration`, and `orca-cheap-workers`, then runs `orca skills get` for those guides before the first `orca` command. It checks `orca account list --json` and reads `result.rateLimits`. A window is available when `status` is `ok` and `usedPercent` is under 80. It dispatches the workers, then ends the turn. It does not edit the repo and does not commit.

Opening the chat in Grok does not choose the planner. Plan with `--agent antigravity --model gemini-4-argon-medium` and no `--effort` while the Antigravity bucket `Gemini 4 Argon` is available. Otherwise `--agent grok` while `grok.weekly` is available. Otherwise `--agent antigravity --model gemini-3.8-flash-high`. Otherwise `--agent opencode2`. Implement with `--agent opencode2` while `opencodeGo.session` and `opencodeGo.weekly` are available. If that quota is low or the launch fails, use `--agent antigravity --model gemini-3.8-flash-high`. Review with the other expensive planner: Argon planned, so review with Grok; Grok planned, so review with Argon. If that window is low, use a cheap worker that did not implement. If the only remaining reviewer is the author, say so.

`--agent grok` and `--agent opencode2` do not take `--model` or `--effort` on `worker-start`. Grok uses `~/.grok/config.toml` (`grok-4.7`, medium). One worktree per issue. Do not run two writers in it. A planner worker does not start the other workers.

| Stage | Worker |
| :--- | :--- |
| Plan | Argon Medium, else Grok, else Gemini 3.8 Flash, else opencode2 |
| Implement | opencode2, else Gemini 3.8 Flash |
| Review | The other expensive planner, as above |

Issue comments, specs, and repo invariants stay in [`docs/workers.md`](docs/workers.md) and [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md). Verification scripts: `./scripts/verify-all.sh` and `./scripts/review-plan-and-code.sh`.

## Layout

```text
.agents/skills/          Project-local agent skills (including orca-quota-engineering, Stitch, Android CLI, Compose, Kotlin)
.stitch/                 Stitch design system (DESIGN.md), screen metadata, and downloaded prototype assets
.githooks/pre-commit     Public-repo secret, token, and private-path guard (enable via git config core.hooksPath .githooks)
.github/workflows/       CI/CD pipelines (lint, test, APK build, secret hygiene check)
app/                     Native Android application (Kotlin, Jetpack Compose, Material 3, Glance widgets)
collector/               Companion macOS quota collector, launchd template, & AES-256-GCM relay service
docs/                    Architecture, provider wire specs, Stitch design docs, worker playbook, lessons, status, and bug tracker
scripts/                 Verification, Grok 3-gate review, and Pixel 8 Pro ADB deployment scripts
skills-lock.json         Locked skill versions installed via npx skills
```

## Hard rules

- **100% Native Android UI (No WebViews)**: All screens, charts, gauges, and animations must be built with native Jetpack Compose and Material 3. Never wrap a web page in a `WebView`.
- **Public-repo secret & PII hygiene**:
  - Enable the pre-commit hook on every clone/worktree: `git config core.hooksPath .githooks`.
  - Never commit `.env`, `local.properties`, `*.jks`, `*.keystore`, `auth.json`, `*.db`, or unredacted captures.
  - Never include real tokens (`Bearer ...`, `eyJ...`, `xai-...`), user IDs, device serial numbers, or local user paths (`/Users/<username>`) in source code, tests, or docs. Use `$HOME` or `~` in documentation and synthetic fixtures (`00000000-0000-0000-0000-000000000000`, `user@example.com`) in tests.
- **Scrubbed logs and errors**: Neither the collector nor the Android app may ever log `Authorization` headers, session tokens, or cookies in Logcat, crash reports, or HTTP error messages.
- **Do not invent provider endpoints or wire shapes**: [`docs/providers.md`](docs/providers.md) records the verified quota commands and HTTP wire shapes. Two upstream endpoints (`opencode go` and `grok`) are undocumented and can change shape—always parse defensively and back parsers with fixture tests.
- **GitHub Issue-First Workflow (Mandatory)**:
  - Every bug or feature MUST be created as a GitHub Issue **before** any implementation starts (`./scripts/gh-issue-workflow.sh create "<title>" "<body>"`).
  - Every stage of work MUST be appended as a structured comment on that GitHub Issue using `./scripts/gh-issue-workflow.sh comment <issue-number> <plan|implementation|testing|review> "<body-or-@file>"`:
    1. `## [PLAN]` — Architectural plan & files in scope.
    2. `## [IMPLEMENTATION]` — Summary of code changes & commits (`Refs #<issue>`).
    3. `## [TESTING]` — Unit test, Gradle lint/build, and emulator/device verification output. The plan comment chooses `UI evidence: before-and-after` or `UI evidence: none`. For a before-and-after change, run `./scripts/gh-issue-workflow.sh before-shot <issue> --attach <file-outside-the-repo>#Before: <old screen>` before editing UI files. The commit hook rejects the UI commit until that shot is registered. The testing comment then attaches that same before shot and `--attach <file>#After: <new screen>`. Do not commit the captures. Collector-only results that never opened the UI must say `No emulator UI was exercised.`
    4. `## [REVIEW]` — Verdict from the review worker chosen above (`./scripts/review-plan-and-code.sh worktree <path> --issue <issue-number>` when that worker is Grok).
  - `.githooks/commit-msg` enforces that commits reference `#<issue-number>` when `origin` is configured, and `./scripts/gh-issue-workflow.sh close <issue-number>` refuses to close an issue unless all four `[PLAN]`, `[IMPLEMENTATION]`, `[TESTING]`, and `[REVIEW]` comments exist.
- **Update docs & codify learnings in the same change (unprompted)**: The implementer worker, before it finishes, updates [`docs/lessons.md`](docs/lessons.md), [`docs/bugs/index.md`](docs/bugs/index.md), [`docs/status.md`](docs/status.md), [`README.md`](README.md) (for any run/test/deploy workflow change), and [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md), then runs `./scripts/verify-all.sh`. The coordinator checks that this happened. The coordinator does not do the edit itself.
- **Keep `AGENTS.md` a signpost, never a running log**: Record progress in [`docs/status.md`](docs/status.md), design details in [`docs/design/`](docs/design/README.md), and defects in [`docs/bugs/`](docs/bugs/README.md).

## Documentation map (progressive discovery)

| Topic | Where to read / update |
| :--- | :--- |
| Architecture, options analysis, & phased build plan | [`docs/plan.md`](docs/plan.md) |
| Provider quota wire specs (`agy`, `opencode go`, `grok`) & normalization schema | [`docs/providers.md`](docs/providers.md) |
| Stitch UI/UX workflow, `DESIGN.md`, screen inventory, & Compose mapping | [`docs/design/README.md`](docs/design/README.md) |
| Orca orchestration playbook (issue comments and specs; routing is the coordinator gate above) | [`docs/workers.md`](docs/workers.md) |
| Architectural lessons, invariants, & historical bug post-mortems | [`docs/lessons.md`](docs/lessons.md), [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md) |
| Current project state, installed skills, & open decision gates | [`docs/status.md`](docs/status.md) |
| Bug tracker lifecycle, filing template, & defect registry | [`docs/bugs/README.md`](docs/bugs/README.md), [`docs/bugs/index.md`](docs/bugs/index.md) |
