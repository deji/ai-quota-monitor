---
name: orca-quota-engineering
description: Project-specific architecture, multi-agent Orca orchestration workflow (agy Argon planner + agy Flash implementer + grok 4.7 reviewer), cross-language AES-256-GCM relay invariants, provider wire parsing rules, and verification gates for ai-quota-monitor. Use whenever planning, implementing, testing, or reviewing changes in this repository.
---

# Orca Quota Engineering Skill (`ai-quota-monitor`)

Use this skill whenever planning, modifying, testing, or reviewing `collector/`, `app/`, `.stitch/`, or `docs/` in `ai-quota-monitor`.

## 1. Mandatory 3-Gate Orca Orchestration Workflow

All non-trivial work in this repository uses three distinct agent roles and **three mandatory review gates**:

| Role | Agent / CLI | Model & Effort | Responsibility |
| :--- | :--- | :--- | :--- |
| **Planner & Coordinator** | `agy` (`antigravity`) | `gemini-4-argon-high` (`high`) | Architecture, Stitch UI/UX prototyping, task specs, worker supervision, and integration. |
| **Implementer & Tester** | `agy` (`antigravity`) | `gemini-3.8-flash-high` (`high`) | Feature implementation, unit/UI tests, Gradle builds, and emulator/device verification. |
| **Plan & Code Reviewer** | `grok` (Grok Build CLI) | `grok-4.7` (`--effort medium`) | Read-only review of plans, worktree diffs, and cross-system contracts. |

### The GitHub Issue-First Lifecycle (`scripts/gh-issue-workflow.sh`) & 3 Review Gates (`scripts/review-plan-and-code.sh`)
0. **Gate 0 — GitHub Issue Creation & Plan Comment (Mandatory Before Code)**:
   Every bug or feature MUST be filed as a GitHub Issue first, and its architectural plan MUST be posted as a `## [PLAN]` comment on that issue:
   ```bash
   ./scripts/gh-issue-workflow.sh create "<title>" "<description>" "enhancement"
   ./scripts/gh-issue-workflow.sh comment <issue-number> plan "<plan-markdown>"
   ```
1. **Gate 1 — Plan Review (Pre-Implementation)**:
   Before dispatching implementation workers for a new feature or architectural change, run `grok` against `docs/plan.md` and `docs/providers.md`:
   ```bash
   ./scripts/review-plan-and-code.sh plan --issue <issue-number>
   ```
2. **Gate 2 — Worktree Implementation, Testing & Review Comments**:
   Every commit must reference `#<issue-number>` (enforced by `.githooks/commit-msg`). After an `antigravity` (`gemini-3.8-flash-high`) worker finishes and passes unit/hygiene tests, post the `## [IMPLEMENTATION]` and `## [TESTING]` comments, release its worker terminal, and run `grok` against its worktree with `--issue <issue-number>` to post `## [REVIEW]`:
   ```bash
   ./scripts/gh-issue-workflow.sh comment <issue-number> implementation "<summary>"
   ./scripts/gh-issue-workflow.sh comment <issue-number> testing "<test-output>"
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
7. **Persisted Model Visibility, Hero Spotlight Pinning & Chart Telemetry (`APP-UX2`)**:
   - `PreferencesStore` must distinguish an unset key (`!prefs.contains(KEY_HIDDEN_BUCKETS)`) from an empty set (`"Show All"`). Never remove the key to represent Show All, and always store and return defensive copies (`HashSet(it)`) to prevent mutable reference corruption.
   - `QuotaRepositoryImpl` must serialize all hidden-bucket and pinning updates under `settingsMutex`, automatically unhiding a bucket when pinned and clearing `pinnedBucketId` when hidden, followed by `QuotaCompactWidget().updateAll(ctx)` and `QuotaWideWidget().updateAll(ctx)`. In UI callers (`ModelFilterSheet`), invoke only `onSetPinnedBucket`—never call `toggleBucketVisibility` before pinning.
   - All dashboard surfaces, including `ProviderBentoRow`, must respect `hiddenBucketIds`. When all buckets for a provider are hidden, omit its card; when partially hidden, drive radial gauges from visible buckets and select the active visible bucket on click.
   - In-app widget previews (`GlancePreviewCard`) must faithfully reflect the same configuration options (such as `QuotaPolarity`) across both the 2x2 tile and the 4x2 Orca Stage Deck as the home-screen AppWidgets (`QuotaCompactWidget` and `QuotaWideWidget`). Sub-metric pills (OpenCode 5h rolling) must retain explicit click modifiers targeting their own bucket.
   - `QuotaSnapshot.heroBucket(hidden, pinned)` returns `null` when all visible buckets are hidden and no usable pin exists (omitting `HeroBottleneckCard` rather than falling back to a hidden bottleneck), and tie-breaks equal remaining percentages by bucket ID ascending.
   - `BurnVelocityChart` must never substitute `100.0` for missing history keys—it only plots streams with at least one recorded data point and distinguishes "All models hidden" from "No history recorded yet" in empty states.
   - Stacked bottom sheets (`BucketDetailSheet`, `SyncHubSheet`, `ModelFilterSheet`) must prioritize the topmost layer's `BackHandler`.

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
