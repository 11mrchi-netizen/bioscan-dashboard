#!/usr/bin/env python3
"""DAV-162: repeatable importer for USDA FoodData Central CSV releases into
this project's canonical foods/food_nutrients/food_servings schema (DAV-161).

Scope note: only SR Legacy and Foundation Foods are imported here -- USDA's
much larger Branded Foods dataset (400k+ packaged products) is deliberately
excluded, since DAV-165's Open Food Facts barcode lookup already covers
packaged/branded products live. Importing it too would duplicate that
coverage and blow well past this project's Supabase free-tier storage for no
resolver benefit.

Idempotent: foods upserts on (food_source_id, source_food_id), food_nutrients
on (food_id, nutrient), food_servings on (food_id, serving_name) -- all three
constraints already exist from DAV-161/162's migrations. Re-running this
importer against a refreshed dataset release updates existing rows in place
rather than creating duplicate canonical foods.

Usage (repeat per dataset -- run once now for the current releases, and again
whenever USDA publishes a new one):

    python3 import_usda_fdc.py \\
        --dataset-dir /path/to/FoodData_Central_sr_legacy_food_csv_2018-04 \\
        --data-type sr_legacy_food \\
        --dataset-version 2018-04 \\
        --release-date 2018-04-01 \\
        --sugar-nutrient-id 2000 \\
        --source-name usda_fdc_sr_legacy \\
        --out sr_legacy_import.sql

    python3 import_usda_fdc.py \\
        --dataset-dir /path/to/FoodData_Central_foundation_food_csv_2026-04-30 \\
        --data-type foundation_food \\
        --dataset-version 2026-04-30 \\
        --release-date 2026-04-30 \\
        --sugar-nutrient-id 1063 \\
        --source-name usda_fdc_foundation_foods \\
        --out foundation_import.sql

This writes SQL, it does not execute it -- run the output against the
project's Postgres connection (e.g. `psql "$SUPABASE_DB_URL" -f out.sql`, or
paste it into the Supabase SQL editor / MCP `execute_sql` for a Claude Code
session already holding a project connection) in batches if your connection
has a statement-size limit; BATCH_SIZE below controls how many VALUES rows
land in one INSERT.
"""

import argparse
import csv
import os

BATCH_SIZE = 1000

# Canonical food_nutrients.nutrient name -> (USDA nutrient_id, real unit).
# Only the nutrients domain/NutritionResolver.kt actually reads today --
# widening this later to more of USDA's ~150-nutrient panel is a one-line
# addition here plus a re-run, never a schema change (DAV-161's
# food_nutrients.nutrient is free-text by design).
def canonical_nutrient_map(sugar_nutrient_id: str) -> dict[str, tuple[str, str]]:
    return {
        "1008": ("calories", "kcal"),
        "1003": ("protein", "g"),
        "1004": ("fat", "g"),
        "1005": ("carbs", "g"),
        "1079": ("fiber", "g"),
        sugar_nutrient_id: ("sugar", "g"),
        "1093": ("sodium", "mg"),
        "1051": ("water", "g"),
        "1057": ("caffeine", "mg"),
        "1018": ("alcohol", "g"),
    }


def sql_str(value: str | None) -> str:
    if value is None or value == "":
        return "NULL"
    return "'" + value.replace("'", "''") + "'"


def sql_num(value: str | None) -> str:
    if value is None or value == "":
        return "NULL"
    return value


def load_food_category_names(dataset_dir: str) -> dict[str, str]:
    path = os.path.join(dataset_dir, "food_category.csv")
    names: dict[str, str] = {}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            names[row["id"]] = row["description"]
    return names


def load_measure_unit_names(dataset_dir: str) -> dict[str, str]:
    path = os.path.join(dataset_dir, "measure_unit.csv")
    names: dict[str, str] = {}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            names[row["id"]] = row["name"]
    return names


def load_foods(dataset_dir: str, data_type: str, category_names: dict[str, str], max_foods: int | None) -> dict[str, dict]:
    path = os.path.join(dataset_dir, "food.csv")
    matched: list[tuple[str, dict]] = []
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["data_type"] != data_type:
                continue
            matched.append((row["fdc_id"], {
                "name": row["description"],
                "category": category_names.get(row["food_category_id"]),
            }))
    if max_foods is None or len(matched) <= max_foods:
        return dict(matched)
    # Even stride across the file's natural order (USDA groups rows by
    # category), not a prefix slice -- keeps category diversity in a capped
    # run instead of only importing whatever categories sort first.
    stride = len(matched) / max_foods
    sampled = [matched[int(i * stride)] for i in range(max_foods)]
    return dict(sampled)


def load_nutrients(dataset_dir: str, fdc_ids: set[str], nutrient_map: dict[str, tuple[str, str]]) -> dict[str, list[tuple[str, float, str]]]:
    path = os.path.join(dataset_dir, "food_nutrient.csv")
    by_food: dict[str, list[tuple[str, float, str]]] = {}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            fdc_id = row["fdc_id"]
            mapped = nutrient_map.get(row["nutrient_id"])
            if fdc_id not in fdc_ids or mapped is None or row["amount"] == "":
                continue
            nutrient_name, unit = mapped
            by_food.setdefault(fdc_id, []).append((nutrient_name, float(row["amount"]), unit))
    return by_food


def load_servings(dataset_dir: str, fdc_ids: set[str], unit_names: dict[str, str]) -> dict[str, list[tuple[str, float]]]:
    path = os.path.join(dataset_dir, "food_portion.csv")
    by_food: dict[str, list[tuple[str, float]]] = {}
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            fdc_id = row["fdc_id"]
            if fdc_id not in fdc_ids or row["gram_weight"] == "":
                continue
            amount = row["amount"] or "1"
            modifier = row["modifier"].strip()
            description = row["portion_description"].strip()
            unit_name = unit_names.get(row["measure_unit_id"], "")
            if modifier:
                serving_name = f"{amount} {modifier}".strip()
            elif description:
                serving_name = description
            else:
                serving_name = f"{amount} {unit_name}".strip()
            by_food.setdefault(fdc_id, []).append((serving_name, float(row["gram_weight"])))
    return by_food


def write_sql(
    out_path: str,
    source_name: str,
    dataset_version: str,
    release_date: str,
    foods: dict[str, dict],
    nutrients: dict[str, list[tuple[str, float, str]]],
    servings: dict[str, list[tuple[str, float]]],
) -> None:
    fdc_ids = list(foods.keys())
    with open(out_path, "w", encoding="utf-8") as out:
        out.write(
            "insert into public.food_sources (name, version, release_date, license, attribution, url, country, language)\n"
            f"values ('{source_name}', {sql_str(dataset_version)}, {sql_str(release_date)}, "
            "'Public domain (U.S. Government Work)', 'U.S. Department of Agriculture, Agricultural Research Service. "
            "FoodData Central.', 'https://fdc.nal.usda.gov', 'US', 'en')\n"
            "on conflict (name) do update set version = excluded.version, release_date = excluded.release_date;\n\n"
        )

        for start in range(0, len(fdc_ids), BATCH_SIZE):
            batch = fdc_ids[start : start + BATCH_SIZE]
            values = ",\n  ".join(
                f"((select id from public.food_sources where name = '{source_name}'), "
                f"{sql_str(fid)}, {sql_str(foods[fid]['name'])}, {sql_str(foods[fid]['category'])})"
                for fid in batch
            )
            out.write(
                "insert into public.foods (food_source_id, source_food_id, name, category)\n"
                f"values\n  {values}\n"
                "on conflict (food_source_id, source_food_id) do update\n"
                "  set name = excluded.name, category = excluded.category, updated_at = now();\n\n"
            )

        nutrient_rows = [(fid, n, amt, unit) for fid in fdc_ids for n, amt, unit in nutrients.get(fid, [])]
        for start in range(0, len(nutrient_rows), BATCH_SIZE):
            batch = nutrient_rows[start : start + BATCH_SIZE]
            values = ",\n  ".join(f"({sql_str(fid)}, {sql_str(n)}, {amt}, {sql_str(unit)})" for fid, n, amt, unit in batch)
            out.write(
                "insert into public.food_nutrients (food_id, nutrient, amount, unit, basis_qty, basis_unit)\n"
                "select f.id, v.nutrient, v.amount, v.unit, 100, 'g'\n"
                f"from (values\n  {values}\n) as v(source_food_id, nutrient, amount, unit)\n"
                f"join public.foods f on f.food_source_id = (select id from public.food_sources where name = '{source_name}')\n"
                "  and f.source_food_id = v.source_food_id\n"
                "on conflict (food_id, nutrient) do update set amount = excluded.amount, unit = excluded.unit;\n\n"
            )

        serving_rows = [(fid, name, grams) for fid in fdc_ids for name, grams in servings.get(fid, [])]
        for start in range(0, len(serving_rows), BATCH_SIZE):
            batch = serving_rows[start : start + BATCH_SIZE]
            values = ",\n  ".join(f"({sql_str(fid)}, {sql_str(name)}, {grams})" for fid, name, grams in batch)
            out.write(
                "insert into public.food_servings (food_id, serving_name, grams)\n"
                "select f.id, v.serving_name, v.grams\n"
                f"from (values\n  {values}\n) as v(source_food_id, serving_name, grams)\n"
                f"join public.foods f on f.food_source_id = (select id from public.food_sources where name = '{source_name}')\n"
                "  and f.source_food_id = v.source_food_id\n"
                "on conflict (food_id, serving_name) do update set grams = excluded.grams;\n\n"
            )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-dir", required=True)
    parser.add_argument("--data-type", required=True, choices=["sr_legacy_food", "foundation_food"])
    parser.add_argument("--dataset-version", required=True)
    parser.add_argument("--release-date", required=True)
    parser.add_argument("--sugar-nutrient-id", required=True, help="2000 for SR Legacy, 1063 for Foundation Foods")
    parser.add_argument(
        "--source-name",
        required=True,
        help="food_sources.name for this run, e.g. usda_fdc_sr_legacy / usda_fdc_foundation_foods. "
        "Must be distinct per USDA sub-dataset -- they have independent release dates/versions, and "
        "sharing one food_sources row would let a later import silently overwrite an earlier "
        "sub-dataset's version metadata.",
    )
    parser.add_argument("--out", required=True)
    parser.add_argument(
        "--max-foods",
        type=int,
        default=None,
        help="Cap on foods imported, evenly sampled across the dataset's natural category order. "
        "Omit for a full import -- the cap exists only for execution channels with a size limit, "
        "not a limitation of the importer itself.",
    )
    args = parser.parse_args()

    category_names = load_food_category_names(args.dataset_dir)
    unit_names = load_measure_unit_names(args.dataset_dir)
    foods = load_foods(args.dataset_dir, args.data_type, category_names, args.max_foods)
    nutrients = load_nutrients(args.dataset_dir, set(foods.keys()), canonical_nutrient_map(args.sugar_nutrient_id))
    servings = load_servings(args.dataset_dir, set(foods.keys()), unit_names)

    write_sql(args.out, args.source_name, args.dataset_version, args.release_date, foods, nutrients, servings)
    print(f"{len(foods)} foods, {sum(len(v) for v in nutrients.values())} nutrient rows, "
          f"{sum(len(v) for v in servings.values())} serving rows -> {args.out}")


if __name__ == "__main__":
    main()
