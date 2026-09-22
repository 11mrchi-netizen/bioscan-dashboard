# 15 — Barcode lookup and Gemini candidate extraction

**Linear:** [DAV-165](https://linear.app/biodashboard/issue/DAV-165) · [DAV-166](https://linear.app/biodashboard/issue/DAV-166) · **Status:** edge functions deployed, Android repositories added

## Overview

Two Supabase Edge Functions, both producing *candidates* for DAV-164's deterministic resolver to
turn into real nutrient totals — neither is ever the authoritative source for a nutrient value:

- **`nutrition-barcode-lookup`** (DAV-165): server-side Open Food Facts lookup, with `foods` itself
  acting as the cache.
- **`nutrition-estimate-text`** (DAV-166): server-side Gemini call that extracts structured food/
  beverage candidates from natural language — never nutrient amounts.

## Why server-side, when this app's other Gemini features aren't

`NutritionEstimationRepository`, `LabExtractionRepository`, and `SupplementImpactRepository` all
call Gemini directly from the Android client, using a key the user pastes into Settings
(`GeminiApiKeyStore`). DAV-166 explicitly requires the key stay server-side for this new flow — a
real architecture difference from the rest of the app, not an oversight. Only this new
candidate-extraction path moves server-side; the existing three features are untouched, since no
ticket asks for that and their existing key-handling isn't broken.

`nutrition-estimate-text` holds `GEMINI_API_KEY` as a Supabase Edge Function secret — **this has to
be set manually** (`supabase secrets set GEMINI_API_KEY=<key>`, or via the Supabase dashboard's
Edge Functions → Secrets page); no tool in this session's toolset can set Edge Function secrets, so
the function will return `{"error": "not_configured"}` until that's done once.

## Why the reference tables need the service-role key

`food_sources`/`foods`/`food_nutrients`/`food_servings` are SELECT-only for authenticated users
under RLS (confirmed directly against `pg_policies` — no INSERT policy exists on any of them, by
design: an ordinary user shouldn't be able to write into the shared food catalog). Both functions
therefore use two Supabase clients, the same dual-client pattern the existing `quick-log` function
already established:

1. An anon-key client, with the caller's own `Authorization` header, used only to confirm a real
   session exists (`auth.getUser`) — this is what stops an anonymous caller from spamming the Open
   Food Facts proxy or the Gemini quota.
2. A service-role client for every actual read/write against the reference tables.

## `nutrition-barcode-lookup`

1. Cache check: `foods` where `food_source_id` = the `openfoodfacts` source and `source_food_id` =
   the barcode — the same `(food_source_id, source_food_id)` unique constraint every other source
   uses (DAV-161), so a repeated scan never re-queries Open Food Facts.
2. On a miss, calls `GET https://world.openfoodfacts.org/api/v2/product/{barcode}.json` (free, no
   key) and maps its `nutriments` fields to the canonical vocabulary
   (`energy-kcal_100g`→`calories`, `proteins_100g`→`protein`, etc.), converting Open Food Facts'
   sodium/caffeine (grams) to the schema's milligrams.
3. **Liquid vs. mass, verified against real data**: Open Food Facts' `_100g`-suffixed nutriment
   field names are misleading for liquids — a real Coca-Cola lookup confirms its own
   `product_quantity_unit` field reports `"ml"`, and its numbers are genuinely per 100ml, not per
   100g. The function reads `product_quantity_unit`/`quantity_unit` to set `food_nutrients.basis_unit`
   correctly, and puts a liquid product's `serving_quantity` into `food_servings.ml`, never `grams`
   — an actual bug caught and fixed while cross-checking against a real product during this
   session, not a hypothetical concern.
4. Beverage classification: a conservative substring match against Open Food Facts'
   `categories_tags` (e.g. `en:sodas` → `soda`) into DAV-181's `beverage_class` vocabulary. An
   unmatched product stays non-beverage rather than guessed.
5. A barcode Open Food Facts doesn't recognize returns `{"found": false}` — never a fabricated
   canonical food.

## `nutrition-estimate-text`

Sends the user's raw text plus a structured-output schema (Gemini's `response_schema`, the same
mechanism `NutritionEstimationRepository.kt` already uses) asking for an array of candidates:
description, quantity (explicit value+unit, or a low/high range for vague amounts), preparation,
beverage classification, an explicit-only caffeine amount, and two independent confidence scores
(food identity vs. portion) plus an `ambiguous` flag. The prompt explicitly forbids nutrient
amounts in the response.

Every call — success or failure — persists an `ai_estimates` row (model, version, prompt text, raw
response, parsed output, latency) for DAV-166's "persist structured AI estimates for
reproducibility" requirement. `meal_id`/`meal_item_id`/`meal_input_id` are all nullable on that
table (DAV-161), so the row can exist before any meal does — DAV-168's review/save step is expected
to link it back once a real `meal_item` exists from the candidate.

## Android side

`data/NutritionBarcodeLookupRepository.kt` and `data/NutritionTextEstimateRepository.kt` — plain
Ktor POST calls carrying the current session's JWT (`supabase.auth.currentAccessTokenOrNull()`),
matching this app's existing Gemini-call shape (`GeminiClient.kt`) rather than adding the
`functions-kt` SDK module for two endpoints. Neither is wired into `AddEntrySheet.kt` yet — that
UI integration, plus turning a chosen candidate into an actual `meal_item` via DAV-164's resolver,
is DAV-168's job.

## What's deliberately not here

- Food-name search (candidate description → an actual `foods.id`) — DAV-168's review step matches
  a candidate to a canonical food (via `foods`' existing GIN full-text index), lets the user
  confirm/correct that match, and only then calls the resolver.
- Image-based Gemini estimation (DAV-167) — same server-side pattern, a separate ticket.
- Any UI. Both repositories are ready for DAV-168 to call; nothing in `AddEntrySheet.kt` changed.
