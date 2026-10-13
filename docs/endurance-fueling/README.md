# Endurance Fueling & Personal Experiments

Design and status notes for Linear milestone **06.3 — Endurance Fueling & Personal Experiments** (`DAV-380`..`DAV-386`, `06.3.1`..`06.3.7`). The goal is a reliable data layer for what's actually consumed during trail runs and endurance sessions (gels, drink mixes, chews, bars, homemade recipes -- carbs, fluid, sodium, caffeine), then contextual analysis and structured N-of-1 experiments on top of it.

Repeated, explicit instruction throughout the milestone: **reuse canonical Nutrition Intelligence, activity/training records, metric registry, provenance, personal-baseline and Intervention & Protocol Intelligence contracts; do not create a duplicate nutrition or experimentation engine.**

## Architecture in one paragraph

Three existing systems do almost all of the hard work already, confirmed against the live schema and code rather than assumed:

1. **Single-counting is already solved.** `nutrient_intake` is a ledger table, not a derived view: every physical log row (a `meal_items` row, a `supplement_log` row) writes exactly one `nutrient_intake` row keyed by `(source_type, source_id)`, and `NutrientBreakdownRepository.kt` sums that ledger directly. A fueling intake event just needs to write one more `source_type` (`'fueling_intake'`) into the same ledger to be counted exactly once in daily totals -- no new aggregation logic.
2. **"Comparable activities" is already a generic engine, not a taxonomy.** `domain/comparison/ComparableSet.kt`'s `comparableSet(history, isComparable)` takes any predicate; it already ships `isComparableSessionDistance` (ratio-based) and is built to grow more predicates without changing shape. `comparePersonal(...)` does the actual comparison, publishing the same `ComparisonResult`/`ComparisonState`/`Confidence` contract (`AnalysisLayer2Contract.kt`, v3.0.0) used everywhere else.
3. **Milestone 08's Intervention & Protocol Intelligence layer already covers this domain.** `interventions.domain` already includes `'nutrition'`; `experiments.intervention_id` (08.5) already lets a pre-registered N-of-1 experiment reference any intervention. 06.3.6 is almost entirely wiring an eligibility layer on top, not new modeling (see `docs/intervention-protocol-intelligence/README.md`).

What's genuinely new: a versioned fueling-product/recipe catalog (no existing *immutable*-versioning pattern in this codebase -- `supplement_products` only mutates in place, "ended not deleted") and the intake-event log itself (closely modeled on `supplement_log` + `AddEntryRepository.kt`'s wiring into `nutrient_intake`).

## Reuse vs. generalize vs. new

| Reused as-is | Generalized | New |
| -- | -- | -- |
| `nutrient_intake` ledger (write one more `source_type`) | `ComparableSet.kt` (new predicates: elevation/grade, training-load/recovery context, alongside the existing distance-ratio one) | `fueling_products`, `fueling_recipe_versions`, `fueling_recipe_ingredients` |
| `foods`/`food_nutrients`/`food_servings` (homemade recipes reference known ingredients) | | `fueling_intake_log` |
| `exercise_sessions` (activity linkage) | | Per-activity fueling summary/timeline contract |
| `zepp_workout_detail.decoded.summary` (GAP, efficiency factor -- read, never recompute) | | Fueling-specific N-of-1 eligibility layer (baseline/intervention-condition activity selection, adherence from `fueling_intake_log`) |
| `comparePersonal`/`AnalysisLayer2Contract.kt` (`ComparisonResult` etc.) | | Fueling workspace in the Analysis Console |
| `interventions`/`experiments`/`experiment.ts`/`interventionResponse.ts` (milestone 08, zero schema change) | | |
| `NutritionResolver.kt`'s nutrient-scaling formula (`amount * (consumedAmount / basisQty)`), mirrored in a new TS equivalent for recipes | | |

## Canonical entities

| Linear concept | Concrete shape |
| -- | -- |
| Fueling product | `public.fueling_products` row (06.3.2) |
| Recipe version (immutable) | `public.fueling_recipe_versions` row, append-only (06.3.2) |
| Recipe ingredient | `public.fueling_recipe_ingredients` row, references `foods` where possible (06.3.2) |
| Planned/actual intake event | `public.fueling_intake_log` row (06.3.3), each consumed event also writes one `nutrient_intake` row (`source_type='fueling_intake'`) |
| Per-activity fueling summary | A pure fold of one activity's `fueling_intake_log` rows + its `exercise_sessions` duration -- a function, not a table (06.3.4) |
| Comparable-activity set | `comparableSet()` with new fueling-relevant predicates (06.3.5) |
| N-of-1 fueling experiment | `interventions` (`domain='nutrition'`, `subject_type='fueling_recipe'`) + `experiments` (`intervention_id`) -- no new table (06.3.6) |

## Single-counting rule (06.3.1's central design decision)

A fueling intake event's actual-consumed nutrients (carbs/fluid/sodium/caffeine) are written as `nutrient_intake` rows with `source_type='fueling_intake'`, `source_id` = the `fueling_intake_log` row's id -- the exact same pattern `meal_items` (`source_type='meal_item'`) and `supplement_log` (`source_type='supplement'`/`'supplement_product'`) already use. `NutrientBreakdownRepository.kt` already sums `nutrient_intake` directly rather than re-deriving totals from multiple tables, so this requires **no change to that repository** -- daily totals pick up fueling intake automatically and exactly once. Planned (not-yet-consumed) intake never writes to `nutrient_intake`.

## Status

| Phase | State |
| -- | -- |
| 06.3.1 Audit & architecture | **Done.** This document. |
| 06.3.2 Versioned fueling products/recipes | **Done.** Migration `supabase/migrations/20261013_fueling_products.sql` (applied); deterministic totals in `supabase/functions/_shared/analysis-core/fuelingRecipe.ts`, tested in `fuelingRecipe.test.ts`. |
| 06.3.3 Log planned/actual intake | **Done.** Migration `supabase/migrations/20261013_fueling_intake_log.sql` (applied); `scaleRecipeToQuantity` in `fuelingRecipe.ts`, tested. |
| 06.3.4 Per-activity summaries and timeline | **Done.** `supabase/functions/_shared/analysis-core/fuelingSummary.ts`, tested in `fuelingSummary.test.ts`. |
| 06.3.5 Cross-activity pattern analysis | **Deferred, documented only.** See notes -- the codebase's own stated policy argues against writing this now. |
| 06.3.6 Structured N-of-1 fueling experiments | **Done.** `supabase/functions/_shared/analysis-core/fuelingExperiment.ts`, tested in `fuelingExperiment.test.ts`. One additive metric (`fueling_carb_rate`) added to `experiment.ts`'s `METRIC_INFO`, no regression in the 188-test suite. |
| 06.3.7 Console + end-to-end validation | **Done.** New FUELING tab in the Relationship Lab (`console/src/workspaces/Fueling.tsx`), build-verified; real-data check against live rows from 06.3.3's verification. |

## 06.3.2 notes

`computeRecipeTotals`/`resolveIngredientTotals` in `fuelingRecipe.ts` mirror `domain/NutritionResolver.kt`'s scaling formula exactly (`amount * (consumedAmount / basisQty)`) and its "a named serving's grams/ml equivalence wins over a raw quantity" precedent, rather than inventing a second formula -- tested against a maltodextrin-gel fixture (24g carbs / 20mg sodium per 32g basis) and confirmed the per-ingredient and whole-recipe math match hand-calculated values exactly. An unresolvable ingredient (no matching food, or a food missing that particular nutrient) contributes null, never zero, and one unresolved ingredient doesn't discard the rest of the recipe's known totals.

`fueling_recipe_versions` is append-only, structurally rather than by convention: `fueling_recipe_versions_immutable()` rejects every `UPDATE` and `DELETE` outright (not a column-subset lock like `experiments_lock_protocol` -- nothing about a published version may change). Verified live: a product + two versions inserted, an `UPDATE` on version 1 rejected with the expected error, version 2 still insertable afterward, both versions intact. `DELETE` also triggered the same session-wide MCP-tool timeout seen throughout milestone 08's verification (not a trigger failure -- a follow-up `select` confirmed both rows untouched, and the `UPDATE` case already proves the trigger function itself works for both operations it's attached to). `get_advisors` shows no new findings beyond the four pre-existing ones.

## 06.3.3 notes

`fueling_intake_log` holds one row per event (planned, consumed, skipped or partial), edited in place as it actually happens -- not two separate planned/actual tables -- matching "post-run correction" naturally (mark a planned row consumed, or correct its values, after the run). Ordering is by `elapsed_seconds` (elapsed time since `exercise_sessions.start_time`), never by `logged_at`, so missing/inconsistent device timestamps can't reorder events.

**Single-counting is enforced by a trigger, not app-layer code** -- a deliberate departure from how `meal_items`/`supplement_log` do it (their own Kotlin repositories dual-write into `nutrient_intake`, no DB trigger). Two reasons: this environment cannot build or run the Android app, so a trigger is the only version of this logic actually testable here; and delete-then-reinsert on every `INSERT`/`UPDATE`/`DELETE` structurally guarantees correctness after an edit, rather than depending on every future client reimplementing the dual-write correctly. `fueling_intake_log_sync_nutrient_intake()` only ever writes rows for `status in ('consumed','partial')`; `planned`/`skipped` never reach the ledger.

Retry-safety follows the existing app idiom (`upsert(...).onConflict(naturalKey)`, e.g. `health_connect_record_id`) rather than new sync infrastructure: a client-generated `client_key` (`unique(user_id, client_key)`) means retrying a failed submit resolves to the same row instead of creating a duplicate.

**A real bug found and fixed during verification:** `nutrient_intake.source_type`'s CHECK constraint only allowed `'meal_item'`/`'supplement'` -- not documented anywhere in this repo's migrations (the constraint predates them), only discovered because the trigger's first live insert was rejected by it. Fixed additively in `supabase/migrations/20261013_nutrient_intake_fueling_source.sql` (widens the CHECK to add `'fueling_intake'`; existing rows unaffected).

Verified live end to end against a real `exercise_sessions` row: a `consumed` event correctly wrote 3 `nutrient_intake` rows with the right computed `logged_at` (`session.start_time + elapsed_seconds`); editing it (new `carbs_g`, added `fluid_ml`) left exactly the right 4 rows, no stale duplicate; marking it `skipped` zeroed the ledger back to 0 rows; a `planned` row never wrote any; and an `upsert` retry with the same `client_key` resolved to the existing row rather than creating a second one. `get_advisors` shows no new findings.

## 06.3.4 notes

`summariseFueling` is a pure fold over an activity's event list -- no I/O, no table of its own (DAV-383's acceptance criterion: "expose a stable summary contract... rather than calculating metrics independently in multiple screens"). Planned and actual totals are computed and reported separately (never merged); skipped events are counted but excluded from both. Per-hour rates are null whenever duration is zero or unknown, never a misleading number with no real "hour" behind it -- `durationKnown` makes that explicit rather than leaving a caller to infer it from a null. Intake gaps are reported as plain elapsed-time buckets (default 30 min, configurable) with no event in them -- descriptive, not a judgment -- and the boundary case (an event logged at the exact activity end) is handled explicitly so it still closes that bucket's gap rather than appearing as a false gap. De-duplication is deliberately not this function's job: it folds whatever list it's given, trusting `fueling_intake_log`'s own row identity as the single source of truth (tested explicitly, so this is a documented design choice, not an oversight).

## 06.3.5 notes (deferred)

Checked `domain/comparison/ComparableSet.kt` directly before writing anything for this phase. Its own header comment states the policy this milestone's plan ran into: `comparableSet()` stays a generic filter on purpose, and the file explicitly says adding concrete predicates (it names exactly the kind this phase would add -- "environmental conditions... nothing in this app yet needs") *speculatively, before a metric actually needs them*, "would be exactly the kind of gap-filling this project's own conventions avoid." The existing `isComparableSessionDistance` predicate was added together with the one real feature (`Durability.kt`) that calls it, not ahead of it.

06.3.5 as specified needs more than a predicate to reuse that pattern honestly: it wants within-run views against "RPE and symptoms where available" and between-run comparisons against "relevant activity outcomes" -- but there is no GI-symptom logging anywhere in this schema today, and no single settled "activity outcome" to compare a carb rate against (pace? HR decoupling? a subjective rating?) is specified by the issue or implied by existing data. Writing `isComparableSessionElevation`/`isComparableSessionTrainingLoad` and a `FuelingPatternComparison.kt` right now, with no real caller and no settled outcome variable, would be inventing product scope under a deadline-shaped justification rather than following what was actually asked -- exactly what the file's comment warns against, and (same as 06.3.4-08.9's Android pieces) completely unverifiable here regardless, since this is Kotlin-only with no TS equivalent and no Android SDK in this environment.

**What this phase should reuse once it has a concrete first comparison to make:** `comparableSet(history, isComparable)` (generic filter, already fine as-is), `comparePersonal(...)` (the actual comparison, already fine as-is), `AnalysisLayer2Contract.kt`'s `ComparisonResult`/`ComparisonState`/`Confidence` (the shared output shape, already fine as-is). The only new code should be: (a) a settled decision on which activity outcome(s) fueling patterns are compared against, (b) one concrete comparability predicate for that specific comparison (elevation/grade-based, most likely, given GAP already exists), built alongside its first real caller, not ahead of it.

## 06.3.6 notes

Reuses the Intervention & Protocol Intelligence lifecycle exactly as planned: a fueling N-of-1 experiment is still a `public.interventions` row (`domain='nutrition'`, `subject_type='fueling_recipe'`) and is analysed through 08.6's existing `interventionResponse.ts` ("the existing personal-response analysis framework", DAV-385's own phrase) -- no new table, no new lifecycle, no new statistics engine.

**The one new metric.** `interventionResponse.ts` (and `experiment.ts`, which it imports `METRIC_INFO` from) operates on named series in a `Frame`, but the fixed `MetricId` union didn't include anything fueling-shaped. 08.5's notes explicitly flagged widening this vocabulary as "future, separate work... tracked here, not attempted in 08.5" specifically because there was no concrete caller yet to justify it. There is now: `fueling_carb_rate` was added as one additive `METRIC_INFO` entry (label, unit `g/h`, no `ln` transform, no `breakKeys`) -- existing metrics untouched, full suite re-run immediately after to confirm zero regressions (it passed, 176 -> 188 only from this phase's own new tests). Unlike the raw biometric series `buildFrame` reads from DB tables, `fueling_carb_rate` is a **derived** series: the caller (this module) builds it from eligible activities' per-hour carb rate, not a column anywhere.

**Eligibility and deviation tracking** (DAV-385: "track activity eligibility... deviations"). `evaluateEligibility` checks, in order, activity type, minimum duration, a known carb rate, and that caffeine/sodium per-hour stayed within a declared tolerance of the protocol's "held stable" targets -- an activity failing any check is excluded with a named reason (`wrong_type`/`too_short`/`unknown_carb_rate`/`caffeine_deviation`/`sodium_deviation`), never silently averaged in as if it were clean data. `buildDailyCarbRateSeries` folds only eligible activities into one value per day (averaged when more than one falls on the same day), producing exactly the series `analyseInterventionResponse` needs -- verified end to end with a planted 90-day dataset (45 baseline days at 60g/h, 45 intervention days at 120g/h) correctly producing `signal_observed` with a positive diff.

**Interpretability gate** (DAV-385: "cannot be marked interpretable without... sufficient recorded data for the configured rule"). `isInterpretable` counts *eligible activities* per phase, not calendar days -- an endurance athlete doesn't run daily, so a 45-day window can contain far fewer than 45 real data points, which a day-count-only gate would miss.

**Verdict vocabulary** (DAV-385: "return keep / modify / stop / inconclusive... not an automatic medical directive"). `fuelingVerdict` is a thin label over 08.7's existing `proposeProtocolAction` -- `maintain`->keep, `increase`/`decrease`/`pause`->modify, `terminate`->stop, `extend_evaluation`/`reassess`->inconclusive -- and always returns the full underlying proposal (action, rationale, source inputs) alongside the label, so the review surface loses nothing. This satisfies "no duplicated generic experiment lifecycle is created" literally: there is exactly one decision engine, labeled two different ways for two different review contexts.

## 06.3.7 notes

`Fueling.tsx` lists products/recipes (latest version per product) and, for a selected run/hike/ride, its logged intake timeline alongside the computed summary -- read-only, same pattern `Interventions.tsx` (08.9) already established. The summary is **not recalculated client-side**: `Fueling.tsx` imports `summariseFueling` directly from `supabase/functions/_shared/analysis-core/fuelingSummary.ts` across the console/Supabase-functions boundary, the same cross-import `docs/analysis-layer-3/README.md` already documents as supported ("Node 24, Deno and Vite all import it unchanged") but that no console file had actually exercised yet -- confirmed empirically rather than assumed: `tsc -b` resolves it, and the production `vite build`'s `RelationshipLab` chunk grew (19.37kB -> 26.72kB) and visibly bundles `fuelingSummary.ts`'s logic in, not an orphaned file tree-shaken away. This is the single-stable-contract principle (06.3.4) actually holding across languages and build tools, not just within one file.

**Real-data validation** (DAV-386's own acceptance criterion): queried the live `fueling_intake_log` rows created during 06.3.3's verification (`id=2`, status `skipped`; `id=3`, status `planned`) with the exact column list `Fueling.tsx` selects, and hand-checked them against `summariseFueling`'s documented behavior -- both correctly contribute nothing to `actualTotals` (neither is `consumed`/`partial`), only the planned row's `carbsG=25` appears in `plannedTotals`. This confirms the real schema and the real function agree, not just synthetic fixtures.

Verified: `tsc -b` (clean), `oxlint` (same warning class as sibling files, nothing new), production `vite build` (succeeds). **Not verified:** a live, authenticated browser session (same limitation as 08.9's Interventions tab -- no test credentials, no component-test harness in this repo).

## Still open

- 06.3.5: revisit once there is a concrete, settled outcome variable and a specific comparison to build -- not attempted in this pass, for the reasons above.
- Android: no mobile logging UI was attempted for 06.3 (products/recipes, intake logging, N-of-1 registration all need a Compose screen and, per 06.3.1's own note, this environment has no Android SDK to verify any of it). The schema and pure logic are ready for it whenever that's picked up.
- Wiring `fuelingExperiment.ts` into an I/O layer (a `runFuelingExperiments.ts` publishing `analysis_results`, mirroring `runInterventionResponse.ts`) and deploying it alongside `analysis-run` -- not attempted in this pass, consistent with 08.6/08.7/08.8 leaving their own I/O wiring as follow-up work rather than redeploying the live cron'd function repeatedly within one session.
- A live, authenticated check of the new FUELING console tab -- build-verified only, same caveat as 08.9.
- Verification test rows in the live project: `fueling_products` id `1`, its two `fueling_recipe_versions` rows (ids 1-2), and `fueling_intake_log` rows (ids 2-3) -- harmless, left in place (DELETE unavailable this session, same as milestone 08's leftover rows).
- No offline-queue/outbox infrastructure exists anywhere in the Android app (confirmed by exploration: no Room entities, no outbox table, no connectivity-aware write queue). 06.3.3's "retry without duplicate submissions" should follow the existing `upsert(...).onConflict(naturalKey)` idiom (e.g. `AddEntryRepository.kt`'s `user_id,date` / `health_connect_record_id` keys), not introduce new sync infrastructure.
- Android implementation (06.3.3 onward) carries the same caveat milestone 08 documented: this environment has no Android SDK, so any Kotlin written here is unverified until built on a real machine.
