# 12 — Beverage, fluid, hydration and caffeine model

**Linear:** [DAV-181](https://linear.app/biodashboard/issue/DAV-181) · **Status:** migration applied

## Overview

Extends DAV-161's canonical nutrition schema so beverages are first-class items feeding Nutrition,
Hydration and Caffeine views from one record, instead of `hydration_daily`'s separate water-only
daily counter (which stays untouched, for the same backward-compatibility reason DAV-161 left
`meals` alone).

Three additions:

- **Beverage classification** on `foods` (`beverage_class`/`beverage_subtype`), null for non-beverage
  foods.
- **Composition** (water/caffeine/alcohol content) reuses `food_nutrients`' existing extensible
  `nutrient` vocabulary — no schema change needed. Canonical names: `water`, `caffeine`, `alcohol`
  (alongside the existing `sodium`), amount per `basis_qty`/`basis_unit` like every other nutrient.
- **Per-consumption beverage fields** on `meal_items`, computed by the resolver (DAV-164) at log
  time, plus a new **`hydration_factor_models`** reference table holding versioned effective-
  hydration retention factors per beverage class.

## `hydration_factor_models`

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | |
| model_version | text | e.g. "v1" |
| beverage_class | text | matches `foods.beverage_class` |
| retention_factor | numeric | multiplier applied to `water_ml` to get `effective_hydration_ml` |
| notes | text | rationale/evidence, kept alongside the number it justifies |

**Unique:** (model_version, beverage_class). **RLS:** readable by all authenticated users (reference
data, same shape as `food_sources`).

Seeded `v1` values (approximate, literature-informed starting points — the point of versioning them
here is that they can be revised without an app release, not that v1 is precise):

| beverage_class | retention_factor |
|---|---|
| water | 1.00 |
| electrolyte | 1.03 |
| milk / plant_milk | 1.00 |
| juice | 0.90 |
| tea | 0.99 |
| coffee | 0.98 |
| soda | 0.95 |
| alcohol | 0.60 |

## New `meal_items` columns

| Column | Type | Notes |
|--------|------|-------|
| is_beverage | boolean | set at logging time, even before `food_id` resolution |
| water_ml | numeric | actual water content of the consumed quantity — derived from composition, distinct from the nominal consumed volume already carried by `quantity`/`quantity_unit` when unit = 'ml' |
| caffeine_mg | numeric | independent of water_ml/effective_hydration_ml — never subtracted from fluid |
| alcohol_g | numeric | |
| effective_hydration_ml | numeric | derived estimate (`water_ml × hydration_factor_models.retention_factor`), never presented as measured |
| hydration_model_version | text | which `hydration_factor_models.model_version` produced `effective_hydration_ml` |
| hydration_confidence | numeric (0–1) | confidence in the hydration estimate specifically — independent of the existing `confidence` column, which describes AI food-identification confidence |

No `volume_ml` column was added: a beverage's consumed volume is the existing `quantity` column
with `quantity_unit = 'ml'`, so a separate field would just duplicate it.

## Why not a new table per beverage attribute

Composition (water/caffeine/alcohol) fits `food_nutrients`' existing per-100g/100ml shape exactly —
a new `nutrient` value is data, not schema. Only the *retention factor* (a property of a beverage
*class*, evolving with evidence, not of any one food) warranted a dedicated table.

## Dependents

DAV-164 (deterministic resolver) is what actually computes and writes `water_ml`/`caffeine_mg`/
`effective_hydration_ml`/`hydration_model_version` on a `meal_items` row at log time — this schema
only defines where those values live. DAV-166/167 (Gemini logging) and DAV-165 (barcode lookup) can
now populate `foods.beverage_class`/`beverage_subtype` and beverage `food_nutrients` rows for the
beverages they resolve.
