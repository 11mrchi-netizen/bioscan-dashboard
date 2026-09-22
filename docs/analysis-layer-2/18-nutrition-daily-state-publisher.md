# 18 — Publishing canonical nutrition daily metrics to Analysis Layer 2

**Linear:** [DAV-180](https://linear.app/biodashboard/issue/DAV-180) · **Status:** live, verified against real data

## Overview

The first real implementation of the [output contract](06-output-contract.md)'s `DailyStateObject`
shape — nothing in `domain/*.kt` had built any of `Provenance`/`InputCompleteness`/`ObservationRef`/
`DailyStateObject` before this ticket. `domain/analysis/AnalysisLayer2Contract.kt` ports only what
this ticket needs (`DailyStateObject` and its shared types); `SessionLoadVector`/`RollingStateSeries`/
`PerformanceAnchor` stay contract-only until the tickets that need them (DAV-56/59/61) build them.

`domain/analysis/NutritionDailyState.kt` (pure, mirrors `NutritionResolver.kt`'s split) turns one
day's already-fetched `meal_items`/`hydration_daily` rows into a `DailyStateObject` with ten
dimensions (`ENERGY_INTAKE` through `EFFECTIVE_HYDRATION`) plus a nutrition-specific envelope
(`NutritionDailyState`) carrying what the ticket's scope asks for that the four shared shapes don't
have a field for: meal count, estimated-vs-confirmed item counts, a beverage-class fluid breakdown,
and the raw per-item list ("keep meal/item granularity available"). `data/NutritionDailyStateRepository.kt`
is the I/O shell — fetch `meals` in range, `meal_items` for those meals, that day's `hydration_daily`
row, and (for beverage items only) each item's `foods.beverage_class`.

## Resolving the `InputCompleteness` design question

[Doc 05](05-confidence-propagation.md) is explicit: `present`/`ideal` name real input fields or
sources, "not abstract categories." An early idea (time-of-day buckets — morning/midday/evening) was
rejected on exactly this ground the moment that rule was re-read: a time bucket describes when
something was logged, not what data source fed the number. What each nutrition dimension can
actually draw from is a small, real, enumerable set of **tables**:

- Every macro/micro dimension (energy, protein, carbs, fat, fiber, sugar, sodium, caffeine) has
  exactly one real source: `meal_items`. `ideal = {"meal_items"}`, always; `present` is the same set
  when at least one item contributed a non-null value that day, empty otherwise — so breadth is
  binary (`FULL` or `MINIMAL`) for these, which is honest: there's no second source to be partially
  missing.
- `FLUID_INTAKE` is the one dimension with two real sources — a beverage logged as part of a meal
  (`meal_items.water_ml`) and a manual daily total (`hydration_daily.ml`), the same pairing
  [doc 17](17-health-connect-nutrition-writeback.md) already merges additively for Health Connect.
  `ideal = {"meal_items", "hydration_daily"}`; `present` is whichever of the two actually
  contributed. (Per doc 05's own tier rule — `MINIMAL` whenever `present.size <= 1` — a single
  contributing source still reads `MINIMAL`, not `PARTIAL`; `PARTIAL` only appears for an `ideal` set
  of three or more members, which no nutrition dimension has today.)
- `EFFECTIVE_HYDRATION` only ever comes from a beverage item that resolved DAV-181's hydration
  factor model — `hydration_daily`'s manual log has no beverage class to model, so it's never a
  candidate source here. `ideal = {"meal_items"}`.

## Depth confidence for a same-day raw sum

Every dimension here is a same-day **raw** sum of already-resolved `meal_items` values, not a
multi-day baseline. Doc 05's depth axis ("how much history backs a value") doesn't have a
meaningful non-trivial answer for a single day's own total — there's no history to be partway
through accumulating. `Confidence(1, 1)` whenever any real value exists, `Confidence(0, 1)` on
`NoData`: depth is trivially satisfied for a raw fact about today, and breadth (which real source(s)
were present) carries the entire "how complete is this" story, exactly as doc 05 intends when it
says breadth is what depth doesn't already answer.

## What's in the nutrition-specific envelope, and why it isn't in the shared contract

`NutritionDailyState` wraps `DailyStateObject` with four more fields the ticket's scope explicitly
asks for, none of which the shared shapes define:

- `mealCount` — a plain fact, not a "completeness score." No target meal count is stored anywhere in
  this app (`NutritionEvaluation.kt`'s own `COMPLETE_MEALS_MIN = 3` is a *classification* threshold
  for a different, multi-day evaluation, not a per-day target this ticket should reuse or restate);
  inventing one here would fabricate a number nothing in the product actually asserts.
- `estimatedItemCount` / `confirmedItemCount` — `meal_items.is_estimated`, split. Satisfies
  "estimated and confirmed quantities remain distinguishable" directly from the real column DAV-168
  already writes, no new field.
- `fluidMlByBeverageClass` — beverage items grouped by their food's `beverage_class`, plus the
  manual `hydration_daily` total under a fixed `"manual_log"` key. Sums back to exactly
  `FLUID_INTAKE`'s own value (verified in `NutritionDailyStateTest.testFluidDimension_mergesBeverageAndManualLog`),
  so the breakdown is never a second, driftable number.
- `items: List<NutritionItemObservation>` — the raw `MealItemRow` plus its meal's `logged_at`, for
  "keep meal/item granularity available" and same-source caffeine timing ("where timestamps are
  available") — a consumer that needs per-meal caffeine timing already has it here, without a second
  query.

## Verified against real data

No real `meal_items` rows were live in the account at the time of this ticket (every earlier
ticket's own test meal was cleaned up after verification, per this project's own convention). A real
verification meal was inserted directly (`foods` id 3 = the black coffee fixture from doc 13, id 2 =
the chicken fixture — same two rows doc 17 used) using the exact same resolved numbers doc 17
already verified against real Health Connect (99.9ml water, 40mg caffeine, 84.915ml effective
hydration at the `v1` retention factor) alongside one manual macro item (chicken, `is_estimated =
true`) and the account's own real `hydration_daily` row for the day (1900ml, pre-existing, not test
data). Querying the same rollup this repository computes returned meal_count=1, energy=166.0
(1.0 + 165.0), 1 estimated + 1 confirmed item, beverage water=99.9ml, effective_hydration=84.915ml —
matching `NutritionDailyStateTest`'s hand-verified expectations exactly, including the coffee-specific
numbers doc 17 already confirmed against the real platform. The verification meal was deleted
afterward; the real `hydration_daily` row was left untouched.

## What's deliberately not here

- Recovery, readiness, fatigue, energy-balance, fluid-demand, or sweat-loss state — explicitly out of
  scope per DAV-180's own text; those are later cross-domain milestones.
- Micronutrients beyond fiber/sugar/sodium — "selected micronutrients when sufficiently complete" is
  scoped-in, but no micronutrient data exists anywhere in this schema yet (`NutritionResolver.kt`'s
  own nutrient map has no vitamin/mineral entries) — nothing to publish until DAV-162/163's import
  scope grows or a future ticket adds them.
- `SessionLoadVector` / `RollingStateSeries` / `PerformanceAnchor` — other domains' tickets.
