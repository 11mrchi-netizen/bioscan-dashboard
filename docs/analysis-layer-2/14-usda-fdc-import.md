# 14 — USDA FoodData Central import

**Linear:** [DAV-162](https://linear.app/biodashboard/issue/DAV-162) · **Status:** importer built, starter subset live

## Scope decision

USDA FoodData Central has a free live REST API, but this ticket deliberately imports instead of
querying it at request time — the ticket's own stated reason: nutrition resolution (DAV-164)
shouldn't depend on a repeated external API call, and an import lets a specific dataset *version* be
pinned for reproducible provenance instead of "whatever the live API returns today."

Only **SR Legacy** (7,793 generic/whole foods, final release, April 2018) and **Foundation Foods**
(469 foods, actively updated, April 2026) are imported. USDA's much larger **Branded Foods** dataset
(400,000+ packaged products) is deliberately excluded — DAV-165's Open Food Facts barcode lookup
already covers packaged/branded products live, so importing Branded Foods too would duplicate that
coverage while using most of a free-tier Supabase project's 500MB storage cap for no resolver
benefit the barcode path doesn't already provide.

## The importer: `scripts/nutrition/import_usda_fdc.py`

Reads a downloaded, unzipped USDA CSV release (`food.csv`, `food_nutrient.csv`, `food_portion.csv`,
`food_category.csv`, `measure_unit.csv`) and emits idempotent SQL:

- **foods**: upsert on `(food_source_id, source_food_id)` — re-running against a refreshed dataset
  release updates existing rows rather than creating duplicate canonical foods.
- **food_nutrients**: only the 10 nutrients `domain/NutritionResolver.kt` actually reads today
  (calories/protein/fat/carbs/fiber/sugar/sodium/water/caffeine/alcohol), mapped from USDA's
  nutrient IDs — not USDA's full ~150-nutrient panel. Widening this is a one-line addition to
  `canonical_nutrient_map()` plus a re-run, never a schema change (`food_nutrients.nutrient` is
  free-text by design, per DAV-161). Upserts on `(food_id, nutrient)`.
- **food_servings**: built from `food_portion.csv`'s `modifier`/`gram_weight` columns (USDA's real
  serving descriptions, e.g. "1 cup", "3 oz"). Upserts on `(food_id, serving_name)`.

Each USDA sub-dataset gets its own `food_sources` row (`usda_fdc_sr_legacy` /
`usda_fdc_foundation_foods`, via `--source-name`) — SR Legacy and Foundation Foods have independent
release dates, and sharing one `food_sources` row would let one sub-dataset's import silently
overwrite the other's version metadata (a real bug caught and fixed during this session: an earlier
draft used one shared `usda_fdc` source name for both).

Nutrient amounts are per 100g in both datasets (USDA's universal convention, including for liquids —
there is no separate per-100ml basis), so `basis_qty`/`basis_unit` are hardcoded to `100`/`'g'`.

### Refresh procedure

```bash
python3 scripts/nutrition/import_usda_fdc.py \
    --dataset-dir /path/to/FoodData_Central_sr_legacy_food_csv_<version> \
    --data-type sr_legacy_food --dataset-version <version> --release-date <date> \
    --sugar-nutrient-id 2000 --source-name usda_fdc_sr_legacy --out sr_legacy_import.sql

python3 scripts/nutrition/import_usda_fdc.py \
    --dataset-dir /path/to/FoodData_Central_foundation_food_csv_<version> \
    --data-type foundation_food --dataset-version <version> --release-date <date> \
    --sugar-nutrient-id 1063 --source-name usda_fdc_foundation_foods --out foundation_import.sql
```

Then run each `.sql` file against the project's Postgres connection (`psql "$SUPABASE_DB_URL" -f
<file>`, or the Supabase SQL editor). Foundation Foods' sugar nutrient ID (`1063`) differs from SR
Legacy's (`2000`) between dataset vintages — always confirm both via that release's own `nutrient.csv`
before importing, the way this session did (`grep -i "Sugars, Total" nutrient.csv`).

## What's actually live right now

Both datasets exist as real downloads (SR Legacy 6.7MB zipped, Foundation Foods 3.7MB zipped from
`https://fdc.nal.usda.gov/download-datasets`) and the importer ran against both in full. Only a
**50-food, evenly-stratified sample from each** (100 real USDA foods total, chosen via
`--max-foods`'s even-stride sampling across the dataset's natural category order, preserving
breadth) is actually loaded into Supabase — every `execute_sql` call in this session has to carry
its SQL as literal text through the assistant's own context, and the full two datasets
(8,262 foods / ~89k nutrient rows / ~25k serving rows) would cost roughly a million tokens to push
through that channel in one sitting. `--max-foods` exists specifically for this constraint, not as a
limitation of the importer itself — omit it (or set it high) for a complete import once a direct
`psql`/Supabase-SQL-editor connection is available, which costs nothing extra in either dollars or
tokens and takes seconds to run.

Combined with the 2 hand-entered manual fixtures from DAV-164, Supabase now holds **102 real,
resolver-correct foods** — verified by cross-checking `scaleNutrientToQuantity`'s exact output
(e.g. 250g of "Strawberries, raw" → 77.5 kcal / 1.6g protein / 227.75g water) directly in SQL against
the imported rows. Total database size after import: 21MB of a 500MB free-tier cap — importing the
complete 8,262+469 foods later would still land well under 100MB per the original estimate in
[12-beverage-hydration-caffeine-model.md](12-beverage-hydration-caffeine-model.md)'s sibling
discussion (see the milestone plan for the full sizing rationale).

## Acceptance criteria status

* Representative records import correctly — yes, cross-checked in SQL (see above).
* Nutrients retain source values and units — yes, USDA's own kcal/g/mg preserved unchanged.
* Source/version metadata attached — yes, two distinct `food_sources` rows.
* Re-running the importer is idempotent — yes, by construction (`ON CONFLICT ... DO UPDATE` on the
  same unique constraints DAV-161 already created).
* Common foods resolve through local search — yes, `foods.name` already has DAV-161's GIN full-text
  index; no separate alias generation was added since USDA doesn't supply alias data and the ticket's
  acceptance criterion is search working, not synonym expansion.
* Future dataset refresh procedure is documented — see above.
