# 10 — Nutrition data model

**Linear:** [DAV-161](https://linear.app/biodashboard/issue/DAV-161) · **Status:** migration applied

## Overview

Replaces the flat meal-level nutrition representation with a canonical food/item
decomposition model while preserving the existing `meals` table and its 96+ rows
intact. The new schema supports:

- **Dataset-backed food catalog** (USDA FoodData Central, Taiwan TFNCD, Open
  Food Facts, manual entries)
- **Per-food nutrient composition** with extensible micro/macronutrient coverage
- **Serving-size definitions** with gram/ml equivalence
- **Meal-item decomposition** (one meal contains multiple food items, each with
  quantity, preparation, confidence and provenance)
- **Raw input preservation** (text, image, barcode, voice inputs)
- **AI estimation audit trail** (model, prompt, response, acceptance)

## Entity-relationship diagram

```
food_sources 1──* foods 1──* food_nutrients
                       1──* food_servings
                                │
meals 1──* meal_items *──1 foods (nullable)
      1──* meal_inputs            │
                         food_servings (nullable)
      1──* ai_estimates ──* meal_items (nullable)
                        ──* meal_inputs (nullable)
```

## Tables

### food_sources
Dataset registry. One row per imported dataset or manual-entry origin.

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | auto-generated |
| name | text UNIQUE | e.g. "usda_fdc", "tfncd", "openfoodfacts", "manual" |
| version | text | dataset version/release identifier |
| release_date | date | upstream release date |
| license | text | license identifier |
| attribution | text | required attribution text |
| url | text | upstream URL |
| country | text | primary country coverage |
| language | text | default 'en' |

**RLS:** readable by all authenticated users (reference data).

### foods
Canonical food entries linked to a data source.

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | auto-generated |
| food_source_id | bigint FK | → food_sources.id |
| source_food_id | text | upstream identifier (e.g. USDA FDC ID) |
| name | text | English/primary name |
| name_zh | text | Chinese name (for Taiwan DB) |
| aliases | text[] | alternative names |
| brand | text | for packaged foods |
| barcode | text | EAN/UPC |
| category | text | top-level category |
| subcategory | text | |
| preparation | text | default preparation method |
| is_composite | boolean | true for recipes/composite foods |

**Indexes:** GIN on name (full-text), btree on barcode, food_source_id, category.
**Unique:** (food_source_id, source_food_id).
**RLS:** readable by all authenticated users.

### food_nutrients
Per-food nutrient data. Amounts per `basis_qty` `basis_unit` (default: per 100g).

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | |
| food_id | bigint FK | → foods.id ON DELETE CASCADE |
| nutrient | text | canonical name (e.g. "calories", "protein", "fat") |
| amount | numeric | amount per basis |
| unit | text | "kcal", "g", "mg", "mcg" |
| basis_qty | numeric | default 100 |
| basis_unit | text | default 'g' |

**Unique:** (food_id, nutrient).

### food_servings
Serving-size definitions per food.

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | |
| food_id | bigint FK | → foods.id ON DELETE CASCADE |
| serving_name | text | e.g. "1 cup cooked", "1 slice" |
| grams | numeric | gram equivalent (nullable if ml given) |
| ml | numeric | ml equivalent (nullable if grams given) |

**Constraint:** at least one of grams/ml must be non-null.

### meal_items
Individual food items within a meal. This is the core decomposition layer.

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | |
| meal_id | bigint FK | → meals.id ON DELETE CASCADE |
| user_id | uuid FK | → auth.users, default auth.uid() |
| food_id | bigint FK | → foods.id (nullable for unresolved items) |
| sort_order | smallint | display order within the meal |
| description | text | free-text item description |
| quantity | numeric | consumed amount |
| quantity_unit | text | default 'g' |
| quantity_low | numeric | lower bound (for estimated items) |
| quantity_high | numeric | upper bound |
| serving_id | bigint FK | → food_servings.id (nullable) |
| serving_count | numeric | number of servings |
| preparation | text | how this item was prepared |
| calories | numeric | calculated total for this item at this quantity |
| protein_g | numeric | |
| fat_g | numeric | |
| carbs_g | numeric | |
| fiber_g | numeric | |
| sugar_g | numeric | |
| sodium_mg | numeric | |
| is_estimated | boolean | true if AI-estimated |
| confidence | numeric | 0-1, null if not estimated |
| source | text | 'manual', 'ai', 'barcode' |

**RLS:** owner-scoped (user_id = auth.uid()).

### meal_inputs
Raw user inputs that led to a meal being logged.

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | |
| meal_id | bigint FK | → meals.id ON DELETE CASCADE |
| user_id | uuid FK | default auth.uid() |
| input_type | text | CHECK: 'text', 'image', 'barcode', 'voice' |
| raw_text | text | original text input |
| image_path | text | Supabase Storage path |
| barcode_value | text | scanned barcode |
| barcode_format | text | EAN-13, UPC-A, etc. |

### ai_estimates
Audit trail for AI-generated nutrition estimates.

| Column | Type | Notes |
|--------|------|-------|
| id | bigint PK | |
| meal_id | bigint FK | → meals.id ON DELETE SET NULL |
| meal_item_id | bigint FK | → meal_items.id ON DELETE SET NULL |
| meal_input_id | bigint FK | → meal_inputs.id ON DELETE SET NULL |
| user_id | uuid FK | default auth.uid() |
| model | text | e.g. "gemini-2.0-flash" |
| model_version | text | |
| prompt_text | text | |
| prompt_image_path | text | |
| raw_response | jsonb | full API response |
| parsed_output | jsonb | structured extraction |
| latency_ms | integer | |
| accepted | boolean | whether user accepted the estimate |

## Backward compatibility

The existing `meals` table is **unchanged**. Its meal-level nutrition columns
(`calories`, `protein_g`, `fat_g`, `carbs_g`, `fiber_g`, `sugar_g`, `sodium_mg`)
continue to serve as the authoritative totals until `meal_items` are populated
for a given meal.

The query pattern for downstream consumers (NutritionRepository, NutritionEvaluation,
the web dashboard) remains:
```sql
SELECT logged_at, calories, protein_g, fat_g, carbs_g, fiber_g, sugar_g, sodium_mg
FROM meals WHERE user_id = auth.uid()
```

Once meal_items are populated, a transition query computes totals from items:
```sql
SELECT m.id, m.logged_at,
       COALESCE(SUM(mi.calories), m.calories) AS calories,
       COALESCE(SUM(mi.protein_g), m.protein_g) AS protein_g,
       COALESCE(SUM(mi.fat_g), m.fat_g) AS fat_g,
       COALESCE(SUM(mi.carbs_g), m.carbs_g) AS carbs_g,
       COALESCE(SUM(mi.fiber_g), m.fiber_g) AS fiber_g,
       COALESCE(SUM(mi.sugar_g), m.sugar_g) AS sugar_g,
       COALESCE(SUM(mi.sodium_mg), m.sodium_mg) AS sodium_mg
FROM meals m
LEFT JOIN meal_items mi ON mi.meal_id = m.id
GROUP BY m.id, m.logged_at, m.calories, m.protein_g, m.fat_g,
         m.carbs_g, m.fiber_g, m.sugar_g, m.sodium_mg
```

## Provenance chain

```
meal_input (raw text/image/barcode)
  → ai_estimate (Gemini processing)
    → meal_item (resolved food + quantity + calculated nutrients)
      → food (canonical entry)
        → food_source (dataset origin)
          → food_nutrients (per-100g composition)
```

Every step in the chain is preserved and auditable.
