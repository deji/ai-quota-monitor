# Design System & Three-Tier Prototype Matrix — AI Quota Monitor

## 1. Core Foundation (Shared Across All Three Sets)
- **Target Device**: Google **Pixel 8 Pro** (`1344 × 2992` LTPO OLED, 120Hz, 20:9 aspect ratio, edge-to-edge system bars).
- **Theme Engine**: Native Android 16/17 **Material You Dynamic Color** (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) paired with harmonized semantic quota & pace tokens.
- **Zero WebViews**: Every card, progress bar, radial gauge, orbital ring, and sparkline is rendered natively with Jetpack Compose and `Canvas`.
- **Mandatory Tabular Numerals**: All percentages (`55%`), pace deltas (`-51.8%`), and reset timers (`4h 22m`) use `fontFeatureSettings = "tnum"`.

---

## 2. The Three Prototype Directions in Google Stitch

Project ID: `projects/8157070654946262217`

### Set 1: Conservative — "Pixel Settings Tonal Utility"
- **Stitch Design System**: `assets/6341266814161057284` (`FIDELITY`, `ROUND_TWELVE`, seed `#4FD8EB`)
- **Inspiration**: Native Android 16 Settings, Digital Wellbeing, and Pixel Battery Usage.
- **Visual Language**:
  - Predictable single-column vertical list hierarchy with structured tonal cards (`surfaceContainerLow` `#171D1E`, `12dp`–`16dp` corners).
  - Full-width Material 3 `SingleChoiceSegmentedButtonRow` at the top to toggle **`% Remaining`** vs **`% Used`**.
  - Horizontal linear progress bars (`LinearProgressIndicator` / custom `Canvas` bar) with a crisp vertical **Linear Window Pace Tick** showing where consumption *should* be at this point in the window.
  - Standard bottom `NavigationBar` (`Quotas`, `Pace & History`, `Relay & Widgets`).
- **Best For**: Maximum familiarity, zero learning curve, high-density scannability that feels built into the Pixel OS.

---

### Set 2: Modern — "Material 3 Expressive Bento & Radial Arcs"
- **Stitch Design System**: `assets/8610081051554797998` (`EXPRESSIVE`, `ROUND_FULL`, seed `#80D4DC`, secondary `#B4CAD6`, tertiary `#D8BDE3`)
- **Inspiration**: Flagship Android 16/17 Material 3 Expressive redesigns (Pixel Weather, Fitbit M3 Expressive).
- **Visual Language**:
  - Asymmetric **Bento Grid** layout with generous squircle (`28dp`) and pill (`CircleShape`) containers.
  - **Hero Bottleneck Card**: Spotlights the tightest subscription bucket (`Gemini 4 Argon Weekly — 55% Left`) with a sweeping Compose `Canvas` radial arc gauge, glowing pace needle on the outer rim, and inline bezier sparkline.
  - **2-Column Provider Bento Row**: Side-by-side radial ring gauges for `OpenCode Go (98% Weekly)` and `Grok Build (97% Credits · Smart Primed)`.
  - **Floating Capsule Dock**: Elevated pill toolbar at the bottom with spring-physics transitions and a prominent tonal Refresh FAB.
- **Best For**: A modern, tactile, flagship Android showcase that balances expressive personality with instant visual hierarchy.

---

### Set 3: Avant-Garde — "Chronos Orbital Kinetic HUD"
- **Stitch Design System**: `assets/5766478320191827137` (`VIBRANT`, `ROUND_EIGHT`, seed `#7AE582`, warning `#FFB951`, tertiary `#80D4DC`, neutral `#0B0F12`, `SPACE_GROTESK` + `GEIST` + `SPACE_MONO`)
- **Inspiration**: Teenage Engineering, Nothing OS, and kinetic aerospace chronometers adapted to Material You Dynamic Color.
- **Visual Language**:
  - Organizes quotas by **Time-Horizon Rings & Orca Agent Roles** rather than static vendor lists:
    - Top **Orca Stage Matrix** pills: `PLANNER [ARGON 55%]`, `BUILDER [FLASH 100% · OC 98%]`, `REVIEWER [GROK 97% · PRIMED]`.
    - Central **Concentric Orbital Chronometer**: Inner ring = `5-Hour Rolling Horizon`, Middle ring = `7-Day Tactical Horizon`, Outer ring = `30-Day Strategic Horizon`, with pace vector ticks on each orbit.
  - **Interactive Time-Travel Horizon Scrubber**: A tactile bottom timeline slider (`NOW → +1H → +4H → RESET`) that lets you scrub forward in time to see projected remaining quota across all buckets at current burn velocity.
- **Best For**: A bold, signature instrument panel built specifically for multi-agent Orca orchestration power users.
