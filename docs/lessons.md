# Engineering Lessons, Architectural Invariants & Historical Bug Post-Mortems

This document captures the hard-won architectural, security, wire-protocol, UI/UX, and multi-agent orchestration lessons from building `ai-quota-monitor`. Read this before modifying the plan, collector, crypto relay, or Android application.

---

## 1. Multi-Agent Orca Orchestration Lessons

### 1.1 Always Review the Architectural Plan *Before* and *Across* Implementation
- **What happened**: Initially, `grok` (`grok-4.7 --effort medium`) was dispatched to review `collector-service` and `android-app` in isolation against `docs/plan.md`. Both passed their isolated reviews (`APPROVED`), yet a subsequent dedicated **Plan & Cross-System Review** caught **Critical** integration bugs (`SYS-C1`: `nonce` vs `iv` JSON field mismatch in the AES-256-GCM Gist envelope; `SYS-C2`: GitHub's Sept 2025 API change returning `401` on unauthenticated secret-gist API reads; `SYS-H4`: `TailscaleApiClient.primeGrok()` decoding `SmartPrimeStatus` instead of `QuotaSnapshot`).
- **Rule Going Forward**:
  1. **Gate 1 (Plan Review)**: Run `grok -m grok-4.7 --effort medium --always-approve` on `docs/plan.md` + `docs/providers.md` *before* dispatching implementation workers.
  2. **Gate 2 (Worktree Review)**: Run `grok` on each implementation worktree after unit tests pass.
  3. **Gate 3 (Cross-System Integration Review)**: Run `grok` across `docs/` + `collector/` + `app/` together to verify producer/consumer contracts (JSON schemas, crypto envelopes, HTTP routes) match byte-for-byte.

### 1.2 Orca CLI & `grok` Invocation Quirks
- **Antigravity worker alias**: In `orca orchestration worker-start`, use `--agent antigravity --model gemini-3.8-flash-high --effort high` (not `--agent agy`).
- **Grok reviewer invocation**: In `orca orchestration worker-start`, `--agent grok` rejects `--model` and `--effort` flags and blocks on interactive TUI folder trust (`agent-trust-workspace`) in newly created worktrees. Always invoke `grok` for reviews in headless single-turn mode with `--always-approve` (while instructing it strictly not to modify files):
  ```bash
  grok -m grok-4.7 --effort medium --always-approve --cwd "<worktree-or-repo-path>" -p "<read-only review prompt>"
  ```
- **Worker settlement discipline**: Never run `orca orchestration check --ack <delivery_id>` for a `worker_done` message until you have (a) independently re-run the test/hygiene gates in the worker's worktree and (b) settled the worker terminal via `orca orchestration worker-release --dispatch <ctx_id>`.

---

## 2. Cross-Boundary & Cryptography Lessons

### 2.1 Never Test Producer & Consumer Crypto Only Against Self-Mocks
- **What happened (`SYS-C1`)**: Python's `collector/crypto_relay.py` emitted `{"v": 1, "alg": "AES-256-GCM", "nonce": "...", "ciphertext": "...", "tag": "...", "capturedAt": "..."}`. Kotlin's `GistEnvelopeDecryptor.kt` looked only for `"iv"`. Both unit test suites passed 100% because Python tested `decrypt(encrypt(x))` and Kotlin tested `decrypt(mockKotlinEnvelope)`.
- **Fix & Invariant**:
  - Lock a single canonical envelope schema in [`docs/providers.md`](providers.md) (`nonce` primary, `iv` accepted as alias).
  - Maintain a **Cross-Language Golden Test Vector** (fixed 32-byte key, fixed 12-byte nonce, fixed JSON snapshot, exact Base64 ciphertext & tag) asserted in both `collector/tests/test_collector.py` and `app/src/test/java/com/aiquotamonitor/domain/GistEnvelopeDecryptorTest.kt`.
  - Bind `capturedAt` across the outer envelope and inner decrypted snapshot so an attacker or stale cache cannot re-label an old ciphertext with a newer timestamp.

### 2.2 Base64 vs Hex Ambiguity on Short Binary Fields (`APP-M2/L3`)
- **What happened**: A 12-byte GCM IV encoded in Base64 is 16 characters long. If all 16 Base64 characters happen to fall in `[0-9a-fA-F]`, a naive `isHex` check decodes it as 8 hex bytes instead of 12 Base64 bytes, causing `GCMParameterSpec` to fail.
- **Fix & Invariant**: Only treat a string as hex when it has an explicit `0x`/`hex:` prefix or when its length equals `expectedByteLength * 2` (`24` chars for a 12-byte IV, `64` chars for a 32-byte key); otherwise decode as Base64 first and validate exact byte length (`12` bytes for IV, `16` bytes for GCM tag, `32` bytes for AES-256 key).

### 2.3 GitHub Secret Gist Relay Rules (Post-Sept 2025) (`SYS-C2`)
- **What happened**: Since September 2025, GitHub's REST API (`GET https://api.github.com/gists/{id}`) returns `401 Unauthorized` for unauthenticated reads of secret gists, and anonymous `api.github.com` requests are rate-limited to `60/hr` per IP. Furthermore, since November 2025, unlisted gists are scanned by GitHub secret scanning.
- **Fix & Invariant**:
  - Support **Direct Raw Gist URLs** (`https://gist.githubusercontent.com/<owner>/<gist_id>/raw/quota_snapshot.enc.json`), which require no GitHub token on the phone and bypass the `60/hr` REST API limit, **and** support an optional **GitHub Fine-Grained Read Token** (encrypted in Android Keystore) when fetching via `api.github.com/gists/{id}`.
  - Never put unencrypted tokens or machine hostnames (`socket.gethostname()`) inside the Gist; secrecy relies 100% on the 256-bit AES-GCM key stored only in `~/.config/ai-quota-monitor/config.json` (`0600`) and Android Keystore.

---

## 3. Upstream Provider Wire-Protocol Lessons

| Provider | Wire Pitfall | Required Defensive Rule |
| :--- | :--- | :--- |
| **`agy`** | `remaining_fraction` is fraction **LEFT** (`0.0..1.0`), whereas other providers report percent **USED**. Missing `remaining_fraction` if defaulted to `1.0` masks broken buckets (`COL-L10`). | Skip any bucket where `remaining_fraction` is `None`; clamp valid values to `[0.0, 1.0]`. |
| **`opencode go`** | Upstream `GET https://opencode.ai/zen/go/v1/usage` nests windows under `{"usage": {"rolling": ...}}`, which becomes `usage.usage.*` when wrapped (`SYS-H5`). Requires `User-Agent: opencode/2.2.2`. | Accept both `usage.*` and `usage.usage.*`. Fail closed (skip bucket / mark `parse_error`) if `percent` is missing or non-numeric—never default to `0% used`. |
| **`grok`** | Wraps `onDemandCap`, `onDemandUsed`, and `prepaidBalance` in `{"val": <number>}` objects. Can report usage in `creditUsagePercent` or `productUsage[].usagePercent` (`SYS-H5`). | Unwrap `{"val": ...}` recursively. Fail closed (`status = "parse_error"`) if neither usage field is numeric. When both exist, use `max(creditUsagePercent, productUsage)` so a `0` in one field never hides real usage in the other. Fall back to secondary billing URL on `HTTPError`, `URLError`, timeout, or `parse_error`. |
| **All Providers** | Upstream `resetsAt` can be `null` when a window has not started (`SYS-H5`). | `QuotaBucket.resetsAt` must be nullable (`String? = null`) in both Python and Kotlin (`kotlinx.serialization`). |

---

## 4. Android Security, Compose & Glance Widget Lessons

1. **Fail-Closed Android Keystore (`APP-H1`, `SYS-H6`)**:
   - Never fall back to returning plaintext if `AndroidKeyStore` encryption fails on a real device. Use an ephemeral in-memory AES key fallback *only* when `KeyStore.getInstance("AndroidKeyStore")` is unavailable in host JVM unit tests.
   - Always set `android:allowBackup="false"` and configure `@xml/data_extraction_rules` and `@xml/backup_rules` so encrypted preferences and cached snapshots are excluded from cloud/ADB backups.
2. **Private-Network Cleartext HTTP Guard (`APP-H2`, `SYS-H3`)**:
   - Android `targetSdk = 36` blocks cleartext HTTP by default, while the local Mac collector over Tailscale uses `http://100.x.y.z:8741`.
   - Enforce in `TailscaleApiClient` that `http://` URLs are permitted **only** for Tailscale CGNAT (`100.64.0.0/10`), loopback (`127.0.0.1`, `localhost`), RFC1918 private LAN, and `*.ts.net` hosts—never send the bearer token over `http://` to a public internet host.
3. **Glance Widget State Synchronization (`APP-M4`, `SYS-H6`)**:
   - `GlanceAppWidget` does not automatically re-render when `SharedPreferences` changes. Explicitly call `QuotaCompactWidget().updateAll(context)` and `QuotaWideWidget().updateAll(context)` whenever a new snapshot is saved or the `% Left` / `% Used` polarity toggle changes.
   - Always badge fallback demo data (`SeedData`) with `"DEMO"` on widgets and flag snapshots older than 2 hours (`7200s`) as `STALE`.
4. **Google Stitch MCP Lessons**:
   - `create_design_system` only accepts specific font and color variant enums (`MANROPE`/`INTER` + `FIDELITY`; `PLUS_JAKARTA_SANS` + `EXPRESSIVE`; `SPACE_GROTESK`/`GEIST` + `VIBRANT`).
   - Direct HTTP `GET` against Stitch CDN URLs (`lh3.googleusercontent.com/aida/...`) returns `401 Unauthorized`; always use Stitch MCP's `download_file` tool RPC to fetch base64-encoded PNG and HTML assets.
5. **Compose Form Draft State vs Composite Settings Updates (`APP-UX1`)**:
   - Never key form draft `remember(...)` state on an entire composite `SyncSettings` object (`remember(settings)`) when another control on the same screen (such as `ActiveHoursSlider`) writes partial updates to `SyncSettings`—doing so resets unsaved text fields while the user is typing.
   - Always key draft state only on the specific credential strings (`remember(settings.tailscaleUrl, settings.tailscaleToken, settings.gistId, settings.gistAesKey, settings.githubToken)`), and serialize all repository settings writes (`saveSettings`, `updateActiveHours`, `updateSettings`, and `testConnection` commits) under a single `Mutex` (`settingsMutex`) that merges onto the latest persisted settings.
6. **Non-Destructive Connection Probing & Honest Cache vs Demo Labeling (`APP-UX1`)**:
   - A "Test Connection" action (`testConnection(draftSettings)`) must probe using the draft credentials without overwriting stored Keystore secrets if the probe fails, must restore the prior `ConnectionState` on failure, and must combine both Tailscale and Gist error causes when both fail.
   - Never persist `SeedData` into `cached_snapshot` preferences—only persist real snapshots from `TAILSCALE_LIVE` or `CLOUD_RELAY_SYNCED` so `ConnectionState.OFFLINE` ("Offline Cache") is never shown for demo seed numbers.
   - Use a single shared predicate (`SyncSettings.hasUsableTransport`: `tailscaleUrl.isNotBlank() || (gistId.isNotBlank() && gistAesKey.isNotBlank())`) across the Dashboard onboarding card, `SyncHubSheet`, and `WidgetsAndSyncScreen` so a Gist ID without an AES key is marked `"Incomplete (needs ID & AES key)"` rather than `"Configured"`.
7. **First-Run Discoverability, Port Consistency & Wireless ADB UX**:
   - Never bury first-run endpoint configuration at the bottom of a secondary tab or make status sheets read-only. Surface a first-run onboarding card (`ConnectCollectorCard`) on the Dashboard and make `SyncHubSheet` directly editable.
   - Keep default ports (`8741`) identical across `collector/config.py`, `collector/cli.py`, UI placeholders, unit tests, and `README.md`.
   - Document Android 14–16 Wireless Debugging's two-port flow explicitly (`adb pair <ip>:<pairing-port> <code>` uses the temporary popup port, whereas `adb connect <ip>:<connect-port>` uses the main Wireless Debugging screen port).
8. **Multi-Stage Public-Repo Git Safety Guards & GitHub Issue-First Workflow (`SEC-G1`)**:
   - In shell `pre-commit` hooks, matching `$f` against `config.json` or `.env` in a `case "$f" in` statement only matches root-level files and silently lets `sub/config.json` or `app/.env.local` slip through, while `for f in $staged` splits paths containing spaces (`Demo Build.APK`). Always iterate with `while IFS= read -r f`, extract `base=$(basename "$f")`, lowercase it (`tr '[:upper:]' '[:lower:]'`), and fail closed if `git cat-file -s ":$f"` fails.
   - Use `grep -v '^+++' | grep '^+'` rather than `grep -E '^\+[^+]'` when extracting staged/commit diff additions so added lines starting with a literal `+` are never skipped.
   - In `commit-msg`, never strip `^#` lines before scanning for secrets/paths/emails—Markdown headings (`## [PLAN]`) passed through `scripts/gh-issue-workflow.sh` or written in commit bodies start with `#` and must be scanned too. Only strip `^#` git status comments when checking for the `#<issue-number>` reference.
   - In `pre-push`, always read `<local_ref> <local_oid> <remote_ref> <remote_oid>` from `stdin` and inspect the actual pushed commit range (`git ls-tree -r -l "$local_oid"` and `git log "$range" -p`) so secrets committed with `git commit --no-verify` or removed in a later working-tree edit are still blocked from reaching GitHub.
   - Enforce the **GitHub Issue-First Workflow** via `scripts/gh-issue-workflow.sh` (`create`, `comment <id> <plan|implementation|testing|review>`, `verify`, `close`) and `.githooks/commit-msg` (`#<issue-number>` check when `origin` is configured).
9. **Persisted Model Visibility, Hero Pinning & Chart Telemetry Invariants (`APP-UX2`)**:
   - **`SharedPreferences` StringSet Semantics**: An absent preference key (`!prefs.contains(KEY_HIDDEN_BUCKETS)`) must be distinguished from a user-selected empty set ("Show All" — `prefs.contains(KEY_HIDDEN_BUCKETS) == true`). Never `remove()` the preference key to represent "Show All" or the default hide (`3p-5h`, `3p-weekly`) will be reinstated on app restart. Furthermore, Android's `getStringSet` can return mutable internal references—always store and return defensive copies (`HashSet(it)`) on get and put.
   - **Mutex-Serialized Visibility & Pinning**: Pinning a bucket must automatically unhide it in the same `settingsMutex` transaction; hiding a currently pinned bucket must clear `pinnedBucketId` in the same transaction so hidden buckets never hijack the Hero spotlight. In UI callers (`ModelFilterSheet`), invoke only `onSetPinnedBucket`—never issue a redundant `toggleBucketVisibility` before pinning, which introduces a race condition between concurrent coroutines where an unhidden pin is immediately re-hidden.
   - **Filter Completeness Across All Dashboard Surfaces**: Hidden quotas must leave all dashboard surfaces, including `ProviderBentoRow`. If all buckets for OpenCode or Grok are hidden, omit the respective card; when partially hidden, drive radial gauges from visible buckets and select the active visible bucket on click.
   - **Glance Preview-Widget Fidelity**: In-app widget previews (`GlancePreviewCard`) must faithfully reflect the same configuration options (such as `QuotaPolarity`, displaying REM/USED and Left/Used) as the actual home-screen AppWidget (`QuotaCompactWidget`).
   - **Domain `heroBucket` Fallback & Tie-Breaking**: When all visible buckets are hidden and no usable pin exists, `heroBucket()` must return `null` (causing `HeroBottleneckCard` to be omitted) rather than falling back to the global `bottleneckBucket` which would display a hidden bucket. Equal `remainingPercent` values must tie-break deterministically by `id` ascending (`compareBy<QuotaBucket> { it.remainingPercent }.thenBy { it.id }`).
   - **Dynamic Velocity Bezier Charting without Flat Lines**: In `BurnVelocityChart`, never substitute `100.0` for missing history keys—doing so draws misleading flat lines across 24h for unrecorded streams. Only plot streams that have at least one recorded history point, draw curves strictly across valid points, and distinguish "All models hidden" from "No history recorded yet" in the empty state.
   - **Progressive Disclosure & BackHandler Priority**: Secondary pools (`AntigravityBucketsCard`) must default to a clean collapsed summary header to declutter the dashboard. When stacking modal sheets (`BucketDetailSheet`, `SyncHubSheet`, `ModelFilterSheet`), prioritize the topmost layer's `BackHandler` so pressing Back dismisses sheets in reverse z-order without interfering with bottom tab navigation.

---

## 5. Historical Bug & Remediation Ledger

| ID | Phase Caught | Root Cause | Resolution |
| :--- | :--- | :--- | :--- |
| **COL-H1** | Collector Review 1 | `_is_authenticated` returned `True` when `bearerToken` was empty; default host was `0.0.0.0`. | Fail closed (`401`) on empty/whitespace token; default bind to `127.0.0.1`; auto-mint CSPRNG token on `init-config` / `serve`. |
| **COL-M2/M3** | Collector Review 1 | `POST /v1/grok/prime` returned a partial dict instead of the v1 snapshot; `GET /v1/history` route was missing. | Re-collect after prime and return canonical `QuotaSnapshot`; added authenticated `GET /v1/history`. |
| **COL-M4** | Collector Review 1 | `TestHTTPServer` invoked live `agy` and `grok` CLIs during unit tests. | Patched provider collection and subprocess calls in `TestHTTPServer` (`31` tests run in `~1.1s`). |
| **APP-H1** / **APP-H2** | Android Review 1 | Bearer token & Gist key stored in plaintext `SharedPreferences` with `allowBackup="true"`; `targetSdk=35` blocked Tailscale HTTP. | Added `KeystoreSecretStore` (`AES/GCM/NoPadding`), `allowBackup="false"`, `targetSdk=36`, and `network_security_config.xml`. |
| **APP-M1** / **APP-M3** / **APP-M4** | Android Review 1 | Pull-to-refresh only called `GET /v1/quota`; `BucketDetailSheet` & `SyncHubSheet` were missing; Glance widgets didn't refresh on sync. | Wired `POST /v1/quota/refresh`, added modal sheets + persistent `TimeTravelScrubberDock`, and triggered `updateAll(context)` on widgets. |
| **APP-M5** | Android Review 1 | Navigation tab reset on configuration change, back gesture exited activity from secondary tabs/sheets, and scrubbing triggered 700ms `tween` lag. | Used `rememberSaveable` for tab state, wired `BackHandler` to dismiss sheets and return to Dashboard, and switched to `spring()` physics with `snap()` while dragging the scrubber. |
| **SYS-C1** | Plan & Cross-System Review | Collector wrote `"nonce"` in Gist envelope; Android decryptor required `"iv"`. | Unified v1 envelope (`nonce` primary, `iv` alias), bound `capturedAt`, and added shared Python/Kotlin golden test vector. |
| **SYS-C2** | Plan & Cross-System Review | GitHub API returns `401` on unauthenticated secret-gist reads since Sept 2025. | Added Raw Gist URL (`gist.githubusercontent.com`) support + optional Keystore-encrypted GitHub Read Token. |
| **SYS-H3** / **SYS-H4** / **SYS-H5** / **SYS-H6** | Plan & Cross-System Review | Missing `launchd` plist & single-flight lock; expired tokens (`<=0s`) skipped by Smart Prime; fail-open parsers on missing usage fields; non-null `resetsAt`. | Added `launchd` plist + `install-launchd`, `threading.Lock` + background poll loop, `POST /v1/config/active-hours`, fail-closed parsers, nullable `resetsAt`, and 2h staleness check. |
| **APP-UX1** | Live Pixel 8 Pro Verification | Connection settings form was buried at the bottom of the 3rd tab (`Widgets & Sync`), `SyncHubSheet` was read-only, draft credentials keyed on `remember(settings)` were wiped by `ActiveHoursSlider` updates, and `testConnection` overwrote secrets on failure. | Extracted `ConnectionSettingsForm` (keyed on credential strings, port `8741` placeholder, non-destructive `testConnection` probe), added `ConnectCollectorCard` on the Dashboard when `!settings.hasUsableTransport`, embedded the form in `SyncHubSheet`, placed it first on `Widgets & Sync`, and serialized `QuotaRepositoryImpl` settings merges under `settingsMutex`. |
| **SEC-G1** | Public GitHub Publication Prep | `pre-commit` matched exact root filenames (`config.json` missed `sub/config.json`), did not block APK binaries, files `> 5 MB`, merge conflict markers, or non-synthetic emails, and lacked `commit-msg` and `pre-push` guards. | Upgraded `.githooks/pre-commit` with `basename` matching, `5 MB` size cap, conflict marker detection, and expanded token/PEM/email/path checks; added `.githooks/commit-msg` and `.githooks/pre-push`; and added automated temporary-repo hook tests in `collector/tests/test_repo_hygiene.py`. |
| **APP-UX2** | Gate 1 & 2 Orca Review | Dashboard and Burn Velocity chart were overly dense with unwanted secondary model families (Claude/GPT 3P in Gemini), lack of model filtering/pinning, and widget synchronization. | Added persisted model visibility filtering (`DEFAULT_HIDDEN_BUCKET_IDS = setOf("3p-5h", "3p-weekly")`) and hero spotlight pinning in `PreferencesStore` (distinguishing unset from user-selected `emptySet()`), serialized under `settingsMutex` in `QuotaRepositoryImpl` with widget updates; added `3p-weekly` to `SeedData`; added `QuotaSnapshot.visibleBuckets()` and `heroBucket()` with tie-breaking and null fallback when all hidden; added native M3 `ModelFilterSheet` with quick presets (`Hide Claude/3P`, `Orca Core Only`, `Show All`), per-bucket toggles, and hero pin; made `AntigravityBucketsCard` collapsible by default (progressive disclosure) filtering out hidden and hero buckets; updated `BurnVelocityChart` and `BurnVelocityScreen` with dynamic series filtering, honest empty states, and interactive filter chips; and wired `QuotaCompactWidget` and `GlancePreviewCard` to respect `heroBucket` with honest all-hidden fallbacks. |

