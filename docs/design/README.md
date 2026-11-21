# UI/UX Design & Locked-In Hybrid Prototype Specification

This directory documents the **Google Stitch** prototype sets and the locked-in **Hybrid Master Design** for **AI Quota Monitor** on the **Pixel 8 Pro**.

---

## 1. Locked-In Design Direction: The Hybrid Master Fusion

Following owner review of the three Stitch prototype sets (`projects/8157070654946262217`), v1 locks in a **Hybrid Fusion** combining the best of **Set 2 (Modern M3 Expressive Bento)** and **Set 3 (Avant-Garde Chronos Kinetic HUD)**:

| UI Component / Surface | Source Set | Native Jetpack Compose Implementation |
| :--- | :--- | :--- |
| **Theme & Surface Palette** | **Modern** (M3 Expressive + Material You) | `dynamicDarkColorScheme` / `dynamicLightColorScheme` on Android 14–17 (`minSdk 34`, `targetSdk 36`) paired with harmonized semantic tokens (Emerald `#5CE196`, Amber `#FFB951`, Coral `#FFB4AB`). |
| **Polarity Pill (`% Left` ↔ `% Used`)** | **Conservative / Modern** | Capsule toggle in Top Bar (`QuotaPolarityToggle`) that flips all gauges, cards, and tables between **Remaining %** and **Used %** with spring animation. |
| **360° Segmented Quota Chronograph** | **Bank 3** (`APP-UX8`) | Native Compose `Canvas` dial (`QuotaChronographCard`) with three provider halo arcs (`#38BDF8` AGY, `#A78BFA` OpenCode, `#5CE196` Grok), 36 decorative bezel ticks, and one permanent health-coloured wedge per pool in `resolveWideProviderGroups` order. Polar tap hit-testing isolates a wedge; tapping the centre returns to `All Providers`; the provider legend is always tappable. |
| **Selected Pool Strip** | **Bank 3** (`APP-UX8`) | Fixed-height contextual command card (`SelectedPoolStrip`) showing only the tapped wedge's details: tightest pools + Smart Prime in `All`, Grok's stacked `productBreakdown` + Prime, or a single Gemini/OpenCode context with `★ Pin`; tapping it opens `BucketDetailSheet`. |
| **Wildcard Hybrid Activity Board** | **Round 4** (`APP-UX8`) | `ActivityBoardCard` with `5h | 24h | 7d | 30d` range pills and an LED/Quilt toggle. `5h`/`24h` default to the 10-dot LED Burst Spectrum; `7d`/`30d` default to the GitHub Quilt (5 quantized intensity levels, `↺` reset glyphs, coral wall-hit dots, `Less … More` legend). Filters to the tapped wedge/legend provider and shows an honest empty state. |
| **Orca Role Readiness Strip** | **Avant-Garde** (`01-dashboard`) | Retired from the Dashboard in `APP-UX8`; `OrcaRoleReadinessStrip` remains available but the Quotas tab now leads with the Bank 3 Chronograph + Activity Board. `BUILDER` copy reads `Gemini Pool / OpenCode` (Gemini Pool = Neon, Hydrogen & 3.8). |
| **Hero Bottleneck Bento Card & Radial Arc Gauge** | **Modern** (`01-dashboard`) | Retired from the Dashboard in `APP-UX8`; `HeroBottleneckCard`, `QuotaRadialArcGauge`, and `MiniSparkline` remain available for the factual `BucketDetailSheet` (current quota + recorded history) and other surfaces. |
| **2-Column Provider Bento Row (`OpenCode Go` & `Grok Build`)** | **Modern** + **Avant-Garde** | Retired from the Dashboard in `APP-UX8`; the live pool state is now reached through the Chronograph wedge + Selected Pool Strip, with Grok Smart Prime preserved on both. |
| **Interactive Time-Travel Horizon Scrubber** | **Avant-Garde** (`01-dashboard`) | Removed from the Dashboard in `APP-UX8`; `HorizonScrubberCard`/`HorizonSimulator` remain in source but are no longer mounted, because burn-rate and projection copy was deliberately removed. |
| **Burn Velocity & Multi-Stream Horizon Screen** | **Modern** + **Avant-Garde** (`02-detail-history`) | No longer a bottom-navigation tab (`APP-UX8`); `BurnVelocityChart.kt` stays on disk so `AQM-LINT-006` continues to guard against `?: 100.0` history defaults. |
| **Glance Widgets (`2x2` & `4x2`) & Dual-Transport Hub** | **Modern** + **Avant-Garde** (`03-widgets-settings`) | Native Jetpack Glance `2x2` Compact (`QuotaCompactWidget`) and `4x2` Wide Orca Stage Deck (`QuotaWideWidget`) + reusable `ConnectionSettingsForm` (promoted to top of Widgets & Sync screen and embedded in `SyncHubSheet`), first-run `ConnectCollectorCard` on Dashboard, Tailscale Live (port `8741`) / GitHub Gist AES-256-GCM Relay settings + **Active-Hours Wake Slider (`08:00–22:00`)**. |

---

## 2. Stitch Prototype Assets Inventory (`.stitch/designs/`)

- **Hybrid Master**: [`.stitch/designs/hybrid/01-hybrid-master-dashboard.png`](../../.stitch/designs/hybrid/01-hybrid-master-dashboard.png)
- **Adaptive Launcher Icon Exploration (`projects/8157070654946262217`, screen `3fa498b599d94569b9be4f056c1603bb`)**:
  - [`.stitch/designs/icons/adaptive-icon-proposals.png`](../../.stitch/designs/icons/adaptive-icon-proposals.png) · [`adaptive-icon-proposals.html`](../../.stitch/designs/icons/adaptive-icon-proposals.html)
- **Set 1 — Conservative (`assets/6341266814161057284`)**:
  - [`01-dashboard.png`](../../.stitch/designs/conservative/01-dashboard.png) · [`02-detail-history.png`](../../.stitch/designs/conservative/02-detail-history.png) · [`03-widgets-settings.png`](../../.stitch/designs/conservative/03-widgets-settings.png)
- **Set 2 — Modern (`assets/8610081051554797998`)**:
  - [`01-dashboard.png`](../../.stitch/designs/modern/01-dashboard.png) · [`02-detail-history.png`](../../.stitch/designs/modern/02-detail-history.png) · [`03-widgets-settings.png`](../../.stitch/designs/modern/03-widgets-settings.png)
- **Set 3 — Avant-Garde (`assets/5766478320191827137`)**:
  - [`01-dashboard.png`](../../.stitch/designs/avant_garde/01-dashboard.png) · [`02-detail-history.png`](../../.stitch/designs/avant_garde/02-detail-history.png) · [`03-widgets-settings.png`](../../.stitch/designs/avant_garde/03-widgets-settings.png)
