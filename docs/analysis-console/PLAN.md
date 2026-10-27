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
3. **Time is "local time, UTC-labelled".** The local date is the first ten characters of a timestamp
   (`schema.ts:localDate`). This becomes an explicit, tested contract, including week and month boundaries.
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
| A. Contracts | Query fields in the metric registry and the console map generated from it; typed aggregation semantics (sum, mean, last, min/max, each with sample count); the local-time contract; the five quality states (unknown, missing, invalid, conflict, valid) as a type; the evidence-link convention | Unit tests for week/month boundaries, sparse data, missing periods; null never becomes 0 | |
| B. Query foundation | Period aggregation in the metric adapter with drill-down period → day → records; keyset paging; request cancellation; materialised HR daily range; Explore and the rebuilt Data workspace; benchmarks for 7D, 1Y and all-time, cold and warm | No silent truncation at the 1,000-row cap; drill-down values reconcile with source records | |
| C. Integrity v1 | `analysis-core/integrity.ts` (ranges, duplicates, conflicts, coverage) published by `analysis-run` as kind `integrity`; Health Connect and Zepp sync heartbeats; freshness from provenance and heartbeats; health overview, source matrix and issue investigation; the series-break review joins the issue list | Every core stream has a coverage rule; unknown is shown where it applies | |
| C2. Discovery | The corrected discovery screen (prewhitened cross-correlations, effective sample sizes, false-discovery correction, chance bands, older two-thirds of the history only) with a "register this question" action, labelled exploratory | Null data flags at most the corrected rate | |
| D. Decision layer v1 | Evidence-item interface across the engines; the queue derived from ledger verdicts (`not_yet` needs data, `unclear` under observation, actionable without a running experiment ready to test, `clear`/`negligible` without a later decision in review); the workbench; the append-only `decisions` table and decision records | Every decision links to its snapshotted evidence; insufficient data reads blocked; no composite score | |
| E. Integration | Issue → records → evidence → decision links end to end; saved workspaces; regression tests | Workflow walkthrough passes | |

## Phase 0 notes

- Removed with the Training workspace: `console/src/lib/training.ts` and its test (a copy of the Android load model; the
  fitness-fatigue model lives in `analysis-core` and is shown in Models).
- Kept for Explore: `FTChart`, `chartSpec`, `useChart`, `metricOptions`, `palette`, the metric adapter and its cache.
- The Relationship Lab became Evidence with the same tabs; its DIAGNOSTICS tab became the Models workspace.
- Data Quality is routed as Integrity unchanged; the root `index.html` CONSOLE link now points at `./console/`.
