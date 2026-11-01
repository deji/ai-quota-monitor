# AI Quota Monitor

A high-craft, 100% native Android application (Kotlin + Jetpack Compose) for monitoring AI model subscription quotas, rolling/weekly/monthly reset windows, and burn-rate trends across multiple coding-agent subscriptions (`agy` / Antigravity, `opencode go`, `grok` Build, and extensible providers). Built for the **Pixel 8 Pro** with Material 3 Expressive design, smooth micro-interactions, and zero WebViews.

## Documentation Index

| Document | Purpose |
| :--- | :--- |
| [`AGENTS.md`](AGENTS.md) | Agent instructions, hard rules, repo layout, and progressive-discovery map |
| [`docs/plan.md`](docs/plan.md) | Architectural plan, open design decisions, toolchain setup, and delivery phases |
| [`docs/providers.md`](docs/providers.md) | Upstream quota collection mechanisms (`agy`, `opencode go`, `grok`) and normalized schema |
| [`docs/design/README.md`](docs/design/README.md) | Google Stitch prototype workflow, design system (`DESIGN.md`), and screen specs |
| [`docs/workers.md`](docs/workers.md) | Orca multi-agent orchestration protocol (Gemini 4 Argon High, Gemini 3.8 Flash High, Grok 4.7 Medium) |
| [`docs/status.md`](docs/status.md) | Project status, environment audit, installed skills, and milestone log |
| [`docs/bugs/index.md`](docs/bugs/index.md) | Defect tracker and verification log |

## Repository Hygiene (Public Repo)

Enable the repository's pre-commit secret guard immediately after cloning:

```bash
git config core.hooksPath .githooks
```

No tokens, API keys, signing keystores, device serials, or personal file paths are ever committed to this repository.
