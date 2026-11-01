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

### The 3 Review Gates (`scripts/review-plan-and-code.sh`)
1. **Gate 1 — Plan Review (Pre-Implementation)**:
   Before dispatching implementation workers for a new feature or architectural change, run `grok` against `docs/plan.md` and `docs/providers.md`:
   ```bash
   ./scripts/review-plan-and-code.sh plan
   ```
2. **Gate 2 — Worktree Implementation Review**:
   After an `antigravity` (`gemini-3.8-flash-high`) worker finishes and passes unit/hygiene tests, release its worker terminal and run `grok` against its worktree:
   ```bash
   ./scripts/review-plan-and-code.sh worktree "<worktree-path>"
   ```
3. **Gate 3 — Cross-System Integration Review (Pre-Merge / Release)**:
   Whenever a change touches both `collector/` and `app/` (or `docs/`), run a cross-system review to verify JSON schemas, HTTP endpoints, and crypto envelopes match across Python and Kotlin:
   ```bash
   ./scripts/review-plan-and-code.sh cross-system
   ```

---

## 2. Non-Negotiable Architectural & Security Invariants

1. **100% Native Android UI (Zero WebViews)**:
   - Every screen, gauge (`QuotaRadialArcGauge`), chart (`BurnVelocityChart`, `MiniSparkline`), sheet (`BucketDetailSheet`, `SyncHubSheet`), dock (`TimeTravelScrubberDock`), and home-screen widget (`QuotaCompactWidget` `2x2`, `QuotaWideWidget` `4x2`) must be pure Jetpack Compose / Glance with Material You Dynamic Color and `fontFeatureSettings = "tnum"`.
2. **Public-Repo Secret & PII Hygiene**:
   - Keep `git config core.hooksPath .githooks` enabled in the main repo and every worktree.
   - Never commit or log tokens (`Bearer ...`, `eyJ...`, `xai-...`, `ghp_...`), account IDs, device serials, machine hostnames (`socket.gethostname()`), personal emails, or `/Users/<username>` paths.
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

---

## 3. Standard Verification Gate (`scripts/verify-all.sh`)

Before completing any task or merging any branch, run:

```bash
./scripts/verify-all.sh
```

This executes:
1. `.githooks/pre-commit` + repo-wide secret & PII scan (`test_repo_hygiene.py`)
2. Python collector unit test suite (`python3 -m unittest discover -s collector/tests -v`)
3. Android lint, JUnit unit tests, and debug APK build (`./gradlew lintDebug testDebugUnitTest assembleDebug`)
