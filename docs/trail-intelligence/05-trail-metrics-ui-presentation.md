# 05 — Presenting trail metrics in the UI

**Status:** design, informs DAV-144's UI-surfacing scope · not its own Linear ticket

## Where, and why not literally "a badge on every Training-tab row"

The user asked for trail performance metrics "available from the log and in the training tab for each
run." Checked against the real screens before designing anything:

- **Log → Session Detail** (`ui/screens/SessionDetailScreen.kt`, opened from a Log entry's DETAIL
  action) already has real per-run structure: `SummaryCard`, a `ROUTE` card (`RouteMiniMap`, DAV-131's
  Health Connect source), a switchable `PERFORMANCE` chart, `SplitsCard`. **This is the real "per run"
  surface** — a new `TRAIL` `FTCard` slots in here directly.
- **Training tab** (`ui/screens/status/TrainingScreen.kt`) has **no session list at all today** — it's
  aggregate-only (`VO2MAX`, `RUNNING — THIS WEEK`, `STRENGTH`, `DISTANCE TOTALS`), the same rollup shape
  every other tile screen (Fuel/Heart/Labs) uses. There is no existing "one row per run" surface to
  attach a per-run badge to. Given that, "for each run" on this tab means the same thing the Training
  tab's existing cards already mean for running in general: a **weekly rollup**, added to the existing
  `RUNNING — THIS WEEK` card, not a new per-session list. If a real Training session list gets built
  later (out of this milestone's scope), a compact badge is the natural per-row addition then.

## Visual language — reuse, don't invent

Every component below already exists and is reused exactly as-is; nothing new is a departure from the
Futuristic Material contract (`ui/theme/FuturisticMaterialTokens.kt`, DAV-105):

- **`FTCard(title)`** — the section container, same uppercase-Emerald-title glass card every screen uses.
- **`FTMetricValue(DisplayValue)`** — the large headline number (RobotoMono/`Telemetry`, 36sp), for
  Mountain Index as the card's primary figure, matching how `VO2MAX`/`DISTANCE TOTALS` already lead
  with one number.
- **`StatLine(label, value)`** — every secondary figure (KM-effort, VAM, GAP, HR decoupling %).
- **`SubTabRow`** — if a session has multiple climbs worth showing individually, the same chip-row
  `PerformanceChartCard` already uses to switch between HR/PACE/POWER/CAL switches between climbs.
- **`FTStatePill(MetricState)`** — every dimension's state, via the **exact same** private
  `EvalState.toMetricState()` extension already duplicated per-screen-file in `FuelTileScreen.kt`/
  `HeartTileScreen.kt`/`TrainingTileScreen.kt` (`NoData→Unavailable`, `Building→Building`,
  `Stable→Optimal`, `ShiftUp/ShiftDown→Warning`, `Unstable→Critical`). Trail's `DimensionState` already
  carries a plain `EvalState` (doc 03's registry), so this mapping needs no new logic — just one more
  private copy of the same four-line function, matching the established per-file-duplicate convention.
- **`FT.DomainTraining`** (= `FT.Emerald`) stays the accent — trail metrics live under the Training/Log
  surfaces, not a new domain; no new accent color is introduced.

**One new small component**: `GradeBandBar` — a horizontal segmented bar (flat/gentle/moderate/steep
distance or time share, doc 03's grade-band distribution), visually modeled directly on the existing
`RangeBar` (same `FT.GlassFill` track, same rounded-corner treatment, same height) but split into
colored segments instead of one fill fraction. No stacked/segmented bar exists yet in `ui/components/`;
everything else needed already does.

**Grade-band color ramp** (semantic, not a new accent): flat → `FT.TextSecondary` (neutral, not a
signal), gentle → `FT.Emerald`, moderate → `FT.Warning`, steep → `FT.Critical`. This mirrors the
existing severity use of Warning/Critical elsewhere (`LabsTileScreen`'s shift coloring) rather than
inventing a new ramp — steepness reads as increasing physiological demand, the same semantic axis
Warning/Critical already encode.

## The `TRAIL` card (Session Detail)

Gated identically to how the existing `ROUTE` card gates on `healthConnectRecordId != null`: only
rendered when `header.type == "run"` and the session's `details.route_type == "trail"`. Layout, top to
bottom:

1. `FTMetricValue` — Mountain Index (primary number, `m/km` unit) with KM-effort as a `StatLine`
   directly under it (both are the "how hard is this course" headline, Mountain Index leads since it's
   the more universally recognized figure per doc 02).
2. `GradeBandBar` — the session's grade-band distance distribution, one glance at the terrain's shape.
3. Climb/descent list — same visual shape as the existing `SplitsCard`, one row per performance-eligible
   segment (doc 04's ≥100m/≥60s bar): direction-colored label, VAM, GAP. Segments that exist structurally
   but don't clear the performance-eligible bar are folded into the grade-band distribution above, not
   listed individually (matching doc 04's own two-tier design) — nothing shown claims false per-segment
   precision.
4. Durability — pace/VAM degradation and HR decoupling as `StatLine`s, only rendered when doc 04's
   comparability rule actually found a matched early/late pair; otherwise the card simply omits that
   row rather than showing a forced or `N/A` comparison.
5. Each dimension's `FTStatePill` sits next to its own value — `NoData` renders quietly (`Unavailable`
   pill, no numbers, no fabricated placeholder), consistent with every other evaluation card in this app.
6. A dimension whose `InputCompleteness.tier == MINIMAL` (doc 03) gets a small "LIMITED DATA" caption
   under its value — the same visible-not-hidden convention DAV-168's review sheet already established
   for AI confidence (`NEEDS CONFIRMATION`), not a new pattern. `FULL`/`PARTIAL` tiers stay silent —
   confidence chrome only appears when it's actually informative.

## Training tab: `RUNNING — THIS WEEK` extension

Two more `StatLine`s, added only when this week includes at least one trail run: "Trail runs" (count)
and "Elevation gained" (sum of `elevation_gain_m` across this week's trail runs, reusing the existing
`sumDistanceKmSince`-style windowing convention from `domain/Training.kt` rather than a new query
shape). No new card — this stays inside the existing card, matching how STRENGTH's weekly summary
already sits alongside RUNNING rather than spawning its own top-level section for a sub-category.

## What this doc does not cover

- The exact `DailyStateObject`-vs-per-session shape trail metrics publish through (that's DAV-144's own
  job — this doc only says where the *rendered* numbers land and how they're styled).
- Any new Linear ticket — this is folded into DAV-144's already-expanded scope (see its own comment
  thread), not a separate tracked issue.
