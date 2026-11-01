# AGENTS.md — ai-quota-monitor

Read [`docs/plan.md`](docs/plan.md) first for architectural authority, then use the **Documentation map** below to load only the specs relevant to your task.
This repository is intended to be hosted publicly on GitHub: **never** put tokens, API keys, passwords, account IDs, device serials, personal emails, or local home paths (`/Users/...`) into any committed file, test fixture, commit message, or PR description.

## What this is

A native Android application (Kotlin + Jetpack Compose, Material 3 Expressive, zero WebViews) and companion quota-collection layer for monitoring AI model subscription quotas, reset windows, and burn-rate trends across multiple coding-agent and model subscriptions (`agy` / Antigravity, `opencode go`, `grok` Build, and future providers) on a Pixel 8 Pro (Android 16/17).

## Multi-Agent Orca Orchestration Roles

All work in this repository is coordinated through **Orca Orchestration** (`orca orchestration ...`):

| Role | Agent / CLI | Model & Effort | Responsibilities |
| :--- | :--- | :--- | :--- |
| **Planner & Coordinator** | `agy` (Antigravity) | `gemini-4-argon-high` (`Gemini 4 Argon (High)`) | Architecture, Stitch UI/UX prototype design & alignment, task decomposition, Orca dispatch & supervision, integration sign-off. |
| **Implementer & Tester** | `agy` (Antigravity) | `gemini-3.8-flash-high` (`Gemini 3.8 Flash (High)`) | Feature implementation, unit/UI/screenshot tests, Gradle builds, emulator/device verification via `orca-emulator-android` / `android` CLI. |
| **Code & Plan Reviewer** | `grok` (Grok Build CLI) | `grok-4.7` (`--effort medium`) | Read-only plan, diff, cross-system contract, security/secret-hygiene, and Compose performance review before implementation and before merge. |

See [`docs/workers.md`](docs/workers.md), [`docs/lessons.md`](docs/lessons.md), and [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md) for the exact 3-gate Orca workflow and verification scripts (`./scripts/verify-all.sh` and `./scripts/review-plan-and-code.sh`).

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
- **Update docs in the same change**: Keep [`docs/plan.md`](docs/plan.md), [`docs/providers.md`](docs/providers.md), [`docs/design/README.md`](docs/design/README.md), [`docs/status.md`](docs/status.md), and [`docs/bugs/`](docs/bugs/README.md) in sync with the code in the same commit.
- **Keep `AGENTS.md` a signpost, never a running log**: Record progress in [`docs/status.md`](docs/status.md), design details in [`docs/design/`](docs/design/README.md), and defects in [`docs/bugs/`](docs/bugs/README.md).

## Documentation map (progressive discovery)

| Topic | Where to read / update |
| :--- | :--- |
| Architecture, options analysis, & phased build plan | [`docs/plan.md`](docs/plan.md) |
| Provider quota wire specs (`agy`, `opencode go`, `grok`) & normalization schema | [`docs/providers.md`](docs/providers.md) |
| Stitch UI/UX workflow, `DESIGN.md`, screen inventory, & Compose mapping | [`docs/design/README.md`](docs/design/README.md) |
| Orca orchestration playbook (`agy` planner/implementer + `grok` reviewer) | [`docs/workers.md`](docs/workers.md) |
| Architectural lessons, invariants, & historical bug post-mortems | [`docs/lessons.md`](docs/lessons.md), [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md) |
| Current project state, installed skills, & open decision gates | [`docs/status.md`](docs/status.md) |
| Bug tracker lifecycle, filing template, & defect registry | [`docs/bugs/README.md`](docs/bugs/README.md), [`docs/bugs/index.md`](docs/bugs/index.md) |
