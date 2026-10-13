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
| 06.3.3 Log planned/actual intake | Not started. |
| 06.3.4 Per-activity summaries and timeline | Not started. |
| 06.3.5 Cross-activity pattern analysis | Not started. |
| 06.3.6 Structured N-of-1 fueling experiments | Not started. |
| 06.3.7 Console + end-to-end validation | Not started. |

## 06.3.2 notes

`computeRecipeTotals`/`resolveIngredientTotals` in `fuelingRecipe.ts` mirror `domain/NutritionResolver.kt`'s scaling formula exactly (`amount * (consumedAmount / basisQty)`) and its "a named serving's grams/ml equivalence wins over a raw quantity" precedent, rather than inventing a second formula -- tested against a maltodextrin-gel fixture (24g carbs / 20mg sodium per 32g basis) and confirmed the per-ingredient and whole-recipe math match hand-calculated values exactly. An unresolvable ingredient (no matching food, or a food missing that particular nutrient) contributes null, never zero, and one unresolved ingredient doesn't discard the rest of the recipe's known totals.

`fueling_recipe_versions` is append-only, structurally rather than by convention: `fueling_recipe_versions_immutable()` rejects every `UPDATE` and `DELETE` outright (not a column-subset lock like `experiments_lock_protocol` -- nothing about a published version may change). Verified live: a product + two versions inserted, an `UPDATE` on version 1 rejected with the expected error, version 2 still insertable afterward, both versions intact. `DELETE` also triggered the same session-wide MCP-tool timeout seen throughout milestone 08's verification (not a trigger failure -- a follow-up `select` confirmed both rows untouched, and the `UPDATE` case already proves the trigger function itself works for both operations it's attached to). `get_advisors` shows no new findings beyond the four pre-existing ones.

## Still open

- 06.3.3 onward, in the sequence above -- each depends on `fueling_recipe_versions` existing, nothing on 06.3.2 depends on them.
- Verification test rows in the live project: `fueling_products` id `1` ("Maurten Gel 100 (verification test row)") and its two `fueling_recipe_versions` rows (ids 1 and 2) -- harmless, left in place for the same reason as milestone 08's leftover rows (DELETE unavailable this session).
- No offline-queue/outbox infrastructure exists anywhere in the Android app (confirmed by exploration: no Room entities, no outbox table, no connectivity-aware write queue). 06.3.3's "retry without duplicate submissions" should follow the existing `upsert(...).onConflict(naturalKey)` idiom (e.g. `AddEntryRepository.kt`'s `user_id,date` / `health_connect_record_id` keys), not introduce new sync infrastructure.
- Android implementation (06.3.3 onward) carries the same caveat milestone 08 documented: this environment has no Android SDK, so any Kotlin written here is unverified until built on a real machine.
