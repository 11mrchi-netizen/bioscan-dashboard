# 13 — Deterministic nutrition resolver and calculator

**Linear:** [DAV-164](https://linear.app/biodashboard/issue/DAV-164) · **Status:** resolver + repository implemented

## Overview

The non-AI layer that turns a resolved `food_id` + consumed quantity into authoritative nutrient
totals. Gemini (DAV-166/167) and barcode lookup (DAV-165) only ever produce *candidates* — a food
identity and a rough quantity; this is what turns that into numbers, and it's the only thing allowed
to be authoritative about nutrient arithmetic when database data exists (DAV-164's own rule).

Split across two files:

- **`domain/NutritionResolver.kt`** — pure functions, no I/O, no Supabase, no suspend. Given
  `FoodNutrientRow`/`FoodServingRow`/`HydrationFactorModelRow` already fetched, computes totals.
  This is what makes the acceptance criteria "known food + quantity produces deterministic totals"
  and "changing quantity recalculates totals without duplicates" true by construction — there's no
  cached state to go stale or double-count; every call is a fresh fold over its arguments.
- **`data/NutritionResolverRepository.kt`** — fetches exactly one food's own rows from Supabase
  (never scans the whole catalog) and calls the pure functions above.

## Core functions (`domain/NutritionResolver.kt`)

| Function | Does |
|---|---|
| `resolveConsumedBaseAmount` | Serving count × serving's grams/ml, or a direct quantity, → one base amount in the same unit family `food_nutrients.basis_unit` uses |
| `scaleNutrientToQuantity` | `nutrient.amount × (consumedAmount / nutrient.basisQty)` — the basis-ratio scaling every nutrient value goes through |
| `resolveFoodItemNutrients` | Applies the scaling across one food's full `food_nutrients` row set → a `ResolvedNutrients` (calories/protein/fat/carbs/fiber/sugar/sodium/water/caffeine/alcohol); a nutrient this food never recorded stays `null`, never a fabricated `0` |
| `aggregateMealItemNutrients` | Null-safe sum across a meal's resolved items — a field null on every item stays null, but one item with a real value is enough to make the aggregate real |
| `computeEffectiveHydration` | DAV-181's retention-factor model applied to a resolved `water_ml`; returns `null` (not a guess) when the beverage class or water content is unknown |

## Provenance

`NutritionResolverRepository.resolveMealItem` returns a `FoodProvenance` (foodId, foodSourceId,
sourceFoodId, datasetVersion) alongside the resolved nutrients — the `meal_item → food → source →
source_food_id → dataset_version` chain DAV-164 requires stays attached to the result, not only to
the raw rows the repository already fetched and discarded.

## Verified against real data

Two hand-entered manual fixture foods exist in Supabase for end-to-end testing ahead of DAV-162/163's
real imports: *"Chicken breast, cooked, skinless"* (`food_id=2`) and *"Black coffee, brewed"*
(`food_id=3`, `beverage_class='coffee'`). Both the resolver's unit tests
(`domain/NutritionResolverTest.kt`) and a direct SQL cross-check against these rows agree on the
same numbers — e.g. 150g of the chicken fixture resolves to 247.5 kcal / 46.5g protein / 5.4g fat /
111mg sodium; 240ml of the coffee fixture resolves to 239.76ml water / 96mg caffeine, with `v1`'s
0.98 coffee retention factor giving 234.96ml effective hydration.

## What's deliberately not here

Day-level rollup across a user's meals (the "Foundation additions" daily-aggregation outputs DAV-164
mentions) is DAV-180's job — publishing through the Analysis Layer 2 output contract
(`06-output-contract.md`) needs its own provenance/confidence shape, not a resolver-internal sum.
This file only aggregates *within* one meal.

Writing resolved values onto a real `meal_items` row (vs. returning them for a caller to persist),
food-name search/candidate matching, and wiring this into `AddEntrySheet.kt`'s `FoodForm` are
DAV-166/167/168's job — this ticket is the calculation layer they all sit on top of.
