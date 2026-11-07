---
name: orca-quota-engineering
description: Project-specific architecture, multi-agent Orca orchestration workflow (agy Argon Medium planner + quota-aware opencode2/Flash implementer + grok 4.7 reviewer), cross-language AES-256-GCM relay invariants, provider wire parsing rules, and verification gates for ai-quota-monitor. Use whenever planning, implementing, testing, or reviewing changes in this repository.
---

# Orca Quota Engineering Skill (`ai-quota-monitor`)

Use this skill whenever planning, modifying, testing, or reviewing `collector/`, `app/`, `.stitch/`, or `docs/` in `ai-quota-monitor`.

## 1. Mandatory 3-Gate Orca Orchestration Workflow

All non-trivial work in this repository uses three distinct agent roles and **three mandatory review gates**:

| Role | Agent / CLI | Model & Effort | Responsibility |
| :--- | :--- | :--- | :--- |
| **Planner & Coordinator** | `agy` (`antigravity`; CLI `--agent antigravity`) | `gemini-4-argon-medium` (`medium`) | Architecture, Stitch UI/UX prototyping, task specs, worker supervision, and integration. Quota fallback per `orca-cheap-workers`: Argon Medium → `grok-4.7` medium → `gemini-3.8-flash-high` → `opencode2`. |
| **Implementer & Tester** | Quota-aware routing (`orca-cheap-workers`) | Primary `opencode2` (`opencode-go/deepseek-v4.1-flash`, no flags); fallback `gemini-3.8-flash-high` (`high`) | Feature implementation, unit/UI tests, Gradle builds, and emulator/device verification. |
| **Plan & Code Reviewer** | `grok` (Grok Build CLI) | `grok-4.7` (`--effort medium`) | Read-only review of plans, worktree diffs, and cross-system contracts. Gate 1 and Gate 2 always use `grok-4.7` medium as the project specialization of `orca-cheap-workers`'s "other expensive planner" rule. |

### The GitHub Issue-First Lifecycle (`scripts/gh-issue-workflow.sh`) & 3 Review Gates (`scripts/review-plan-and-code.sh`)
0. **Gate 0 — GitHub Issue Creation & Plan Comment (Mandatory Before Code)**:
   Every bug or feature MUST be filed as a GitHub Issue first, and its architectural plan MUST be posted as a `## [PLAN]` comment on that issue:
   ```bash
   ./scripts/gh-issue-workflow.sh create "<title>" "<description>" "enhancement"
   ./scripts/gh-issue-workflow.sh comment <issue-number> plan "<plan-markdown>"
   # The plan body must contain exactly one line: UI evidence: before-and-after
   # or UI evidence: none.
   ```
1. **Gate 1 — Plan Review (Pre-Implementation, Mandatory on Every Feature/UX Issue)**:
   Before dispatching implementation workers for a new feature, UX change, or architectural change, run a supervised `grok-4.7` (`--effort medium`) Plan Review against the task spec and `docs/plan.md` + `docs/providers.md`, and post the review to `## [PLAN]`:
   ```bash
   # Via supervised Orca worker (after pre-trusting the worktree in ~/.grok/trusted_folders.toml):
   orca orchestration worker-start --task-title "Issue #<n>: Gate 1 Plan Review" --spec "<prompt>" --worktree "<worktreeSelector>" --agent grok --json
   # Or via helper script (auto-trusts target worktree and posts ## [PLAN] when --issue is given):
   ./scripts/review-plan-and-code.sh plan "<worktree-path>" --spec "<spec-file>" --issue <issue-number>
   ```
   - **Grok Orca Worker Rule**: `orca orchestration worker-start --agent grok` reads `default = "grok-4.7"` and `default_reasoning_effort = "medium"` from `~/.grok/config.toml` (it does **not** accept `--model` or `--effort` CLI flags) and requires `[folders."<worktreePath>"]\ntrusted = true` in `~/.grok/trusted_folders.toml` so newly created worktrees never block on interactive TUI folder trust (`agent-trust-workspace`).
   - **Follow-Up Worker Dispatch Rule**: When sending Gate 1 review findings or follow-up instructions to a running worker terminal via `orca orchestration send`, always pass `--type dispatch` (valid types: `status, dispatch, worker_done, merge_ready, escalation, handoff, decision_gate, question, heartbeat`; `--type steer` is invalid).
2. **Gate 2 — Worktree Implementation, Testing & Review Comments**:
   Every commit must reference `#<issue-number>` (enforced by `.githooks/commit-msg`). After a quota-routed implementer worker (`opencode2` primary, fallback `--agent antigravity --model gemini-3.8-flash-high --effort high`, never `--agent agy`) finishes and passes unit/hygiene tests, post the `## [IMPLEMENTATION]` and `## [TESTING]` comments, release its worker terminal, and run `grok` against its worktree with `--issue <issue-number>` to post `## [REVIEW]`:
   ```bash
   # Before editing UI files, when the plan says UI evidence: before-and-after:
   ./scripts/gh-issue-workflow.sh before-shot <issue-number> --attach "$TMPDIR/before.png#Before: previous screen"
   ./scripts/gh-issue-workflow.sh comment <issue-number> implementation "<summary>"
   ./scripts/gh-issue-workflow.sh comment <issue-number> testing "<test-output>" \
     --attach "$TMPDIR/before.png#Before: previous screen" \
     --attach "$TMPDIR/after.png#After: screen after the change"
   ./scripts/review-plan-and-code.sh worktree "<worktree-path>" --issue <issue-number>
   ```
3. **Gate 3 — Cross-System Integration Review & Issue Verification (Pre-Merge / Close)**:
   Whenever a change touches both `collector/` and `app/` (or `docs/`), run a cross-system review, and verify all 4 issue comment stages (`[PLAN]`, `[IMPLEMENTATION]`, `[TESTING]`, `[REVIEW]`) before closing the issue:
   ```bash
   ./scripts/review-plan-and-code.sh cross-system --issue <issue-number>
   ./scripts/gh-issue-workflow.sh close <issue-number>
   ```

---

## 2. Non-Negotiable Architectural & Security Invariants

1. **100% Native Android UI (Zero WebViews)**:
   - Every screen, gauge (`QuotaRadialArcGauge`), chart (`BurnVelocityChart`, `MiniSparkline`), sheet (`BucketDetailSheet`, `SyncHubSheet`), dock (`TimeTravelScrubberDock`), and home-screen widget (`QuotaCompactWidget` `2x2`, `QuotaWideWidget` `4x2`) must be pure Jetpack Compose / Glance with Material You Dynamic Color and `fontFeatureSettings = "tnum"`.
2. **Public-Repo Secret & PII Hygiene (`SEC-G1`)**:
   - Keep `git config core.hooksPath .githooks` enabled in the main repo and every worktree so `pre-commit`, `commit-msg`, and `pre-push` all enforce public-repo safety.
   - Never commit or log tokens (`Bearer ...`, `eyJ...`, `xai-...`, `gh[pousr]_...`, `github_pat_...`, `sk-ant-...`, `AKIA...`), PEM private keys, APK/keystore binaries, files `> 5 MB`, merge conflict markers, account IDs, device serials, machine hostnames (`socket.gethostname()`), non-synthetic emails, or `/Users/<username>` / `/home/<username>` paths.
3. **Cross-Language AES-256-GCM Envelope Interoperability**:
   - `collector/crypto_relay.py` and `GistEnvelopeDecryptor.kt` share the v1 envelope schema (`v=1`, `alg="AES-256-GCM"`, `nonce` (12-byte Base64, `iv` accepted as alias), `ciphertext`, `tag` (16-byte Base64), `capturedAt`).
   - Any change to envelope encryption or decryption **must** keep the **Cross-Language Golden Test Vector** green in both `collector/tests/test_collector.py` and `GistEnvelopeDecryptorTest.kt`.
4. **Fail-Closed Upstream Wire Parsing**:
   - `agy`: `remaining_fraction` is fraction **left** (`0.0..1.0`, clamped). Skip buckets where `remaining_fraction` is `None`.
   - `opencode_go`: Accept both `usage.{rolling,weekly,monthly}` and `usage.usage.{rolling,weekly,monthly}`. Skip any window missing a numeric `percent` or where `status != "ok"`.
   - `grok`: Unwrap `{"val": <number>}` objects. If neither `creditUsagePercent` nor `productUsage[].usagePercent` is numeric, mark `status = "parse_error"` with `buckets = []` (never default to `0% used` / `100% left`). When both exist, use `max(creditUsagePercent, productUsage)`.
   - `QuotaBucket.resetsAt`: Always nullable (`String? = null`) in Kotlin and Python.
   - `BUILDER` Orca Role: `min(gemini-5h, opencode-rolling)`.
5. **Android Keystore & Network Security**:
   - `KeystoreSecretStore` encrypts `tailscale_token`, `gist_key`, and `github_token` using `AndroidKeyStore` (`AES/GCM/NoPadding`) and fails closed on Android (never writing plaintext secrets to `SharedPreferences`).
   - `TailscaleApiClient` permits `http://` **only** for private/Tailscale/loopback addresses (`100.64.0.0/10`, `127.0.0.1`, `localhost`, RFC1918, `*.ts.net`) and rejects public `http://` hosts.
6. **Compose Settings State, Non-Destructive Probing & Honest Source Labels (`APP-UX1`)**:
   - Key `ConnectionSettingsForm` draft state on individual credential strings (never on the whole `SyncSettings` object, which also carries `activeHoursStart`/`End`), and serialize all settings mutations in `QuotaRepositoryImpl` under `settingsMutex`.
   - Use `SyncSettings.hasUsableTransport` (`tailscaleUrl.isNotBlank() || (gistId.isNotBlank() && gistAesKey.isNotBlank())`) everywhere transport readiness is checked.
   - Never persist `SeedData` to `cached_snapshot`; ensure `testConnection` probes non-destructively (preserving stored secrets and restoring prior `ConnectionState` on failure); and keep collector port `8741` consistent across code, placeholders, and docs.
7. **Android Adaptive Launcher Icons & Material You Theming Invariants (`APP-ICO1` / Issue #2)**:
   - Adaptive icons must adhere to the 108dp outer canvas / 66dp diameter safe area circle (center `(54, 54)`, $R \le 33\text{dp}$; maximum foreground extent is the amber pace pip at 29.8dp, alongside the arc stroke at 29.25dp) to prevent clipping under arbitrary OEM launcher masks (Circle, Squircle, Rounded Square, Teardrop).
   - Android 13+ (API 33+) Material You dynamic theming requires a dedicated `<monochrome>` layer in `ic_launcher.xml` and `ic_launcher_round.xml`. It must use `#FFFFFF` fills/strokes with differentiated alpha (e.g. 30% alpha for background tracks). Crucially, because VectorDrawable uses source-over blending, stacking a `#00000000` path does not create cutouts; true knockouts must be subpaths using `android:fillType="evenOdd"`.
   - Always back adaptive vector drawables with hermetic unit tests (`AdaptiveIconResourceTest`) asserting geometry and attribute invariants.
8. **Persisted Model Visibility, Hero Spotlight Pinning & Chart Telemetry (`APP-UX2`)**:
   - `PreferencesStore` must distinguish an unset key (`!prefs.contains(KEY_HIDDEN_BUCKETS)`) from an empty set (`"Show All"`). Never remove the key to represent Show All, and always store and return defensive copies (`HashSet(it)`) to prevent mutable reference corruption.
   - `QuotaRepositoryImpl` must serialize all hidden-bucket and pinning updates under `settingsMutex`, automatically unhiding a bucket when pinned and clearing `pinnedBucketId` when hidden, followed by `QuotaCompactWidget().updateAll(ctx)` and `QuotaWideWidget().updateAll(ctx)`. In UI callers (`ModelFilterSheet`), invoke only `onSetPinnedBucket`—never call `toggleBucketVisibility` before pinning.
   - All dashboard surfaces, including `ProviderBentoRow`, must respect `hiddenBucketIds`. When all buckets for a provider are hidden, omit its card; when partially hidden, drive radial gauges from visible buckets and select the active visible bucket on click.
   - In-app widget previews (`GlancePreviewCard`) must faithfully reflect the same configuration options (such as `QuotaPolarity`) across both the 2x2 tile and the 4x2 Orca Stage Deck as the home-screen AppWidgets (`QuotaCompactWidget` and `QuotaWideWidget`). Sub-metric pills (OpenCode 5h rolling) must retain explicit click modifiers targeting their own bucket.
   - `QuotaSnapshot.heroBucket(hidden, pinned)` returns `null` when all visible buckets are hidden and no usable pin exists (omitting `HeroBottleneckCard` rather than falling back to a hidden bottleneck), and tie-breaks equal remaining percentages by bucket ID ascending.
   - `BurnVelocityChart` must never substitute `100.0` for missing history keys—it only plots streams with at least one recorded data point and distinguishes "All models hidden" from "No history recorded yet" in empty states.
   - Stacked bottom sheets (`BucketDetailSheet`, `SyncHubSheet`, `ModelFilterSheet`) must prioritize the topmost layer's `BackHandler`.
9. **Release Workflow Dual-APK Invariant, Wireless ADB Bonjour Discovery & Linting Rules (`app/lint.xml` + `AQM-LINT-001..008`)**:
   - `.github/workflows/release.yml` must execute `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease` so both `app-debug.apk` and `app-release.apk` are verified and built before `softprops/action-gh-release@v2` attaches release assets (`AQM-LINT-007`).
   - `./scripts/deploy-pixel8pro.sh` supports `--release` and `--debug` flags and uses macOS Bonjour (`dns-sd -B _adb-tls-connect._tcp local.` → `dns-sd -L` → `dns-sd -G v4`) plus Tailscale (`tailscale status`) to automatically discover the active Wireless Debugging port and `adb connect` over either LAN (`<lan-ip>:<port>`) or Tailscale (`<100.x.y.z>:<port>` when macOS Local Network privacy or AP isolation returns `No route to host` on LAN). **Never** log or commit the discovered `_adb-tls-connect._tcp` service instance name, as Android embeds the device serial number in the mDNS instance name.
   - `app/lint.xml` (wired via `lintConfig = file("lint.xml")` with `abortOnError = true` in `app/build.gradle.kts`) enforces `MutatingSharedPrefs`, `CommitPrefEdits`, `SetJavaScriptEnabled`, `JavascriptInterface`, `HardcodedDebugMode`, `MutableCollectionMutableState`, and `RememberSaveableSaverParameter` at `severity="error"`.
   - `collector/tests/test_repo_hygiene.py` (`test_architectural_and_code_lint_rules`) enforces `AQM-LINT-001` (Zero WebViews), `AQM-LINT-002` (`app/lint.xml` error rules), `AQM-LINT-003` (`prefs.contains(KEY_HIDDEN_BUCKETS)` + `HashSet` defensive copies), `AQM-LINT-004` (no `remember(settings)` on composite `SyncSettings`), `AQM-LINT-005` (no `saveCachedSnapshot(SeedData...)`), `AQM-LINT-006` (no `?: 100.0` in `BurnVelocityChart.kt`), `AQM-LINT-007` (`release.yml` builds both debug and release APKs), and `AQM-LINT-008` (no `onToggleBucket` before `onSetPinnedBucket` in `ModelFilterSheet.kt`).
10. **Personal Device Isolation, Local ARM64 AVD Testing & Linting Rules (`AQM-LINT-009` & `AQM-LINT-010` / `APP-ICO2`)**:
    - **Personal Device Off-Limits**: Personal devices connected over USB or wireless ADB must NEVER be automatically targeted or probed by automated agents or deployment scripts. Always target the local virtual device (`Pixel_8_Pro_Virtual` / `emulator-5554`) or pass `--emulator`.
    - **Local AVD Lifecycle**: Apple Silicon Mac AVDs run native ARM64 with Hypervisor acceleration (`orca emulator attach Pixel_8_Pro_Virtual` boots and binds directly to Orca's Emulator pane). Use `orca emulator ax` to inspect the UI Automator hierarchy and `adb -s emulator-5554 exec-out screencap -p` for visual evidence.
    - **Screenshot evidence stays on the GitHub issue (`SEC-G2`)**: The plan comment decides with exactly one line, `UI evidence: before-and-after` or `UI evidence: none`. When shots are required, the implementer runs `before-shot` while the UI files still match the pre-change commit. `.githooks/commit-msg` rejects a later UI commit until that receipt exists. The testing comment must attach the same Before file plus an After file. The script checks the Before file hash, posts Before first, refuses in-repo paths, and uses a cleaned filename. `git commit --no-verify` skips the hook. The hook does not press the shutter; it refuses the commit until a real PNG was registered first. Collector-only results that never opened the UI must include `No emulator UI was exercised.`
    - **Adaptive Icon Full-Bleed Rule**: `ic_launcher_background.xml` must seamlessly fill the entire 108dp canvas (`#0B0F12`) without sub-canvas geometry (e.g. inner discs of radius 36dp) that produces outer rim edge artifacts on larger circular or squircle launcher masks.
    - **Inactive Gauge Track Contrast**: On dark obsidian surfaces, inactive dial tracks must maintain sufficient luminance delta (e.g. `#243338` or ~15–20% alpha) to be legible at small launcher scales.
    - **Lint Rules (`AQM-LINT-009` & `AQM-LINT-010`)**: `app/lint.xml` enforces `IconXmlAndPng`, `IconLocation`, and `MipmapIcons` as error (`AQM-LINT-009`), and `scripts/deploy-pixel8pro.sh` enforces `--device` and `--emulator` options and fails closed when multiple connected devices exist without explicit targeting (`AQM-LINT-010`).
    - **Themed Evidence**: Pixel Launcher may serve a stale full-color app-drawer icon cache after toggling themed icons. Enable the toggle, restart the launcher, drag the app onto the home screen, and capture the themed home screen; keep `AdaptiveIconResourceTest` asserting the full-bleed `#0B0F12` plate and `#243338` track (`AQM-LINT-011`).
11. **Lifecycle-Aware Foreground Auto-Sync & Snapshot Age Feedback (`APP-SYNC1` / Issue #1)**:
    - **Never use `ConnectionState.SYNCING` for background polling**: it disables `ConnectionSettingsForm` buttons and can corrupt `testConnection` prior-state restoration. Use `isAutoSyncingFlow` / `QuotaViewModel.isAutoSyncing` and preserve the existing `ConnectionState`.
    - **Serialize every network/snapshot writer** (`refresh`, `testConnection`, `primeGrok`, `updateActiveHours`, and the refresh inside `saveSettings`) through `syncMutex` with a monotonic generation guard; auto-sync ticks use atomic `tryLock()` and return immediately instead of queueing, and must not stamp `lastSyncAttemptEpochMsFlow`. Never nest `settingsMutex` inside `syncMutex` (persist a successful `testConnection` draft only after releasing `syncMutex`).
    - **Stamp `lastSyncAttemptEpochMsFlow` at the start of every real attempt** (including failures, `saveSettings`, and the live network paths of `primeGrok` / `updateActiveHours`) and use the injectable `clock`. `runForegroundSyncLoop` polls every `AUTO_SYNC_INTERVAL_MS = 60_000L` while STARTED with a `AUTO_SYNC_MIN_GAP_MS = 30_000L` resume debounce, and must be launched only from `repeatOnLifecycle(Lifecycle.State.STARTED)` — never from a second `ON_START` observer.
    - **Auto-sync eligibility** requires `settings.hasUsableTransport`, `scrubOffsetHours == 0.0`, `!isPriming`, `connectionState != SYNCING`, and `!isAutoSyncing`; background refresh must never touch `_userMessage`.
    - **Freeze the scrub base snapshot** (`scrubFrozenSnapshot`) from offset `> 0.0` until reset to `0.0`, then immediately reveal the latest repository snapshot. `QuotaSnapshot.ageSeconds` / `formatAgeShort` must tolerate blank/unparseable `capturedAt` (`""`), future timestamps (`"Just now"`), and be driven by a 30s lifecycle-aware ticker rather than network polling. Drive `isStale` from `activeSnapshot` and OR it with a live `ageSeconds(nowEpochMs) > 7200` check in the UI so the `(Stale)` badge updates across the boundary.
12. **Configurable Glance Widget Buckets & Two-Pass Fallback (`APP-UX4` / Issue #4)**:
    - **Persistence**: `compact_widget_bucket_id` is a sanitized `String?` (`null`/blank = Auto Hero Spotlight) and `wide_widget_bucket_ids` is an ordered comma-delimited `String` (never `putStringSet`/`getStringSet`). Missing/blank/empty wide values mean "Orca Roles (default)"; reads and writes trim, drop blanks, `distinct()`, cap at `MAX_WIDE_WIDGET_SLOTS = 3`, remove the key when empty, return fresh immutable lists, and use `apply()` only. Do not copy the `KEY_HIDDEN_BUCKETS` `contains()` contract onto the wide key.
    - **Atomic mutations**: `setCompactWidgetBucket`, `setWideWidgetBuckets`, and `toggleWideWidgetBucket` run under `settingsMutex` (disjoint from `syncMutex`), build the next wide list from `PreferencesStore.getWideWidgetBucketIds()` inside the lock, auto-unhide selected concrete ids in the same transaction, never touch visibility for `role:` tokens, never call `toggleBucketVisibility`, and call `updateAll()` only after the lock is released. Hiding a bucket later leaves the widget preference intact; resolution treats it as absent.
    - **Resolution**: `QuotaSnapshot.resolveCompactWidgetItem` resolves Auto/concrete/`role:` (case-insensitive) and falls back to `heroBucket(..., isFallback = true)`, or `null` when all buckets are hidden. `resolveWideWidgetItems` always returns exactly 3 `WidgetQuotaItem`s: reserve visible role primaries and explicit `role:`/concrete requests in pass 1 (`usedBucketIds`), then fill holes from the remaining visible pool in pass 2; emit `Double.NaN` placeholders when exhausted. Role cells use `OrcaRoleReadiness.remainingPercent` and `usedPercent = (100.0 - remainingPercent).coerceAtLeast(0.0)`; fallback cells carry the substitute's own id/category/title/percents.
    - **Glance + preview parity**: both `provideGlance` implementations read polarity, hidden ids, pin, widget selection, and `getCachedSnapshotJson()` inside `provideContent` and resolve there, so `updateAll()` repaints during an active session. Both previews and both widgets guard `displayPercent.isNaN()` before color thresholds, render `--` in slate `#94A3B8`, suppress pace pills on NaN, and show `FALLBACK • <category>` in amber alongside the substitute label. `GlancePreviewCard` no longer uses a `?: 35.0` stand-in and drives live M3 `FilterChip` selection (`Auto`, roles, buckets, `Orca Core Trio`, `#1–#3` slot badges).

---

## 3. Standard Verification Gate (`scripts/verify-all.sh`)

Before completing any task or merging any branch, run:

```bash
./scripts/verify-all.sh
```

This executes 5 automated gates:
1. `.githooks/pre-commit` + repo-wide secret & PII scan (`test_repo_hygiene.py`)
2. Python collector unit test suite (`python3 -m unittest discover -s collector/tests -v`)
3. Android lint, JUnit unit tests, and debug APK build (`./gradlew lintDebug testDebugUnitTest assembleDebug`)
4. APK artifact verification (`app/build/outputs/apk/debug/app-debug.apk`)
5. **Learnings, Bug Ledger & Skill Sync Check**: Verifies all living docs (`docs/lessons.md`, `docs/bugs/index.md`, `docs/status.md`, `README.md`, and this `SKILL.md`) are present and that every defect ID in `docs/bugs/index.md` is documented in `docs/lessons.md`.

---

## 4. Mandatory Unprompted End-of-Task Closeout Protocol

**Never wait for the user to ask you to update docs, record lessons, or update skills/scripts.** At the end of every task, bug fix, or `grok-4.7` review cycle, execute this checklist before reporting completion:

1. **Capture Learnings (`docs/lessons.md`)**: Record every non-obvious root cause, Compose/Android quirk, wire-protocol edge case, or review finding in [`docs/lessons.md`](../../../docs/lessons.md) (both in the thematic section and the §5 Historical Bug Ledger).
2. **Sync Defect & Status Trackers (`docs/bugs/index.md`, `docs/status.md`)**: Ensure every defect ID (`COL-*`, `APP-*`, `SYS-*`) is logged in [`docs/bugs/index.md`](../../../docs/bugs/index.md) and cross-referenced in `docs/lessons.md`.
3. **Update User-Facing Run/Deploy Instructions (`README.md`)**: If any CLI flag, port, UI path, or ADB/deployment step changed, update [`README.md`](../../../README.md) immediately.
4. **Codify Reusable Rules & Checks (`SKILL.md`, `scripts/`)**: If a new class of bug was discovered, add the invariant to Section 2 of this `SKILL.md` and, where possible, add an automated check in `scripts/verify-all.sh` or unit tests.
