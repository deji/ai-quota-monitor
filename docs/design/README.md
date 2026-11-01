# UI/UX Design & Locked-In Hybrid Prototype Specification

This directory documents the **Google Stitch** prototype sets and the locked-in **Hybrid Master Design** for **AI Quota Monitor** on the **Pixel 8 Pro**.

---

## 1. Locked-In Design Direction: The Hybrid Master Fusion

Following owner review of the three Stitch prototype sets (`projects/8157070654946262217`), v1 locks in a **Hybrid Fusion** combining the best of **Set 2 (Modern M3 Expressive Bento)** and **Set 3 (Avant-Garde Chronos Kinetic HUD)**:

| UI Component / Surface | Source Set | Native Jetpack Compose Implementation |
| :--- | :--- | :--- |
| **Theme & Surface Palette** | **Modern** (M3 Expressive + Material You) | `dynamicDarkColorScheme` / `dynamicLightColorScheme` on Android 14–17 (`minSdk 34`, `targetSdk 36`) paired with harmonized semantic tokens (Emerald `#5CE196`, Amber `#FFB951`, Coral `#FFB4AB`). |
| **Polarity Pill (`% Left` ↔ `% Used`)** | **Conservative / Modern** | Capsule toggle in Top Bar (`QuotaPolarityToggle`) that flips all gauges, cards, and tables between **Remaining %** and **Used %** with spring animation. |
| **Orca Role Readiness Strip** | **Avant-Garde** (`01-dashboard`) | 3-column top status strip (`OrcaRoleReadinessStrip`) mapping subscriptions to Orca orchestration roles: `PLANNER (Gemini 4 Argon)`, `BUILDER (Flash 3.8 + OpenCode)`, `REVIEWER (Grok 4.7)`. |
| **Hero Bottleneck Bento Card & Radial Arc Gauge** | **Modern** (`01-dashboard`) | `28dp` squircle card (`HeroBottleneckCard`) with custom Compose `Canvas` radial arc (`QuotaRadialArcGauge`), glowing orange **Target Pace Notch** on the rim, reset countdown, and mini bezier sparkline. |
| **2-Column Provider Bento Row (`OpenCode Go` & `Grok Build`)** | **Modern** + **Avant-Garde** | Side-by-side `28dp` Bento cards with radial ring gauges, plus the **Grok OAuth Token Lease Countdown bar** & manual **`Prime Pulse`** trigger button. |
| **Interactive Time-Travel Horizon Scrubber** | **Avant-Garde** (`01-dashboard`) | Bottom dock slider (`HorizonScrubberCard`: `NOW → +1H → +4H → RESET`) backed by `HorizonSimulator` that projects remaining quota across all buckets at current burn velocity. |
| **Burn Velocity & Multi-Stream Horizon Screen** | **Modern** + **Avant-Garde** (`02-detail-history`) | Custom Compose `Canvas` bezier/horizon velocity chart (`BurnVelocityChart`) with diagonal linear pace line, `2x2` metric squircles, all-buckets pace list, and recent orchestration stage checkpoints. |
| **Glance Widgets (`2x2` & `4x2`) & Dual-Transport Hub** | **Modern** + **Avant-Garde** (`03-widgets-settings`) | Native Jetpack Glance `2x2` Compact (`QuotaCompactWidget`) and `4x2` Wide Orca Stage Deck (`QuotaWideWidget`) + reusable `ConnectionSettingsForm` (promoted to top of Widgets & Sync screen and embedded in `SyncHubSheet`), first-run `ConnectCollectorCard` on Dashboard, Tailscale Live (port `8741`) / GitHub Gist AES-256-GCM Relay settings + **Active-Hours Wake Slider (`08:00–22:00`)**. |

---

## 2. Stitch Prototype Assets Inventory (`.stitch/designs/`)

- **Hybrid Master**: [`.stitch/designs/hybrid/01-hybrid-master-dashboard.png`](../../.stitch/designs/hybrid/01-hybrid-master-dashboard.png)
- **Set 1 — Conservative (`assets/6341266814161057284`)**:
  - [`01-dashboard.png`](../../.stitch/designs/conservative/01-dashboard.png) · [`02-detail-history.png`](../../.stitch/designs/conservative/02-detail-history.png) · [`03-widgets-settings.png`](../../.stitch/designs/conservative/03-widgets-settings.png)
- **Set 2 — Modern (`assets/8610081051554797998`)**:
  - [`01-dashboard.png`](../../.stitch/designs/modern/01-dashboard.png) · [`02-detail-history.png`](../../.stitch/designs/modern/02-detail-history.png) · [`03-widgets-settings.png`](../../.stitch/designs/modern/03-widgets-settings.png)
- **Set 3 — Avant-Garde (`assets/5766478320191827137`)**:
  - [`01-dashboard.png`](../../.stitch/designs/avant_garde/01-dashboard.png) · [`02-detail-history.png`](../../.stitch/designs/avant_garde/02-detail-history.png) · [`03-widgets-settings.png`](../../.stitch/designs/avant_garde/03-widgets-settings.png)
