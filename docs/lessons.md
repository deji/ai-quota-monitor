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

---

## 5. Historical Bug & Remediation Ledger

| ID | Phase Caught | Root Cause | Resolution |
| :--- | :--- | :--- | :--- |
| **COL-H1** | Collector Review 1 | `_is_authenticated` returned `True` when `bearerToken` was empty; default host was `0.0.0.0`. | Fail closed (`401`) on empty/whitespace token; default bind to `127.0.0.1`; auto-mint CSPRNG token on `init-config` / `serve`. |
| **COL-M2/M3** | Collector Review 1 | `POST /v1/grok/prime` returned a partial dict instead of the v1 snapshot; `GET /v1/history` route was missing. | Re-collect after prime and return canonical `QuotaSnapshot`; added authenticated `GET /v1/history`. |
| **COL-M4** | Collector Review 1 | `TestHTTPServer` invoked live `agy` and `grok` CLIs during unit tests. | Patched provider collection and subprocess calls in `TestHTTPServer` (`31` tests run in `~1.1s`). |
| **APP-H1/H2** | Android Review 1 | Bearer token & Gist key stored in plaintext `SharedPreferences` with `allowBackup="true"`; `targetSdk=35` blocked Tailscale HTTP. | Added `KeystoreSecretStore` (`AES/GCM/NoPadding`), `allowBackup="false"`, `targetSdk=36`, and `network_security_config.xml`. |
| **APP-M1/M3/M4** | Android Review 1 | Pull-to-refresh only called `GET /v1/quota`; `BucketDetailSheet` & `SyncHubSheet` were missing; Glance widgets didn't refresh on sync. | Wired `POST /v1/quota/refresh`, added modal sheets + persistent `TimeTravelScrubberDock`, and triggered `updateAll(context)` on widgets. |
| **SYS-C1** | Plan & Cross-System Review | Collector wrote `"nonce"` in Gist envelope; Android decryptor required `"iv"`. | Unified v1 envelope (`nonce` primary, `iv` alias), bound `capturedAt`, and added shared Python/Kotlin golden test vector. |
| **SYS-C2** | Plan & Cross-System Review | GitHub API returns `401` on unauthenticated secret-gist reads since Sept 2025. | Added Raw Gist URL (`gist.githubusercontent.com`) support + optional Keystore-encrypted GitHub Read Token. |
| **SYS-H3/H4/H5/H6** | Plan & Cross-System Review | Missing `launchd` plist & single-flight lock; expired tokens (`<=0s`) skipped by Smart Prime; fail-open parsers on missing usage fields; non-null `resetsAt`. | Added `launchd` plist + `install-launchd`, `threading.Lock` + background poll loop, `POST /v1/config/active-hours`, fail-closed parsers, nullable `resetsAt`, and 2h staleness check. |
