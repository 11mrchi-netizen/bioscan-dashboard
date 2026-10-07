# Canonical contracts audit — 29/9 User Profile & Training milestone

Audit for [DAV-281](https://linear.app/biodashboard/issue/DAV-281), written before any of the
milestone's other issues (DAV-282–296) so they extend existing contracts rather than duplicate
them, per the milestone's explicit constraint: no new parallel scoring or evaluation system.

## 1. Evaluation layer (within-person)

`domain/*Evaluation.kt` (`TrainingLoadEvaluation.kt`, `HrvRhrEvaluation.kt`, `SleepEvaluation.kt`,
`BloodworkEvaluation.kt`, …) resolve to `EvalState` (`domain/EvalState.kt`):
`NoData / Building / Stable / ShiftUp / ShiftDown / Unstable`, paired with `Confidence(have, need)`.
Strictly within-person — never compared against a population norm. `EvalState.toMetricState()`
bridges to `domain/MetricPresentation.kt`'s `MetricState` for UI.

## 2. Comparison / benchmark layer (the one true percentile pipeline)

`domain/comparison/` + `domain/analysis/AnalysisLayer2Contract.kt`:

- `ComparisonResult` — the master shape (metric, rawValue, normalizedValue, referenceValue, delta,
  standardizedDelta, percentile, rank, directionality, referenceIdentity/Version, confidence,
  breadth, provenance, bandLabel).
- `Directionality` (HIGHER_BETTER / LOWER_BETTER / OPTIMAL_RANGE / TARGET_VALUE / NON_DIRECTIONAL),
  `ComparisonType` (POPULATION / COHORT / PERSONAL_HISTORY / CONTEXTUAL / EFFORT_EFFICIENCY).
- `personalBaseline()` / `comparePersonal()` / `personalBest(baseline, directionality)`
  (`PersonalBaseline.kt`, `PersonalComparison.kt`) — rolling mean/median/stdDev/quantiles, gated at
  5 observations, stable at 14. `personalBest()` is the *only* existing "personal best" concept:
  stateless, directionality-aware min/max, no persistence.
- `comparePopulation()` (`PopulationComparison.kt`) resolves a versioned `BenchmarkArtifact`
  (Supabase `benchmark_artifacts`, 1 row today) and interpolates a percentile.
- `percentileBand(percentile)` → `PercentileBand` (`ComparisonBands.kt`) — explicitly never a
  collapsed cross-dimension score.

**Rule for this milestone**: DAV-292's domain levels are pure functions *over* these outputs
(`ComparisonResult` → label), never a new comparison mechanism.

## 3. Band-from-number precedent

`tsbBandLabel()` (`TrainingLoadEvaluation.kt`) and `itraCategory()` (`domain/trail/ItraClassification.kt`)
are the existing pattern for "map a number to a descriptive label" — plain functions, not a new
`StateDimension` case (a categorical band isn't a state-dimension scalar). DAV-292's per-domain
levels follow this same shape.

## 4. Activity classification — genuine gap, now filled

Before this milestone, `exercise_sessions.type` was a raw string with no enum, compared ad hoc in
`Training.kt`, `TrainingRepository.kt`, `MapScreen.kt`, `SessionDetailScreen.kt`. Health Connect's
own closed vocabulary (`healthconnect/HealthConnectExerciseTypes.kt`) is
`run/walk/hike/ride/swim/strength/yoga/other`; `details.routeType` (`road/trail/mixed/track`,
`AddEntrySheet.kt`'s `ROUTE_TYPE_OPTIONS`) is the only trail signal, and it's user-confirmed, not
inferred (see `suspectedTrailReason()` — detection-only, never auto-applied).

DAV-282 adds `domain/SportType.kt`, a pure classifier over exactly these two existing fields —
consolidation, not new data.

## 5. Strength math already implemented (DAV-293 assembles, doesn't invent)

`domain/StrengthLoad.kt`: `volumeLoad`, `sessionVolumeLoad`, `estimatedOneRepMax` (Epley, gated
reps ≤ 12), `bestEstimatedOneRepMax`, `relativeIntensityPercent`, `sessionAverageRpe/Rir`,
`sessionRegionalLoad`. `domain/RegionalLoad.kt`: `BodyRegion` (17 values), `MovementPattern` (10
values), `regionalLoadVector`. No session-level aggregate struct exists yet — DAV-293 is a data
class + mapper composing these, not new formulas.

## 6. Training-block precedent

`domain/TrainingCycle.kt` + Supabase `training_cycles` (0 rows): `start_date`, `end_date`, `focus`
(jsonb `FocusEntry` list), `undulation`, `notes`. Closest existing analog to "training block" — has
no goal/target/progress columns. **DAV-291 extends this table with nullable
`goal_metric`/`starting_value`/`target_value` columns rather than creating a parallel table** — low
risk since it's currently empty.

## 7. Nutrition, settings, HRV/RHR gaps

- No goals concept anywhere (`domain/comparison/MetricDirectionality.kt`: "no goal weight is stored
  anywhere in this app"). `RangeKind.TargetRange` (`domain/MetricPresentation.kt`) exists but is
  unused — the natural range-kind once DAV-286 adds goals.
- No Supabase-backed settings table; `data/MapSettingsStore.kt` (local `SharedPreferences`) is the
  pattern to copy for DAV-286's nutrition goals rather than standing up a new table.
- `PerformanceTimeframe` / `RunningPeriod` (`domain/Training.kt`) + generic `SegmentedToggle<T>`
  (`ui/components/SegmentedToggle.kt`) is the timeframe-toggle pattern DAV-285 reuses.
  `PeriodToggle`/`TotalsPeriod` is a separate pairing reserved for Nutrition — not to be reused for
  HRV/RHR (explicit comment in `SegmentedToggle.kt`).

## 8. Efficiency Factor (DAV-295) — already computed, not new math

EF is computed server-side in `zepp-extract`'s decoder from per-second altitude/speed/HR (DAV-272)
and stored on `zepp_workout_detail.decoded.summary.efficiencyFactor`. This repo only reads it
(`SessionDetailRepository.kt`), trends it (`TrainingRepository.kt`'s `efficiencyPoints`), and rolls
it up (`efficiencyRollingMedian28`, `domain/Training.kt`, gated at `EF_MIN_RUNS_28D = 6`, aerobic-only
per DAV-40). DAV-295's job is eligibility documentation (this section) plus promoting EF to sit
alongside VO2max/threshold in the Training tab's switchable metric card instead of only in its own
"RUN EFFICIENCY" section.

## 9. No existing achievement/record/PR concept

Nothing beyond `personalBest()` (§2) — no `Achievement`, `Record`, or `Milestone` type, no
persistence, no date/notification concept. DAV-290 is genuinely new; it reuses `personalBest()`'s
directionality-aware selection as the "is this a new record" predicate.

## 10. Map tab replacement (DAV-289/296) — dependencies and migration risk

`ui/nav/TopLevelTab.kt` + `ui/nav/FieldTerminalNavHost.kt` own the tab routing; `MapScreen.kt` +
`MapRepository`/`PlannedRouteRepository`/`PeopleRepository`/`WeatherRepository`/`GeocodingRepository`
back the current Map tab. The Status→Map `focusEventId` deep link (DAV-69) couples `BodyConsole`
to the Map route and is dropped when the tab is replaced — a real, minor capability loss, flagged
here rather than silently absorbed. Map's code is **unmounted from navigation, not deleted** — no
issue in this milestone asks for its removal, and it may resurface (e.g. from Session Detail's
route card) later.

## Summary: extend vs. new

| Concept | Decision |
|---|---|
| Domain levels (DAV-292) | Extend — pure functions over the existing comparison layer |
| Training block (DAV-291) | Extend `training_cycles` (additive columns) |
| Achievement/record (DAV-290) | New Kotlin model only — no table this milestone (archive milestone's problem) |
| Strength session (DAV-293) | New data class, assembled from existing `StrengthLoad.kt` math |
| Conditioning session (DAV-294) | New — no existing concept, but reuses `exercise_sessions.details` jsonb |
| Sport taxonomy (DAV-282) | New — first enum/classifier over existing raw fields |
| Nutrition goals (DAV-286) | New — local-store pattern copied from `MapSettingsStore` |
| HRV/RHR timeframe (DAV-285) | Extend — reuses `SegmentedToggle<T>` pattern |

No new parallel scoring or evaluation system is introduced anywhere in this milestone.
