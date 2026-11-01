# AI Quota Monitor

A high-craft, **100% native Android application** (Kotlin + Jetpack Compose, Material You Dynamic Color + Material 3 Expressive, zero WebViews) and companion macOS quota-collection daemon for monitoring AI model subscription quotas, rolling/weekly/monthly reset windows, and burn-rate velocity across coding-agent subscriptions (`agy` / Antigravity, `opencode go`, and `grok` Build CLI) on a **Pixel 8 Pro** (Android 14–16, `minSdk = 34`, `targetSdk = 36`).

---

## Features & Architecture

- **Hybrid Master Native Compose UI**:
  - **Orca Multi-Agent Role Readiness Strip**: Live readiness pills for `PLANNER` (`Gemini 4 Argon`), `BUILDER` (`min(Gemini 3.8 Flash 5h, OpenCode Go Rolling)`), and `REVIEWER` (`Grok 4.7 Build` + OAuth lease status).
  - **Custom `Canvas` Radial Gauges & Burn-Rate Sparklines**: `240°` `QuotaRadialArcGauge` with an outer-rim **Target Pace Notch** comparing actual consumption against linear window elapsed time, plus `24h / 7d` `BurnVelocityChart` and `MiniSparkline` curves.
  - **Time-Travel Horizon Scrubber Dock**: Persistent bottom scrubber (`NOW → +1H → +4H → RESET`) projecting remaining quota across every bucket at current burn rates.
  - **Interactive Detail & Sync Modal Sheets**: Tap any bucket card to open `BucketDetailSheet` or tap the connection status badge to open `SyncHubSheet`.
  - **Grok Lease Bar & Smart Prime Controls**: Live countdown for the `~/.grok/auth.json` OAuth token, manual **Prime Pulse** button, and configurable **Active-Hours Wake Slider (`08:00–22:00`)** synced to the Mac collector.
  - **Native Jetpack Glance Home-Screen Widgets**: `2x2` Compact (`QuotaCompactWidget`) and `4x2` Wide Orca Stage Deck (`QuotaWideWidget`).
- **Dual-Path Transport (Tailscale Live Daemon + AES-256-GCM Encrypted Gist Relay)**:
  - **Primary Live Path (Tailscale)**: Direct authenticated HTTP over Tailscale (`100.64.0.0/10` / `*.ts.net`) to the Mac collector (`GET /v1/quota`, `POST /v1/quota/refresh`, `POST /v1/grok/prime`, `POST /v1/config/active-hours`, `GET /v1/history`).
  - **Offline-Mac Fallback (AES-256-GCM Gist Relay)**: Every collection cycle encrypts the normalized v1 snapshot with a 256-bit key (`AES-256-GCM`, 12-byte random nonce, 16-byte authentication tag) and publishes `quota_snapshot.enc.json` to GitHub Gist, readable via a **Direct Raw Gist URL** (`gist.githubusercontent.com/...`) or authenticated GitHub API (`api.github.com/gists/{id}`).
- **Hardened Security & Public-Repo Hygiene**:
  - Android secrets (`tailscale_token`, `gist_key`, `github_token`) are encrypted at rest via `AndroidKeyStore` (`KeystoreSecretStore`, fail-closed) with `android:allowBackup="false"`.
  - Pre-commit secret guard (`.githooks/pre-commit`) and automated repo hygiene tests (`collector/tests/test_repo_hygiene.py`) block tokens, keys, personal emails, and `/Users/<username>` paths from ever entering Git history.

---

## Quick Start: Testing, Running & Deploying

### 1. Enable Repository Secret Guard

Immediately after cloning (or creating a worktree), enable the pre-commit hook:

```bash
git config core.hooksPath .githooks
```

### 2. Run the Full Verification Suite (`scripts/verify-all.sh`)

Runs the pre-commit secret guard, all Python collector + repo-hygiene unit tests, Android Lint (`lintDebug`), Android JUnit unit tests (`testDebugUnitTest`), and builds `app/build/outputs/apk/debug/app-debug.apk`:

```bash
./scripts/verify-all.sh
```

Or run individual test suites directly:

```bash
# Python collector & secret-hygiene tests
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s collector/tests -v

# Android Lint, unit tests, and debug APK build
./gradlew lintDebug testDebugUnitTest assembleDebug
```

---

### 3. Run & Install the Mac Companion Collector (`collector/`)

1. **Initialize Configuration (`~/.config/ai-quota-monitor/config.json`, mode `0600`)**:
   ```bash
   python3 -m collector init-config
   ```
   This generates a 256-bit `bearerToken` and a 256-bit `gistKeyHex` (32 bytes / 64 hex chars) and prints the configuration summary.

2. **Test a Single Live Collection Cycle on macOS**:
   ```bash
   python3 -m collector collect --json
   ```

3. **Run the Collector Daemon Interactively (Bound to Tailscale IP or Loopback)**:
   ```bash
   # Auto-detect Tailscale IPv4 (100.x.y.z) or bind to 127.0.0.1:8787
   python3 -m collector serve --host tailscale --port 8787
   ```

4. **Install as a Background macOS `launchd` Agent**:
   ```bash
   python3 -m collector install-launchd
   ```
   This installs and loads `~/Library/LaunchAgents/com.aiquotamonitor.collector.plist` so the collector polls and publishes encrypted Gist snapshots automatically in the background.

---

### 4. Deploy to Your Pixel 8 Pro (`scripts/deploy-pixel8pro.sh`)

1. Connect your **Pixel 8 Pro** via USB (with **USB Debugging** enabled) or over Wireless Debugging / Tailscale (`adb connect <ip>:<port>`).
2. Run the one-command build, install, and launch script:
   ```bash
   ./scripts/deploy-pixel8pro.sh
   ```
   Or manually via `adb`:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.aiquotamonitor/.ui.MainActivity
   ```
3. **Configure Sync in the App**:
   - Ensure the **Tailscale** app is active on your Pixel 8 Pro.
   - Tap the **Sync Status Pill** in the top app bar (or switch to the **Sync** tab) to open the **Sync Hub**:
     - **Tailscale Collector URL**: `http://<your-mac-tailscale-ip>:8787` (e.g., `http://100.x.y.z:8787`)
     - **Bearer Token**: Copy `bearerToken` from `~/.config/ai-quota-monitor/config.json`
     - **Cloud Relay (Optional Offline Fallback)**:
       - Paste either the **Raw Gist URL** (`https://gist.githubusercontent.com/<owner>/<gist_id>/raw/quota_snapshot.enc.json`, no GitHub token required) **or** the **Gist ID** + a **GitHub Fine-Grained Read Token**
       - **AES-256-GCM Key**: Copy `gistKeyHex` from `~/.config/ai-quota-monitor/config.json`
     - Tap **Save & Test Sync**.

---

### 5. Run the 3-Gate `grok-4.7` Architectural & Code Reviews (`scripts/review-plan-and-code.sh`)

```bash
# Gate 1: Review architectural plan & provider wire specs
./scripts/review-plan-and-code.sh plan

# Gate 2: Review a specific implementation worktree
./scripts/review-plan-and-code.sh worktree /path/to/worktree

# Gate 3: Full cross-system review across docs/, collector/, and app/
./scripts/review-plan-and-code.sh cross-system
```

---

## Documentation & Skills Index

| Document / Skill | Purpose |
| :--- | :--- |
| [`AGENTS.md`](AGENTS.md) | Agent instructions, hard rules, repo layout, and progressive-discovery map |
| [`docs/plan.md`](docs/plan.md) | Authoritative system & Android architecture, security boundaries, and delivery roadmap |
| [`docs/providers.md`](docs/providers.md) | Upstream wire specs (`agy`, `opencode go`, `grok`), v1 snapshot schema, and v1 AES-256-GCM envelope spec |
| [`docs/lessons.md`](docs/lessons.md) | Engineering lessons, cross-boundary invariants, and historical bug post-mortems (`COL-*`, `APP-*`, `SYS-*`) |
| [`docs/design/README.md`](docs/design/README.md) | Google Stitch 3-set + Hybrid Master prototype workflow, [`DESIGN.md`](.stitch/DESIGN.md), and Compose mapping |
| [`docs/workers.md`](docs/workers.md) | Orca multi-agent orchestration playbook (`agy` Argon High + `agy` Flash 3.8 High + `grok` 4.7 Medium) |
| [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md) | Project-local skill codifying the 3-gate review workflow, golden test vector rules, and verification gates |
| [`docs/status.md`](docs/status.md) | Project status, toolchain audit, and milestone tracker |
| [`docs/bugs/index.md`](docs/bugs/index.md) | Defect registry and remediation verification log |
