# Analysis contract v3

Version 3.0.0. This is what the server-published results look like and how they are read. It adds to the Analysis Layer 2 contracts (docs `06` and `19`) and does not replace the on-device shapes they define.

## What is new, what is unchanged

- **Unchanged and still authoritative:** `DailyStateObject`, `Confidence(have, need)`, `Provenance`, `ComparisonResult` and the `EvalState` / `ComparisonState` names (docs `05`, `06`, `19`). The Layer 1 evaluations (`domain/*Evaluation.kt`) keep producing them on the device from the same tables as before.
- **New:** results that need many days of data, a shared statistics core or a pre-registered plan are computed server-side by the Edge Function `analysis-run` and stored as rows in `analysis_results`. Clients only read them.
- **Baselines are break-aware.** Doc `19`'s personal baseline was a plain trailing window. In v3 a window restarts at the last **confirmed** series break of that metric (`windowStart` on the SWC evaluators and the Sleep Index; `SeriesBreakStarts` on Android). Until a break is confirmed nothing changes.

## Envelope: `analysis_results`

| Column | Meaning |
| -- | -- |
| `user_id` | Owner. Rows are written with the service role and readable only by their owner. |
| `kind` | `series_break`, `relationship`, `registered_question`, `typicality`, `experiment`, `intervention_response`, `intervention_its`, `expected_band`, `event_curve`, `case_crossover`, `lag_curve`. New kinds may be added; readers ignore kinds they do not know. |
| `key` | Identifies the result inside its kind: a metric name (`hrv`), a hypothesis id (`alcohol->hrv@1`), `personal_normal`, an experiment id. |
| `as_of` | The data date the result describes. |
| `engine_version` | `CORE_VERSION` of the shared core (`supabase/functions/_shared/analysis-core/version.ts`, currently `3.0.0-alpha.5`: the analysis review phases A to C changed the permutation p, typicality thresholds, series-break confirmation and exposures, and added `expected_band`; phase D rebuilt `relationship` and added the evidence kinds; phase E added the sequential verdict interval, pooling and registered questions; phase F added the randomised experiment designs and `intervention_its`). Bumped whenever an algorithm or parameter changes; part of the unique key so old results stay readable. |
| `inputs_hash` | Hash of the inputs. A result is stale when the hash of the current data differs. |
| `state` | A state name, mirroring the Kotlin contract (see each kind). |
| `confidence_have`, `confidence_need` | `Confidence(have, need)`. |
| `payload` | The kind's body, below. |
| `calculated_at` | When the run produced it. |

Unique key: `(user_id, kind, key, as_of, engine_version)`. A nightly job (`analysis-run-nightly`, 20:30 UTC) refreshes everything; clients read the latest `as_of` for the current `engine_version`.

## Kinds

**`series_break`**, key = metric (`hrv`, `rhr`, `steps`, `sleep_hours`, `deep_min`, `rem_min`, `light_min`). `state`: `Building`, `Stable`, `Unstable`. Payload: `{ metric, deviceSensitive, breaks: [{ date, kind: level|dispersion|both, score, before, after, status: candidate|confirmed|dismissed, reason: origin_change|user_decision|null }], lastConfirmed }`. A statistical break is only a candidate; it becomes confirmed by an origin change within 3 days (`daily_field_provenance`) or a user decision (`series_break_decisions`). Only `lastConfirmed` restarts a baseline.

**`relationship`**, key = question id. `state` (analysis review phase D, catalogue 2.0.0): `clear`, `negligible`, `unclear`, `not_yet` — the 95% interval against the question's no-effect zone (outside it, inside it, across an edge; too few days). Payload: `{ catalogueVersion, familySize, window, capture, id, label, outcome: hrv|rhr|sleep|energy, rung: associated, n, nOn, nOff, reason, effect: {value, lo, hi, unit}, noEffect, mde, p, pAdjusted, direction, signMatchesPrior, eValue: {point, ci}, negativeControl: {value, lo, hi, unit, clear}, readiness: {have, need, unit}, adjustments, summary, notes }`. One regression per question (weekday, slow trend, the outcome before the exposure and the exposure behind it; Newey-West intervals); the interval, verdict and `mde` come from that one fit. `pAdjusted` (Benjamini-Hochberg) is reported, not used by the verdict. Rows from catalogue 1.0.0 (`supported`, `not_distinguishable`, `insufficient`) are read as not yet until the next run replaces them. Since phase E the verdict uses `verdictInterval: { lo, hi, boundary, information }` (a repeated confidence interval, boundary 2.24 / sqrt(information), because questions are re-checked nightly) while `effect` stays the calibrated 95% interval; `model: { estimate, se }` (model units) and `pooled: { family, members, value, lo, hi, unit, shrinkage, probabilityBeyondZone }` (partial pooling within the exposure's family) are added.

**`registered_question`**, key = `reg_<id>` of a `registered_questions` row. Same `state` values and payload as `relationship`, plus `{ registeredOn, source, exposure, target, lag, daysSince }`; tested only on days after `registeredOn`.

**`typicality`**, key = `personal_normal`. `state`: `building`, `typical`, `elevated`, `unusual`, `insufficient`. Payload: baseline window and size, thresholds, the day's distance and its drivers (signal, z, direction), recent days, lookalike days, `skipped` signals. A state, never a score; experimental (see README, Phase 4).

**`experiment`**, key = experiment id. `state`: the stage (`before`, `running`) while it runs, then the primary outcome's verdict (`effect_detected`, `no_detectable_effect`, `insufficient`) once complete. Payload: `{ id, name, asOf, stage, adherence, metrics: [{ metric, role: primary|secondary, verdict: effect_detected|no_detectable_effect|insufficient|running, n0, n1, mean0, mean1, diff: {value, lo, hi, unit}, mde, p, pAdjusted, preTrendPerWeek, trendWarning, replicated, summary, notes }] }`, plus `status`, `supplementId`, the registered `protocol`, `preregisteredAt` and the `mdePreview` shown at registration. While the experiment runs, verdicts are `running`.

Protocol version 2 (analysis review phase F, `experiments.protocol_version = '2'`, `protocol.version = 2`) adds two randomised designs. `randomized_pairs`: `phases` carry `pair` (the order inside each pair was drawn at registration), `washoutDays` apply at the start of every period, plus `pairs`, `periodDays`, `blinded`. `micro_randomized`: `phases` is empty; `assignments: [{ date, arm: A|B }]` (3 or 4 B days a week), `lag` (days until the outcome is read) and `behaviour: { series, withValue, label }` (the rule a B day asks for, checked against that frame series). Both carry `zone` (the primary outcome's no-effect zone in its display unit, set at registration) and `randomization: { method, drawnAt }`. Their primary verdict, once complete, is one of `clear_benefit`, `clear_harm`, `negligible`, `unclear` (four words against `zone`, in the outcome's favourable direction); secondary outcomes report an interval without a verdict. Metrics add `zone` and `randomizationP` (the exact randomisation p: all `2^pairs` orders for pairs, reported from six pairs; re-drawn schedules for days). A protocol stored without `washoutDays`, `minAdherence` or `excludeAlcoholDays` (the phone's JSON left out values equal to their defaults until phase F) is read as 0, 0.7 and true. A deleted experiment (`experiments.deleted_at`) is no longer analysed and readers leave its last row out.

**`intervention_its`** (analysis review phase F), key = intervention id. Read once: `state` is `collecting` until the night after `evaluateOn`, then `evaluated` or `insufficient`, and that row is kept (never redone). Payload: `{ interventionId, preStart, activeStart, evaluateOn, state, metrics: [{ metric, label, state, rung: observed, evaluateOn, preDays, activeDays, gap: {value, lo, hi, unit}|null, verdict: clear|negligible|unclear|null, zone, days: [{ date, value, forecast, lo80, hi80 }], summary }] }`. The forecast is the level and weekday pattern of the 56 days before `activeStart` (from the last confirmed series break, if later); `evaluateOn` is `interventions.evaluate_on`, else 27 days after the start, and never after `ended_at`. A confirmed series break on or after the start makes the metric `insufficient`. It is the OBSERVED rung and never drives a protocol action on its own (`protocolRules.ts` turns a change proposed from an observational verdict into `reassess`).

**`expected_band`** (analysis review phase C), key = metric (`hrv`, `rhr`, `sleep_hours`). `state`: `building` (fewer than 7 one-step errors in the current regime: no band), `provisional` (a band, widened by Student t, but no run flags), `ready` (21 or more errors). Payload: `{ metric, unit, transform: ln|none, better: higher|lower|range, state, asOf, regimeStart, lastObserved, have, need, params: { alpha, gamma, sigma, df, q80, q99 }, calibration: { days, inside80, inside99 }, recent: [BandPoint x 28], next: BandPoint|null, ewma: { lambda, limit, value, date, runsActive } }`, where `BandPoint = { date, value, expected, lo80, hi80, lo99, hi99, mu, sigma, z, flag: extreme|run|null, side: low|high|null, excluded }`. Values and band edges are in natural units; `mu` and `sigma` are in model space (log for HRV) so a reading that arrives after the run is placed as `z = (T(value) - mu) / sigma` and the run chart is stepped once (`lambda * clip(z, q99) + (1 - lambda) * ewma.value`). `next` is the band for the day after `asOf`. A confirmed series break of the metric starts a new regime.

**`event_curve`**, key = question id it illustrates (`alcohol_hrv`, `late_meal_sleep`, ...). `state`: `ready`, `not_yet` (fewer than 8 events). Payload: `{ id, label, exposure, outcome, unit, events, need, days: [{ k (-3..5), mean, lo, hi, chanceLo, chanceHi, n }], peak: {k, value}|null, backByDay, differentBefore, summary }`. `mean` is the distance from the expected-band forecast (made from before the window) on event days minus the same on comparison days of the same weekday; the chance band is a simultaneous permutation envelope.

**`case_crossover`**, key = outcome (`hrv`, `rhr`, `sleep_hours`). `state`: `ready` when any exposure could be estimated. Payload: `{ outcome, side: low|high, text, caseDays, exploratory: true, rows: [{ exposure, label, cases, need (15), or, lo, hi, verdict: more_often|less_often|no_clear_difference|not_yet }] }`. Time-stratified referents (same weekday, same month), Mantel-Haenszel odds ratio with the Robins-Breslow-Greenland interval.

**`lag_curve`**, key = `training_hrv`, `steps_hrv`, `alcohol_hrv`. `state`: `ready`, `not_yet`. Payload: `{ id, label, exposure, outcome, unit, perText, rows, exposedDays, need: {rows, exposedDays}, lags: [{lag (0..6), value, lo, hi}], cumulative: {value, lo, hi}, clearLags, lastClearLag, dose: [{group: 0|1-2|3+, n, mean, lo, hi}]|null, summary }`.

## Other tables the contract depends on

`evidence_ledger` (analysis review phase E: every nightly verdict, append-only), `registered_questions` (hints registered as questions; fields frozen, withdrawn not deleted), `experiment_exclusion_snapshots` (phase F: the days each ended period or week left out, frozen the night after it ends; written only by the analysis job, never updated), `interventions.evaluate_on` (phase F: the read date, fixed once the intervention has started), `daily_field_provenance` (which origin wrote a daily field), `series_break_decisions` (the person's answer), `experiments` (the pre-registered plan, with a trigger that refuses changes to it; since phase F soft-deleted through `deleted_at`, and a randomised schedule must start on or after the registration date), `interaction_records` (curated pairs with a `reviewed` flag), `log_agent_runs` (audit of typed log messages), `grip_strength_log`. See the README.

## Readers

- **Android:** read models in `data/model/AnalysisModels.kt` and `data/AnalysisRepository.kt` (every field defaulted, so an older app ignores fields it does not know). `ANALYSIS_CONTRACT_VERSION` in `AnalysisLayer2Contract.kt` names this version.
- **Console:** the Relationship Lab tabs read the same rows with the user's session.

## Compatibility rules

1. Additive only within a major version: new fields, new kinds, new `state` values that old readers can show as plain text.
2. A change to what a number means bumps `CORE_VERSION`; the old rows stay.
3. Readers must tolerate a missing result (before the first run, or when the read fails) and show nothing or "not yet analysed", never an error screen.
4. A result never carries real health data in this repository: fixtures and docs use synthetic values only.

## What v3 deliberately did not do

- No Kotlin `RelationshipResult` / `ExperimentResult` / `TypicalityResult` types in `AnalysisLayer2Contract.kt`: the app reads the stored JSON through its own row models instead.
- No `dataOrigin` field on `Provenance`: origin lives in `daily_field_provenance`, because the Health Connect sync writes each metric in a separate upsert and a shared column would be overwritten.
