# Provider Quota Specifications & Verified Wire Shapes

Distilled from `~/exchange/scratch/ai-quota-monitor/` (`quota.py`, `README.md`, `wendy-model-quota.md`) and verified against a live pre-flight gate on macOS.
All personal tokens and machine-specific usernames are omitted.

---

## 1. Live Gate Findings (Critical Parser Invariants)

Running `quota.py` on the live host confirmed three wire-level details that every parser in `collector/` and `app/` must handle:

1. **`opencode go` double-nests `usage`**:
   - The HTTP response JSON from `GET https://opencode.ai/zen/go/v1/usage` is `{ "usage": { "rolling": {...}, "weekly": {...}, "monthly": {...} } }`.
   - When `quota.py` wraps that in `{"http": 200, "usage": body}`, the path is `opencode_go.usage.usage.{rolling,weekly,monthly}` (or `body.usage.{rolling,weekly,monthly}` when calling the upstream API directly). Parsers must accept both `usage.rolling` and `usage.usage.rolling`.
2. **`grok` wraps monetary/credit caps in `{"val": <number>}` objects**:
   - `billing.config.onDemandCap` is `{"val": 0}`, `onDemandUsed` is `{"val": 0}`, and `prepaidBalance` is `{"val": 0}` (not bare numbers).
   - `billing.config.currentPeriod.type` is `"USAGE_PERIOD_TYPE_WEEKLY"`.
   - `billing.config.productUsage` is an array: `[{"product": "GrokBuild", "usagePercent": 3.0}]`.
3. **Polarity mismatch across providers**:
   - `agy` reports **`remaining_fraction` LEFT** (`0.0` to `1.0`).
   - `opencode go` (`percent`) and `grok` (`creditUsagePercent` / `usagePercent`) report **percentage USED** (`0.0` to `100.0`).
   - The collector normalizes every bucket to carry **both** `remainingPercent` (`0..100`) and `usedPercent` (`0..100`).

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
  - `User-Agent: opencode/2.2.2` (Required for Cloudflare)
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

- **Request**: `GET https://cli-chat-proxy.grok.com/v1/billing?format=credits` (fallback without `?format=credits`)
- **Required Headers**:
  - `Authorization: Bearer <key>`
  - `X-XAI-Token-Auth: xai-grok-cli`
  - `x-userid: <user_id>`
  - `User-Agent: opencode/2.2.2`
  - `Accept: application/json`
- **Credential Source**: `~/.grok/auth.json`, entry matching key prefix `https://auth.x.ai` (`key`, `user_id`, `expires_at`).
- **Smart Prime Policy**:
  - When `expires_at - now < 15 minutes` **AND** (current local time is within configured active hours `08:00–22:00` **OR** an explicit manual Prime Pulse / Refresh is requested), run `/opt/homebrew/bin/grok -p "reply with ok"` (timeout 120s) and reload `~/.grok/auth.json`.
- **Verified Upstream Response**:
  ```json
  {
    "config": {
      "currentPeriod": {
        "type": "USAGE_PERIOD_TYPE_WEEKLY",
        "start": "2026-10-29T16:45:10.524836+00:00",
        "end": "2026-11-05T16:45:10.524836+00:00"
      },
      "creditUsagePercent": 3.0,
      "onDemandCap": { "val": 0 },
      "onDemandUsed": { "val": 0 },
      "productUsage": [
        { "product": "GrokBuild", "usagePercent": 3.0 }
      ],
      "isUnifiedBillingUser": true,
      "prepaidBalance": { "val": 0 },
      "topUpMethod": "TOP_UP_METHOD_SAVED_PAYMENT_METHOD",
      "billingPeriodStart": "2026-10-29T16:45:10.524836+00:00",
      "billingPeriodEnd": "2026-11-05T16:45:10.524836+00:00"
    }
  }
  ```

---

## 3. Normalized Quota Snapshot Schema (`v1`)

Both the Tailscale HTTP daemon (`GET /v1/quota`, `POST /v1/quota/refresh`, `POST /v1/grok/prime`) and the AES-256-GCM Encrypted GitHub Gist Relay emit this canonical JSON payload:

```json
{
  "schemaVersion": 1,
  "capturedAt": "2026-11-01T16:27:47Z",
  "host": "mac-host",
  "smartPrime": {
    "policy": "active_hours_or_manual",
    "activeHoursStart": "08:00",
    "activeHoursEnd": "22:00",
    "grokTokenExpiresAt": "2026-11-01T22:18:41Z",
    "grokTokenRemainingSeconds": 21054,
    "lastPrimedAt": null
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
  "history": [
    {
      "capturedAt": "2026-11-01T15:04:00Z",
      "bucketRemaining": {
        "gemini-argon-wtu-weekly": 55.0,
        "gemini-5h": 100.0,
        "gemini-weekly": 100.0,
        "opencode-rolling": 100.0,
        "opencode-weekly": 98.0,
        "opencode-monthly": 99.0,
        "grok-weekly-credits": 97.0
      }
    }
  ]
}
```
