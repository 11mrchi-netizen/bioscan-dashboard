# Analysis hub: plan

**Date:** 2026-10-27 · **Branch:** `feat/ft-redesign` · **Structure:** [IA.md](IA.md) v2.0

The console becomes the hub for in-depth analysis: data integrity establishes trust, the longitudinal engine makes the
data accessible, the decision layer turns evidence into recorded decisions. Android keeps capture and record correction.

## Findings that shape the plan

1. **Data volume is small for daily tables.** About 490 days of history: a daily metric over all time is about 700 rows,
   so week and month periods of daily tables are aggregated in the client; no summary tables for them. The heavy data is
   minute heart rate (`wearable_daily.hr_timeseries`, about 50 kB a day; the `wearable_hr_daily_range` view takes about
   1.4 s), `location_fixes`, phone app usage, the auto-detected walks in `exercise_sessions` (about 4,600) and Zepp workout
   detail. Server-side summaries are for those only, and only where a benchmark shows the need.
2. **The metric registry has no query contract.** `supabase/functions/_shared/metric-registry/metric-registry.json`
   (60 metrics) carries methodology and limitations, not table, column, time field, unit, permitted aggregations or
   default grain. The console keeps its own 9-entry `METRICS` map (`console/src/lib/schema.ts`).
3. **Time conventions differ by column.** Checked against the live data on 2026-10-27: calendar `date` columns are the
   local day; session times are local wall time labelled UTC (`zepp-extract` `toLocalLabeledUtcIso`, Health Connect);
   sleep `bedtime`/`wake_time` are true UTC instants with `tz_offset_min` (+480 Taipei on 426 nights, +60 on 47 nights
   between 2025-11 and 2026-04, null on 829 older nights); meals were backfilled as true UTC at templated times
   (00:00, 05:00, 11:30 UTC) before 2026-09-17 and are local wall time since. Taking the first ten characters puts the
   backfilled meals on the right day only by luck and their hour of day 8 h early. The contract now declares the time
   kind per series, with dated exceptions.
4. **Freshness is only partly observable.** `collector_heartbeats` covers location and phone usage. Zepp and Health
   Connect arrivals can be inferred only from `daily_field_provenance.written_at` (from 2026-10-12) and row `created_at`;
   manual logs have no collector signal (no alcohol row ≠ no alcohol). The quality state is often honestly *unknown*.
5. **The documented quality checks are not running.** `docs/analysis-layer-2/11-data-quality-checks.md` (duplicates,
   impossible ranges) has no live implementation besides the session-duplicate folding SQL.
6. **Nothing records a decision.** `evidence_ledger`, `registered_questions`, `experiments` and `interventions` hold
   evidence and protocols, not a decision, its rationale or a review condition.
7. **Evidence is not one model.** `registered_questions` allows six exposures and three morning outcomes; fueling,
   intervention response and interrupted time series live in their own engines. The decision layer needs a common
   evidence-item interface across them.

## Owner decisions (2026-10-27)

| Question | Decision |
|---|---|
| Remove the six domain dashboards outright | Yes, removed in Phase 0 |
| Sync heartbeats for Health Connect and Zepp, so freshness is known rather than unknown | Yes: widen `collector_heartbeats.stream`, one row per sync run (Phase C) |
| Append-only `decisions` table that snapshots its evidence | Yes (Phase D) |
| Corrected discovery screen | Yes, built before the decision layer (Phase C2) |

## Phases

| Phase | Deliverable | Gate | Status |
|---|---|---|---|
| 0. Clear the ground | Remove Overview, Timeline, Training, Recovery, Nutrition, Comparison and the pre-Vite root `console.html`; five-workspace navigation (Integrity, Evidence, Explore, Data, Models) with v1 routes redirected; IA v2.0 | Build, lint and console tests pass | Done |
| A. Contracts | Query fields in the metric registry and the console map generated from it; typed aggregation semantics (sum, mean, last, min/max, each with sample count); the local-time contract; the five quality states (unknown, missing, invalid, conflict, valid) as a type; the evidence-link convention | Unit tests for week/month boundaries, sparse data, missing periods; null never becomes 0 | Done |
| B. Query foundation | Period aggregation in the metric adapter with drill-down period → day → records; keyset paging; request cancellation; materialised HR daily range; Explore and the rebuilt Data workspace; benchmarks for 7D, 1Y and all-time, cold and warm | No silent truncation at the 1,000-row cap; drill-down values reconcile with source records | |
| C. Integrity v1 | `analysis-core/integrity.ts` (ranges, duplicates, conflicts, coverage) published by `analysis-run` as kind `integrity`; Health Connect and Zepp sync heartbeats; freshness from provenance and heartbeats; health overview, source matrix and issue investigation; the series-break review joins the issue list | Every core stream has a coverage rule; unknown is shown where it applies | |
| C2. Discovery | The corrected discovery screen (prewhitened cross-correlations, effective sample sizes, false-discovery correction, chance bands, older two-thirds of the history only) with a "register this question" action, labelled exploratory | Null data flags at most the corrected rate | |
| D. Decision layer v1 | Evidence-item interface across the engines; the queue derived from ledger verdicts (`not_yet` needs data, `unclear` under observation, actionable without a running experiment ready to test, `clear`/`negligible` without a later decision in review); the workbench; the append-only `decisions` table and decision records | Every decision links to its snapshotted evidence; insufficient data reads blocked; no composite score | |
| E. Integration | Issue → records → evidence → decision links end to end; saved workspaces; regression tests | Workflow walkthrough passes | |

## Phase A: what was built

- **`metric-registry/metric-series.json`** (32 series): per stored series its id, methodology key in
  `metric-registry.json` (null for steps, SpO2, energy, zone minutes and the band's sleep score, which have no entry yet),
  label, unit, table, column, time contract, cadence (daily or per event), day and period methods, long-range grain,
  plausible range (from `docs/analysis-layer-2/11-data-quality-checks.md` and the live data), filters and whether the
  table has a source column. A separate file from `metric-registry.json` so methodology and query contracts can change
  independently; Android ignores it (it is packaged with the registry asset folder but not read).
- **`analysis-core/seriesContract.ts`**: the types and the rules. Time kinds `local_date`, `local_wall`, `utc` with
  dated exceptions and `localDateOf` (marks a date that rests on the default +480 offset). ISO weeks (Monday) and
  calendar months, periods clipped to the range and flagged, empty periods kept. `aggregateDays` (rows to a local day)
  and `aggregatePeriods` (days to a week or month) report value, days observed, samples, coverage, min, max and sources;
  no observation is null, never 0, and sums are never extrapolated. A daily series may not sum within a day (duplicate
  rows would double it). `grainFor`: daily to 90 days, the series' long grain to a year, monthly beyond. Quality states
  `unknown` / `missing` / `invalid` / `conflict` / `valid` with `classifyDay`. `validateSeriesFile` checks the file
  against the registry.
- **`analysis-core/dataRef.ts`**: record, day, period, result and question references with a stable string key;
  `console/src/lib/links.ts` turns them into console links (Data for records and days, Explore for periods, Evidence for
  results and questions). Data accepts `?table=…&id=…`.
- **Console**: the hand-kept `METRICS`, `CHART_SPECS` and `METRIC_OPTIONS` are gone; `SERIES` comes from the contract
  file, chart specs from `chartSpecFor`, and the adapter's day aggregation uses the contract's methods (`mean`, `sum`,
  `last`, `raw`). The adapter still dates rows by their first ten characters; phase B moves it to `localDateOf`.
- **Tests**: `seriesContract.test.ts` and `dataRef.test.ts` (16), `links.test.ts` and the rewritten `schema.test.ts`
  (every contract column, time, offset and filter column exists in the live snapshot; `hasSource` agrees with it).

Found for phase C: days with `steps = 0` (zero possibly standing for no data), `calories_total` as low as 79 kcal
(partial days), sleep of 17.9 h, deep sleep of 425 min in one night, and `activity_intensity_min` and the phone-use
tables still empty.

## Phase 0 notes

- Removed with the Training workspace: `console/src/lib/training.ts` and its test (a copy of the Android load model; the
  fitness-fatigue model lives in `analysis-core` and is shown in Models).
- Kept for Explore: `FTChart`, `chartSpec`, `useChart`, `metricOptions`, `palette`, the metric adapter and its cache.
- The Relationship Lab became Evidence with the same tabs; its DIAGNOSTICS tab became the Models workspace.
- Data Quality is routed as Integrity unchanged; the root `index.html` CONSOLE link now points at `./console/`.
