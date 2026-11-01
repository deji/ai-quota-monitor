# Provider Quota Specifications, Envelope Schema & Verified Wire Shapes

Distilled from `~/exchange/scratch/ai-quota-monitor/` (`quota.py`, `README.md`, `wendy-model-quota.md`) and verified against live pre-flight gates on macOS.
All personal tokens and machine-specific usernames are omitted.

---

## 1. Live Gate Findings (Critical Parser Invariants)

Running `quota.py` and `python3 -m collector collect --json` on the live host confirmed four wire-level invariants that every parser in `collector/` and `app/` must enforce:

1. **`opencode go` double-nests `usage`**:
   - The HTTP response JSON from `GET https://opencode.ai/zen/go/v1/usage` is `{ "usage": { "rolling": {...}, "weekly": {...}, "monthly": {...} } }`.
   - When wrapped in `{"http": 200, "usage": body}`, the path is `opencode_go.usage.usage.{rolling,weekly,monthly}`. Parsers must accept both `usage.{rolling,weekly,monthly}` and `usage.usage.{rolling,weekly,monthly}`.
   - **Fail-closed rule**: If a window object is missing a numeric `percent` field (or has non-`"ok"` status without valid `percent`), omit that bucket rather than defaulting to `0% used`.
2. **`grok` wraps monetary/credit caps in `{"val": <number>}` objects**:
   - `billing.config.onDemandCap` is `{"val": 0}`, `onDemandUsed` is `{"val": 0}`, and `prepaidBalance` is `{"val": 0}` (or bare numbers).
   - `billing.config.currentPeriod.type` is `"USAGE_PERIOD_TYPE_WEEKLY"`.
   - `billing.config.productUsage` is an array: `[{"product": "GrokBuild", "usagePercent": 5.0}]`.
   - **Fail-closed rule**: If an HTTP 200 response contains neither a numeric `creditUsagePercent` nor a numeric `productUsage[].usagePercent`, mark the provider `status = "parse_error"` with an empty bucket list (never report a phantom `100% remaining`). When both are present, take `max(creditUsagePercent, productUsage[GrokBuild].usagePercent)` so a `0` in one field cannot mask active usage in the other.
3. **Polarity normalization & clamping**:
   - `agy` reports **`remaining_fraction` LEFT** (`0.0` to `1.0`, clamped to `[0.0, 1.0]`; buckets missing `remaining_fraction` are skipped).
   - `opencode go` (`percent`) and `grok` (`creditUsagePercent` / `usagePercent`) report **percentage USED** (`0.0` to `100.0`, clamped to `[0.0, 100.0]`).
   - Every normalized bucket carries **both** `remainingPercent` (`0..100`) and `usedPercent` (`0..100`), and `resetsAt` is nullable (`String?`).
4. **Orca Role Readiness Aggregation**:
   - `PLANNER`: tracks `gemini-argon-wtu-weekly`.
   - `BUILDER`: tracks `min(gemini-5h, opencode-rolling)` (setting `primaryBucketId` to whichever of the two has lower `remainingPercent`) so an exhausted OpenCode Go rolling window or Gemini 5h window immediately reflects on the Builder pill.
   - `REVIEWER`: tracks `grok-weekly-credits` alongside the Smart Prime OAuth lease state.

---

## 2. Upstream Provider Specs

### 2.1 `agy` (Antigravity — Gemini & 3P Models) — Official CLI

- **Command**:
  ```bash
  AGY_CLI_DISABLE_AUTO_UPDATE=true /opt/homebrew/bin/agy -p "/usage" --output-format json --print-timeout 20s
  ```
- **Raw CLI Output Path**: `command.data.groups[].buckets[]`
- **Verified Buckets**:
  | Group (`name`) | Bucket (`id`) | Window Duration | Orca Stage Role |
  | :--- | :--- | :--- | :--- |
  | `Gemini 4 Argon` | `gemini-argon-wtu-weekly` | `168h` (7 days) | **PLANNER** (`gemini-4-argon-high`) |
  | `Gemini Models` | `gemini-5h` | `5h` (rolling) | **BUILDER** (`gemini-3.8-flash-high`) |
  | `Gemini Models` | `gemini-weekly` | `168h` (7 days) | **BUILDER** (`gemini-3.8-flash-high`) |
  | `Claude and GPT models` | `3p-5h` | `5h` (rolling) | Standby / 3P |
  | `Claude and GPT models` | `3p-weekly` | `168h` (7 days) | Standby / 3P |

---

### 2.2 `opencode go` (OpenCode Go Plan — DeepSeek 4.1 Flash) — Undocumented HTTP

- **Request**: `GET https://opencode.ai/zen/go/v1/usage`
- **Required Headers**:
  - `Authorization: Bearer <token>`
  - `User-Agent: opencode/2.2.2` (configurable via `OPENCODE_USER_AGENT`)
  - `Accept: application/json`
- **Credential Source**: `~/.local/share/opencode/opencode.db` (`mode=ro`), table `credential`, column `value` where `integration_id IN ('opencode-go', 'opencode') AND coalesce(active, 1) = 1`. JSON value contains `key`, `apiKey`, or `access` (fallback: `$OPENCODE_API_KEY`).
- **Verified Upstream Response**:
  ```json
  {
    "usage": {
      "rolling": { "status": "ok", "percent": 0, "resetsAt": "2026-11-01T21:27:47.468Z" },
      "weekly":  { "status": "ok", "percent": 2, "resetsAt": "2026-11-02T00:00:00.000Z" },
      "monthly": { "status": "ok", "percent": 1, "resetsAt": "2026-11-29T08:26:02.000Z" }
    }
  }
  ```

---

### 2.3 `grok` (Grok Build CLI — Grok 4.7) — Undocumented HTTP + Smart Prime

- **Request**: `GET https://cli-chat-proxy.grok.com/v1/billing?format=credits` (fallback to `GET https://cli-chat-proxy.grok.com/v1/billing` on HTTP error, `URLError`, timeout, or empty/unparseable JSON body)
- **Required Headers**:
  - `Authorization: Bearer <key>`
  - `X-XAI-Token-Auth: xai-grok-cli`
  - `x-userid: <user_id>`
  - `User-Agent: opencode/2.2.2`
  - `Accept: application/json`
- **Credential Source**: `~/.grok/auth.json`, entry matching key prefix `https://auth.x.ai` (`key`, `user_id`, `expires_at`).
- **Unified Smart Prime State Machine**:
  - Let `remaining_seconds = expires_at - now`.
  - **Expired or Near Expiry (`remaining_seconds < 900`)**:
    - **Background Poll**: Primes (`grok -p "reply with ok"`) if and only if current local time is within `[activeHoursStart, activeHoursEnd]` (`08:00–22:00` default), setting `primed = true`, `reason = "primed_successfully"`. Outside active hours, `primed = false` with `reason = "near_expiry_outside_active_hours"` (if `0 < remaining_seconds < 900`) or `reason = "token_expired_outside_active_hours"` (if `remaining_seconds <= 0`).
    - **Manual Pull-to-Refresh (`POST /v1/quota/refresh`, `manual_refresh=True`)**: Primes immediately regardless of active hours (`primed = true`, `reason = "primed_successfully"`).
  - **Healthy (`remaining_seconds >= 900`)**:
    - **Background Poll or Pull-to-Refresh**: Does not prime (`primed = false`, `reason = "token_healthy"`).
    - **Explicit Prime Pulse (`POST /v1/grok/prime`, `force=True`)**: Primes immediately (`primed = true`, `reason = "primed_successfully"`), reloads `~/.grok/auth.json`, re-collects all providers, and returns the full canonical `QuotaSnapshot`.
  - **Active-Hours Configuration Sync (`POST /v1/config/active-hours`)**:
    - Accepts `{"activeHoursStart": "HH:MM", "activeHoursEnd": "HH:MM"}`, validates both timestamps, atomically updates `config.json` (`0600`), and returns the updated `QuotaSnapshot`.

---

## 3. Normalized Quota Snapshot Schema (`v1`)

All collector endpoints (`GET /v1/quota`, `POST /v1/quota/refresh`, `POST /v1/grok/prime`, `POST /v1/config/active-hours`) and the decrypted Gist Relay payload emit this canonical JSON document (with `host` scrubbed to `"collector"`):

```json
{
  "schemaVersion": 1,
  "capturedAt": "2026-11-01T16:27:47Z",
  "host": "collector",
  "smartPrime": {
    "policy": "active_hours_or_manual",
    "activeHoursStart": "08:00",
    "activeHoursEnd": "22:00",
    "grokTokenExpiresAt": "2026-11-01T22:18:41Z",
    "grokTokenRemainingSeconds": 21054,
    "lastPrimedAt": null,
    "primed": false,
    "reason": "token_healthy"
  },
  "orcaRoles": [
    {
      "role": "PLANNER",
      "label": "Gemini 4 Argon",
      "primaryBucketId": "gemini-argon-wtu-weekly",
      "remainingPercent": 35.0,
      "status": "healthy"
    },
    {
      "role": "BUILDER",
      "label": "Flash 3.8 / OpenCode",
      "primaryBucketId": "gemini-5h",
      "remainingPercent": 99.0,
      "status": "healthy"
    },
    {
      "role": "REVIEWER",
      "label": "Grok 4.7 Build",
      "primaryBucketId": "grok-weekly-credits",
      "remainingPercent": 97.0,
      "status": "primed"
    }
  ],
  "providers": [
    {
      "id": "agy",
      "displayName": "Antigravity (CLI)",
      "subtitle": "Gemini 4 Argon & 3.8 Flash",
      "status": "ok",
      "buckets": [
        {
          "id": "gemini-argon-wtu-weekly",
          "group": "Gemini 4 Argon",
          "label": "Gemini 4 Argon Weekly",
          "windowType": "weekly",
          "windowDurationHours": 168,
          "remainingPercent": 35.0,
          "usedPercent": 65.0,
          "resetsAt": "2026-11-01T19:39:25Z",
          "elapsedPercent": 98.1,
          "paceDeltaPercent": -33.1,
          "burnRatePerHour": 0.39,
          "projectedRemainingAtReset": 33.8,
          "status": "healthy"
        }
      ]
    }
  ],
  "history": []
}
```

---

## 4. Encrypted Cloud Relay Envelope Schema (`v1`) & Shared Test Vector

To guarantee 100% interoperability between `collector/crypto_relay.py` (Python) and `GistEnvelopeDecryptor.kt` (Android/Kotlin):

### 4.1 Canonical Envelope Format (`quota_snapshot.enc.json`)

```json
{
  "v": 1,
  "alg": "AES-256-GCM",
  "nonce": "<Base64-encoded 12-byte (96-bit) random IV>",
  "ciphertext": "<Base64-encoded AES-256-GCM ciphertext>",
  "tag": "<Base64-encoded 16-byte (128-bit) GCM authentication tag>",
  "capturedAt": "2026-11-01T16:27:47Z"
}
```

Rules enforced by both producer and consumer:
1. **`nonce` / `iv` compatibility**: The producer always writes `"nonce"` (Base64, 12 bytes). The Android decryptor accepts `"nonce"` as primary and `"iv"` as a legacy alias, requiring the decoded IV to be exactly 12 bytes (`96 bits`).
2. **`tag` verification**: 16-byte (`128-bit`) GCM authentication tag verified in constant time (`hmac.compare_digest` in pure-Python fallback, `javax.crypto.Cipher` `AES/GCM/NoPadding` on Android/JVM).
3. **Timestamp binding & freshness**: If the outer envelope includes a non-empty `capturedAt` and the decrypted JSON object also includes `capturedAt`, they must match (preventing an attacker or stale cache from re-labeling an old ciphertext with a newer outer timestamp). Snapshots whose `capturedAt` is more than `7200` seconds (2 hours) old are marked stale (`isStale = true`).
4. **Cross-Language Golden Test Vector**:
   - **Key (32 bytes hex)**: `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f`
   - **Nonce (12 bytes hex)**: `a0a1a2a3a4a5a6a7a8a9aaab` (`oKGio6Slpqeoqaqr` in Base64)
   - Both `collector/tests/test_collector.py` and `app/src/test/java/com/aiquotamonitor/domain/GistEnvelopeDecryptorTest.kt` must verify encryption/decryption of the exact same golden envelope!

---

## 5. Security & Network Isolation

- **OS-level `network_security_config.xml`**: Android's network security configuration permits cleartext HTTP in its `<base-config>` to enable communication with the local/Tailscale companion daemon, while strictly prohibiting cleartext (`cleartextTrafficPermitted="false"`) for GitHub domains (`github.com`, `api.github.com`, `githubusercontent.com`, `gist.githubusercontent.com`).
- **Client-level CIDR Enforcement**: Because Android's XML network security configuration operates on domain names and does not evaluate IP CIDR blocks, `TailscaleApiClient` enforces strict client-level IP validation. Cleartext `http://` requests are permitted only for private, Tailscale CGNAT, and loopback ranges:
  - `100.64.0.0/10` (Tailscale CGNAT)
  - `127.0.0.1` / `localhost` (Loopback)
  - `10.0.0.0/8` (RFC 1918 Private)
  - `172.16.0.0/12` (RFC 1918 Private)
  - `192.168.0.0/16` (RFC 1918 Private)
  - `*.ts.net` (Tailscale MagicDNS)
  Any attempt to send cleartext `http://` traffic to a public IP or unapproved host is rejected with `IllegalArgumentException` on the client before socket transmission, safeguarding bearer tokens.
