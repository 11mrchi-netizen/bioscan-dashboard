# 16 — Nutrition review, correction and recurring-meal UX

**Linear:** [DAV-168](https://linear.app/biodashboard/issue/DAV-168) · **Status:** live, verified end-to-end on-device

## Overview

The step where DAV-164/165/166/167 finally connect to the UI. `AddEntrySheet.kt`'s `FoodForm`
gained a "LOG WITH AI OR BARCODE" section (photo/description/barcode) that opens
`ui/screens/NutritionCandidateReviewSheet.kt` instead of pre-filling flat macro fields — the old
`NutritionEstimationRepository` client-side-Gemini path it used to call is now dead code and was
deleted (along with `domain/FoodEstimate.kt`), since nothing calls it anymore.

## Scope decisions (stated in the approved plan, restated here for the record)

- **No camera barcode scanning.** Barcode entry is a manual numeric field. DAV-165's acceptance
  criteria only require resolution, not a scanning UI; ML Kit/ZXing camera scanning is real,
  separate future work.
- **The old flat manual-entry fields in `FoodForm` stay**, untouched, for plain manual logging
  without AI.
- **No personalization/synonym-memory table.** The shared `foods` catalog itself is what future
  searches benefit from once a food is matched once — no new mapping table recording
  "Gemini said X, user picked Y."

## The review sheet (`ReviewSeedItem` → `ConfirmedMealItem`)

`ReviewSeedItem` is the one shape all three candidate sources normalize into (text/image
candidates, or a barcode's single pre-matched food) before ever reaching the review UI — the sheet
itself has no source-specific branching. Per item:

- A debounced `ilike` search (`NutritionFoodSearchRepository`, matching `PeopleRepository.kt`'s
  existing search convention) against `foods.name`, pre-seeded from the candidate's own
  description.
- Once matched, a live `NutritionResolverRepository.resolveMealItem` call recomputes totals on
  every food/quantity/serving change — there is no cached total to go stale, every edit is a fresh
  resolver call.
- A quantity field (raw grams/ml) plus, once a food is matched, chip buttons for that food's own
  `food_servings` — tapping one switches the field's meaning from "grams/ml" to "serving count."
- Confidence badges (`FOOD n%` / `PORTION n%`) and an "NEEDS CONFIRMATION" flag surface Gemini's own
  uncertainty rather than hiding it.

`NutritionMealSaveRepository.saveMeal` resolves every confirmed item, writes one `meals` row (flat
totals = the resolved sum) + one `meal_items` row per item, then links any originating
`ai_estimates` row's `meal_id`/`meal_item_id` back — `ai_estimates.parsed_output` is never mutated,
so the delta between it and the saved `meal_items` row **is** the correction record, satisfying
DAV-168's "corrections stored separately from original AI output" without a second table.
`cloneMeal` (the "REPEAT A RECENT MEAL" action) duplicates a source meal's `meal_items` under a new
`meals` row, reusing the same `food_id`/`serving_id` values — verified live that this creates zero
new `foods` rows.

## Three real bugs, caught only by live on-device testing

Unit tests and SQL cross-checks (DAV-164) couldn't have caught any of these — all three are about
what happens when Kotlin/kotlinx.serialization decode real Supabase responses, not about the
nutrition math itself:

1. **`NutritionResolverRepository.resolveMealItem`'s `foods` select omitted `name`.** `FoodRow.name`
   is non-nullable; a partial select missing a required field decodes fine as raw JSON but throws
   `decodeSingle<FoodRow>()` with *"Field 'name' is required... but it was missing"*. Selecting
   exactly the columns a function *uses* isn't safe when the target type has other required fields
   — fixed by including every non-nullable field the shared row model declares, not just the ones a
   given call site reads.
2. **`NutritionMealSaveRepository.cloneMeal`'s `meals` select omitted `logged_at`.** Identical root
   cause, same fix, same lesson — caught independently while testing "repeat a recent meal," since
   it's a different query hitting the same class of bug.
3. **The `ai_estimates` link-back update used a raw `mapOf(...)`.** kotlinx.serialization can't
   serialize a heterogeneously-typed `Map<String, Any>` without a registered polymorphic serializer
   — *"Serializer for class 'Any' is not found."* Fixed with a real `@Serializable
   AiEstimateLinkUpdate` data class. This one had a real data-integrity consequence beyond the
   crash: the failure happened *after* the meal's flat totals were already computed from all
   resolved items but *midway* through inserting individual `meal_items` rows, so the first live
   test run left a `meals` row whose flat totals didn't match its (incomplete) `meal_items` — caught
   and the test data cleaned up, but a real illustration of why "throws after partial writes" is
   worth noticing even when the immediate symptom is just a crashed UI.

All three were reproduced, fixed, and re-verified live (real Gemini text estimate → real catalog
search match → live-recalculating totals → real `meal_items` row; real barcode lookup for a live
Coca-Cola UPC → correct `ml`-basis resolution → real save; real meal clone → zero duplicate `foods`
rows) before this ticket was considered done.

## What's deliberately not here

- Taiwan-database-specific search/matching (DAV-163, not yet imported).
- Validation dataset / regression tests against real meal descriptions (DAV-169).
- Health Connect write-back for a finalized meal (DAV-170).
- Publishing nutrition metrics to Analysis Layer 2 (DAV-180).
