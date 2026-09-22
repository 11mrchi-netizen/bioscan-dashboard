# 03 — Trail-running metric registry and formulas

**Linear:** [DAV-128](https://linear.app/biodashboard/issue/DAV-128) · **Version:** 1.0.0 · **Contract only — no implementation here**

## Why this exists

The P9 domain metric contract every trail-metric ticket (DAV-134 through DAV-144) conforms to, playing
the same role [`docs/analysis-layer-2/02-metric-registry.md`](../analysis-layer-2/02-metric-registry.md)
plays for the rest of the app. Reuses that registry's exact conventions rather than inventing new
ones: **kind** taxonomy (raw/derived/modeled/inferred), the **Provenance**/**Confidence**/
**InputCompleteness** shapes already built in `domain/analysis/AnalysisLayer2Contract.kt` (DAV-180),
and the same "raw observations are never overwritten by derived computation" principle — every metric
below is computed fresh from trackpoints/session data each time, never persisted as if it were a
measurement.

Every metric here is **Phase 1 only** (a completed trail run's actually-recorded route) unless marked
otherwise. Phase 2's preview computes only the three metrics marked **shared** — Mountain Index,
KM-effort, and grade distribution — from a planned GPX instead of a recorded route; it never computes
a performance/effort metric, since nothing has happened yet to measure effort on. Canonical formulas
below are the ones chosen in [doc 02](02-trail-metric-conventions.md); see that doc for the comparison
and citations behind each choice.

## Registry

| Canonical name | Kind | Unit | Grain | Formula / inputs | Min data quality | Algorithm & version | Ticket |
|---|---|---|---|---|---|---|---|
| Elevation gain / loss | derived | m | per-session | sum of positive/negative smoothed elevation deltas | ≥2 trackpoints with elevation | `elevation_smoothing` v1 (doc 04) | DAV-134 |
| Mountain Index (= vertical density, aliased) **shared** | derived | m/km | per-session | `elevation_gain_m / distance_km` | `distance_km > 0` | `mountain_index` v1 | DAV-135 |
| KM-effort **shared** | derived | km-equivalent | per-session | `distance_km + elevation_gain_m/100` (FFA/ITRA formula) | `distance_km` present | `km_effort_ffa_itra` v1 | DAV-135 |
| Grade distribution (time/distance/elevation by grade band) **shared** | derived | % or km per band | per-session | segment grade buckets, bands from doc 04 | needs segmentation | `grade_bands` v1 | DAV-136 |
| Climb/descent count, avg/max length, longest climb, gain per climb | derived | count, m, m | per-session | segment boundaries | needs segmentation | `climb_structure` v1 | DAV-136 |
| VAM | modeled | m/h | per climb segment | `elevation_gain_segment / time_segment(h)` | segment meets doc 04's min-duration rule | `vam` v1 | DAV-138 |
| Grade-adjusted pace (GAP) | modeled | min/km | per point/segment | `pace / (EC(grade)/EC(0))`, Minetti et al. 2002 polynomial | grade + speed present | `minetti_2002_gap` v1 | DAV-138 |
| Uphill/downhill efficiency | modeled | denominator TBD by DAV-141 (HR first) | per climb/descent segment | vertical speed ÷ physiological cost | HR (or later power) present | `grade_efficiency`, version set by DAV-141 | DAV-141 |
| Run/hike classification | inferred | % time & distance | per session | speed/grade threshold heuristic | speed present | `run_hike` v1 | DAV-140 |
| Uphill hiking efficiency | modeled | same family as grade efficiency | per hiking segment | vertical speed ÷ HR, hiking-speed segments only | HR present | `hiking_efficiency` v1 | DAV-140 |
| Pace (GAP) degradation | modeled | % or min/km delta | per session | early-vs-late matched-segment GAP delta | ≥2 comparable segments (doc 04) | `durability_pace_decay` v1 | DAV-142 |
| VAM degradation | modeled | % or m/h delta | per session | early-vs-late matched-climb VAM delta | ≥2 comparable climbs | `durability_vam_decay` v1 | DAV-142 |
| HR drift / decoupling | modeled | % | per session | pace:HR (or power:HR) ratio, early vs. late half | continuous HR present | `hr_decoupling` v1 | DAV-142 |
| Terrain technicality | inferred (manual v1) | ordinal tag | per session or segment | user-tagged; never derived from GPX alone | none required — optional | `technicality_manual` v1 | DAV-137 (deferrable) |

## Provenance and confidence

Every metric above publishes through the same shared shapes DAV-180 already built for nutrition
(`domain/analysis/AnalysisLayer2Contract.kt`): `Provenance(origin, algorithm, algorithmVersion)` with
`origin` naming the real route source — `"health_connect_route"` (Phase 1) or `"gpx_drive"`
(Phase 2) — never a generic `"computed"`; `InputCompleteness(present, ideal)` naming real contributing
data (e.g. `ideal = {"trackpoints", "heart_rate"}` for VAM-with-HR-context, `present` dropping
`"heart_rate"` when a session has no HR stream) per DAV-66's own "name real fields, not abstract
categories" rule (see `docs/analysis-layer-2/18-nutrition-daily-state-publisher.md`'s worked resolution
of the same question for nutrition). DAV-144 adds the actual `StateDimension` enum cases (e.g.
`MOUNTAIN_INDEX`, `KM_EFFORT`, `VAM`, `GRADE_ADJUSTED_PACE`, `HR_DECOUPLING`) to the existing shared
enum rather than building a parallel contract.

## Explicit exclusions (per this ticket's own acceptance criteria)

- **No opaque proprietary "Trail Score."** Every number above is a named, formula-backed metric a
  reader can recompute by hand; there is no single blended difficulty/performance score.
- **Strava's GAP and TrainingPeaks' NGP are not reverse-engineered.** The published Minetti et al.
  (2002) polynomial is implemented instead — see doc 02 for why.
- **Cadence** is not in this registry — not stored anywhere in this app yet (same gap
  `docs/analysis-layer-2/02-metric-registry.md` already documents for the rest of the app).

## Versioning

v1.0.0. Adding a metric is a minor version change. Changing a formula already in this table (e.g.
revising the grade-band boundaries or the durability comparison window) is documented in doc 04/this
doc's own changelog and bumps that metric's own `algorithmVersion`, not this registry's version —
matching `docs/analysis-layer-2/05-confidence-propagation.md`'s own versioning convention.
