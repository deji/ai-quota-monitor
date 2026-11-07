# AI Quota Monitor

A high-craft, **100% native Android application** (Kotlin + Jetpack Compose, Material You Dynamic Color + Material 3 Expressive, zero WebViews) and companion macOS quota-collection daemon for monitoring AI model subscription quotas, rolling/weekly/monthly reset windows, and burn-rate velocity across coding-agent subscriptions (`agy` / Antigravity, `opencode go`, and `grok` Build CLI) on a **Pixel 8 Pro** (Android 14–16, `minSdk = 34`, `targetSdk = 36`).

---

## Features & Architecture

- **Hybrid Master Native Compose UI**:
  - **Orca Multi-Agent Role Readiness Strip**: Live readiness pills for `PLANNER` (`Gemini 4 Argon`), `BUILDER` (`min(Gemini 3.8 Flash 5h, OpenCode Go Rolling)`), and `REVIEWER` (`Grok 4.7 Build` + OAuth lease status).
  - **Custom `Canvas` Radial Gauges & Burn-Rate Sparklines**: `240°` `QuotaRadialArcGauge` with an outer-rim **Target Pace Notch** comparing actual consumption against linear window elapsed time, plus `24h / 7d` `BurnVelocityChart` and `MiniSparkline` curves.
  - **Time-Travel Horizon Scrubber Dock**: Persistent bottom scrubber (`NOW → +1H → +4H → RESET`) projecting remaining quota across every bucket at current burn rates; the scrub base snapshot is frozen mid-drag so in-flight refreshes never jump the gauges.
  - **Lifecycle-Aware Foreground Auto-Sync & Live Snapshot Age**: Polls every 60s while the app is `STARTED` (30s resume debounce, single-flight `syncMutex`, skipped while scrubbing, priming, unconfigured, or already syncing) and appends a non-blocking `· 3m old` age suffix driven by a local 30s clock in the Dashboard status pill and Sync Hub card.
  - **Interactive Detail & Sync Modal Sheets**: Tap any bucket card to open `BucketDetailSheet` or tap the connection status badge to open `SyncHubSheet`.
  - **Model Quota Filtering & Hero Spotlight Pinning (`ModelFilterSheet`)**: Filter out unwanted secondary model quotas (Claude & GPT 3P hidden by default), apply one-tap presets (`Hide Claude/3P`, `Orca Core Only`, `Show All`), pin any active model to lock it into the primary Hero Spotlight gauge, and declutter secondary pools via progressive disclosure collapsible cards.
  - **Grok Lease Bar & Smart Prime Controls**: Live countdown for the `~/.grok/auth.json` OAuth token, manual **Prime Pulse** button, and configurable **Active-Hours Wake Slider (`08:00–22:00`)** synced to the Mac collector.
  - **Native Jetpack Glance Home-Screen Widgets**: `2x2` Compact (`QuotaCompactWidget`) and `4x2` Wide Orca Stage Deck (`QuotaWideWidget`).
- **Dual-Path Transport (Tailscale Live Daemon + AES-256-GCM Encrypted Gist Relay)**:
  - **Primary Live Path (Tailscale)**: Direct authenticated HTTP over Tailscale (`100.64.0.0/10` / `*.ts.net`) to the Mac collector (`GET /v1/quota`, `POST /v1/quota/refresh`, `POST /v1/grok/prime`, `POST /v1/config/active-hours`, `GET /v1/history`).
  - **Offline-Mac Fallback (AES-256-GCM Gist Relay)**: Every collection cycle encrypts the normalized v1 snapshot with a 256-bit key (`AES-256-GCM`, 12-byte random nonce, 16-byte authentication tag) and publishes `quota_snapshot.enc.json` to GitHub Gist, readable via a **Direct Raw Gist URL** (`gist.githubusercontent.com/...`) or authenticated GitHub API (`api.github.com/gists/{id}`).
- **Hardened Security & Public-Repo Hygiene**:
  - Android secrets (`tailscale_token`, `gist_key`, `github_token`) are encrypted at rest via `AndroidKeyStore` (`KeystoreSecretStore`, fail-closed) with `android:allowBackup="false"`.
  - Multi-stage Git hooks ([`.githooks/pre-commit`](.githooks/pre-commit), [`.githooks/commit-msg`](.githooks/commit-msg), and [`.githooks/pre-push`](.githooks/pre-push)) and automated repo hygiene tests ([`collector/tests/test_repo_hygiene.py`](collector/tests/test_repo_hygiene.py)) block tokens, PEM private keys, nested config/secret files, APK/keystore binaries, oversized files (`> 5 MB`), unresolved merge conflict markers, non-synthetic emails, and `/Users/<username>` or `/home/<username>` paths from ever entering commits, commit messages, or pushes.

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
   # Auto-detect Tailscale IPv4 (100.x.y.z) or bind to 127.0.0.1:8741
   python3 -m collector serve --host tailscale --port 8741
   ```

4. **Install as a Background macOS `launchd` Agent**:
   ```bash
   python3 -m collector install-launchd
   ```
   This installs and loads `~/Library/LaunchAgents/com.aiquotamonitor.collector.plist` so the collector polls and publishes encrypted Gist snapshots automatically in the background.

---

### 4. Deploy to Your Pixel 8 Pro (`scripts/deploy-pixel8pro.sh`)

#### Option A: Connect Your Pixel 8 Pro Wirelessly via ADB (No USB Cable Needed)

1. **Enable Developer Options on the Pixel 8 Pro** (if not already enabled):
   - Open **Settings → About phone**, scroll to the bottom, and tap **Build number** 7 times until you see *"You are now a developer!"* (enter your PIN when prompted).
2. **Turn on Wireless Debugging**:
   - Ensure your Pixel 8 Pro and this Mac are on the **same Wi-Fi network** (or connected to the same **Tailscale** tailnet).
   - Open **Settings → System → Developer options**, scroll down to the **Debugging** section, and toggle **Wireless debugging** ON (tap into the **Wireless debugging** text to open its sub-screen, and choose **Always allow on this network**).
3. **One-Time Device Pairing (`adb pair`)**:
   - Inside the **Wireless debugging** screen on your phone, tap **Pair device with pairing code**.
   - A popup will show a **Wi-Fi pairing code** (6 digits) and an **IP address & Port** (e.g., `192.168.1.50:37123` — note that the pairing port is *different* from the main connection port!).
   - Keep that popup open on your phone and run on this Mac:
     ```bash
     adb pair <phone-ip>:<pairing-port> <6-digit-code>
     # Example: adb pair 192.168.1.50:37123 482910
     ```
   - You will see: `Successfully paired to <phone-ip>:<pairing-port>`.
4. **Connect ADB (`adb connect`)**:
   - After the popup closes on your phone, look at the main **Wireless debugging** screen under **IP address & Port** (this port is different from the pairing port, e.g., `192.168.1.50:41555`).
   - Run on this Mac:
     ```bash
     adb connect <phone-ip>:<connect-port>
     # Example: adb connect 192.168.1.50:41555
     ```
   - Verify it shows `device` in:
     ```bash
     adb devices -l
     ```
   *(Tip: You can also run `./scripts/deploy-pixel8pro.sh --wireless` for an interactive prompt that pairs, connects, installs, and launches the app in one go.)*

#### Option B: Connect via USB-C Cable

1. Plug your Pixel 8 Pro into this Mac via USB-C.
2. Open **Settings → System → Developer options** and toggle **USB debugging** ON.
3. Tap **Allow** on the *"Allow USB debugging?"* prompt on your phone screen.

#### Install & Launch the App

Once your Pixel 8 Pro has Wireless Debugging enabled (and is paired with this Mac) or is connected via USB-C, run:

```bash
# Auto-discovers paired Wireless ADB over Bonjour (_adb-tls-connect._tcp) if not yet connected, installs, and launches:
./scripts/deploy-pixel8pro.sh

# Or deploy specifically to a local virtual emulator (e.g. Pixel_8_Pro_Virtual / emulator-5554):
./scripts/deploy-pixel8pro.sh --emulator

# Or target a specific ADB serial when multiple devices are attached:
./scripts/deploy-pixel8pro.sh --device <serial>

# Or build and install the signed Release APK:
./scripts/deploy-pixel8pro.sh --release
```

Or manually via `adb`:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.aiquotamonitor/.ui.MainActivity
```
3. **Configure Sync in the App**:
   - Ensure the **Tailscale** app is active on your Pixel 8 Pro.
   - Tap the **Sync Status Pill** in the top app bar (or switch to the **Widgets & Sync** tab) to open the **Sync Hub**:
     - **Tailscale Collector URL**: `http://<your-mac-tailscale-ip>:8741` (e.g., `http://100.x.y.z:8741`)
     - **Bearer Token**: Copy `bearerToken` from `~/.config/ai-quota-monitor/config.json`
     - **Cloud Relay (Optional Offline Fallback)**:
       - Paste either the **Raw Gist URL** (`https://gist.githubusercontent.com/<owner>/<gist_id>/raw/quota_snapshot.enc.json`, no GitHub token required) **or** the **Gist ID** + a **GitHub Fine-Grained Read Token**
       - **AES-256-GCM Key**: Copy `gistKeyHex` from `~/.config/ai-quota-monitor/config.json`
     - Tap **Save & Test Sync**.

---

### 5. GitHub Issue-First Workflow & 3-Gate `grok-4.7` Reviews (`scripts/gh-issue-workflow.sh` & `scripts/review-plan-and-code.sh`)

All bugs and features follow a mandatory **GitHub Issue-First Workflow** where the issue is created first and all four stages (`[PLAN]`, `[IMPLEMENTATION]`, `[TESTING]`, `[REVIEW]`) are recorded as comments on the issue:

```bash
# 1. Create a GitHub Issue first (scrubbed for secret/PII hygiene)
./scripts/gh-issue-workflow.sh create "<title>" "<description>" "enhancement"

# 2. Post Plan, Implementation, and Testing comments on the Issue
./scripts/gh-issue-workflow.sh comment <issue-number> plan "<plan-details>"
./scripts/gh-issue-workflow.sh comment <issue-number> implementation "<commit-and-diff-summary>"
./scripts/gh-issue-workflow.sh comment <issue-number> testing "<verify-all-results>"

# 3. Run the 3-Gate Grok 4.7 review and automatically post ## [REVIEW] to the Issue
./scripts/review-plan-and-code.sh plan --issue <issue-number>
./scripts/review-plan-and-code.sh worktree /path/to/worktree --issue <issue-number>
./scripts/review-plan-and-code.sh cross-system --issue <issue-number>

# 4. Verify all 4 lifecycle comments exist and close the Issue
./scripts/gh-issue-workflow.sh close <issue-number>
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
| [`docs/workers.md`](docs/workers.md) | Orca multi-agent orchestration playbook (`agy` Argon Medium planner + quota-aware `opencode2`/Flash 3.8 implementer + `grok` 4.7 Medium reviewer) |
| [`.agents/skills/orca-quota-engineering/SKILL.md`](.agents/skills/orca-quota-engineering/SKILL.md) | Project-local skill codifying the 3-gate review workflow, golden test vector rules, and verification gates |
| [`docs/status.md`](docs/status.md) | Project status, toolchain audit, and milestone tracker |
| [`docs/bugs/index.md`](docs/bugs/index.md) | Defect registry and remediation verification log |
