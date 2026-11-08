#!/usr/bin/env python3
"""Real drinks for the drink log: the ready-to-drink rows of USDA FoodData Central SR Legacy and of Taiwan FDA's food
nutrient database (食品營養成分資料庫), each tagged with its beverage_class (the hydration_factor_models vocabulary)
and a beverage_subtype, with nutrients per 100 g and servings in both grams and ml.

Writes SQL, does not run it (same as import_usda_fdc.py): run the output against the project's database in batches.

    python3 import_drinks.py \\
        --usda-dir /path/to/FoodData_Central_sr_legacy_food_csv_2018-04 \\
        --tw-csv /path/to/20_2.csv --tw-version 2025-10 \\
        --out drinks_import.sql

Sources and what is taken:
  - USDA SR Legacy (public domain): Beverages, Alcoholic Beverages, fluid milks, ready-to-drink juices and soy milk.
    Powders, concentrates, mixes and syrups are left out unless the row is "prepared" (made up as drunk). Upserts into
    the existing usda_fdc_sr_legacy source, so a USDA row already in the catalog just gains its beverage_class.
  - Taiwan FDA (Open Government Data License, Taiwan): 飲料類 and the drinkable 乳品類 rows. Their table has no
    caffeine; tea and coffee get a per-100 ml caffeine estimate from the matching USDA brew (see TW_CAFFEINE), stored
    with food_nutrients.estimated = true so the app can say so.

Idempotent: foods upsert on (food_source_id, source_food_id), food_nutrients on (food_id, nutrient), food_servings on
(food_id, serving_name).
"""

import argparse
import csv
import os
import re

BATCH = 400
FL_OZ_ML = 29.5735
CUP_ML = 236.6

USDA_NUTRIENTS = {
    "1008": ("calories", "kcal"), "1003": ("protein", "g"), "1004": ("fat", "g"), "1005": ("carbs", "g"),
    "1079": ("fiber", "g"), "2000": ("sugar", "g"), "1093": ("sodium", "mg"), "1051": ("water", "g"),
    "1057": ("caffeine", "mg"), "1018": ("alcohol", "g"),
}

# Rows that are not drunk as they are.
NOT_AS_DRUNK = re.compile(
    r"\b(powder|powdered|dry|dried|concentrate|mix|syrup|granules|unprepared|undiluted|crystals|beans|ground|leaves|"
    r"condensed|evaporated|cubes|base|instant, regular|from concentrate, unprepared)\b")
PREPARED = re.compile(r"\b(prepared|ready[- ]to[- ]drink|brewed|diluted|reconstituted|made with)\b")


def usda_class(name: str, category: str) -> tuple[str, str | None] | None:
    n = name.lower()
    if "non-alcoholic" in n or "nonalcoholic" in n:
        return ("soda", "non_alcoholic") if "beer" in n else ("juice", "non_alcoholic")
    if category == "Alcoholic Beverages" or n.startswith("alcoholic beverage") or "malt liquor" in n:
        if "beer" in n or "malt" in n:
            return "alcohol", "beer"
        if "wine" in n:
            return "alcohol", "wine"
        if any(w in n for w in ("liqueur", "pina colada", "daiquiri", "sour", "martini", "tequila sunrise", "mojito", "margarita", "cocktail")):
            return "alcohol", "cocktail"
        return "alcohol", "spirits"
    if "coffee" in n and "liqueur" not in n:
        return "coffee", "espresso" if "espresso" in n else ("latte" if "latte" in n or "milk" in n else None)
    if re.search(r"\btea\b|\bchai\b", n):
        return "tea", "herbal" if re.search(r"herb|chamomile|hibiscus|rooibos", n) else None
    if re.search(r"sports drink|powerade|gatorade|electrolyte|isotonic|rehydration", n):
        return "electrolyte", None
    if re.search(r"almond milk|rice milk|soymilk|soy milk|oat milk|coconut milk|silk ", n):
        return "plant_milk", None
    if re.search(r"energy drink|red bull|rockstar|monster|vault|full throttle|amp ", n):
        return "soda", "energy_drink"
    if re.search(r"lemonade|limeade|juice|nectar|coconut water", n) and "carbonated" not in n:
        return "juice", None
    if re.match(r"(beverages, )?water\b", n) or "mineral water" in n or re.search(r"water, bottled|bottled water|, water, bottled", n):
        return ("water", None) if not re.search(r"sweetened|flavor|vitamin|pomegranate|yumberry", n) else ("soda", "flavoured_water")
    if re.search(r"carbonated|\bcola\b|\bsoda\b|tonic|ginger ale|root beer|club soda|\bpepper\b|lemon-lime|seltzer", n):
        return "soda", None
    if n.startswith("milk,") or re.search(r"\bshake\b|kefir|buttermilk|eggnog|chocolate milk|dairy drink|ensure|slimfast|nutritional|protein|instant breakfast|ovaltine|malt|cocoa|hot chocolate", n):
        return "milk", None
    if re.search(r"juice|nectar|lemonade|punch|limeade|cider|drink|smoothie|cocktail", n):
        return "juice", None
    return None


def usda_drinks(d: str) -> tuple[dict, dict, dict]:
    cats = {r["id"]: r["description"] for r in csv.DictReader(open(os.path.join(d, "food_category.csv"), encoding="utf-8"))}
    foods = {}
    for r in csv.DictReader(open(os.path.join(d, "food.csv"), encoding="utf-8")):
        name, cat = r["description"], cats.get(r["food_category_id"], "")
        n = name.lower()
        drinkish = (
            cat in ("Beverages", "Alcoholic Beverages")
            or (cat == "Dairy and Egg Products" and n.startswith("milk,") and "fluid" in n or cat == "Dairy and Egg Products" and re.search(r"kefir|buttermilk, fluid|eggnog|milk shake|chocolate milk", n))
            or (cat == "Fruits and Fruit Juices" and re.search(r"juice|\bnectar\b", n))
            or (cat == "Legumes and Legume Products" and re.search(r"soymilk|soy milk", n))
            or (cat == "Vegetables and Vegetable Products" and re.search(r"juice", n))
        )
        if not drinkish:
            continue
        if NOT_AS_DRUNK.search(n) and not PREPARED.search(n):
            continue
        if re.search(r"\binstant\b", n) and not PREPARED.search(n):
            continue
        if re.search(r"juice pack|packed in|drained|heavy syrup|light syrup|\bhuman\b", n):
            continue
        cls = usda_class(name, cat)
        if cls is None:
            continue
        foods[r["fdc_id"]] = {"name": name, "category": cat, "class": cls[0], "subtype": cls[1], "aliases": []}
    nutrients: dict[str, dict[str, tuple[float, str]]] = {}
    for r in csv.DictReader(open(os.path.join(d, "food_nutrient.csv"), encoding="utf-8")):
        m = USDA_NUTRIENTS.get(r["nutrient_id"])
        if r["fdc_id"] in foods and m and r["amount"] != "":
            nutrients.setdefault(r["fdc_id"], {})[m[0]] = (float(r["amount"]), m[1])
    units = {r["id"]: r["name"] for r in csv.DictReader(open(os.path.join(d, "measure_unit.csv"), encoding="utf-8"))}
    servings: dict[str, list[tuple[str, float, float | None]]] = {}
    density: dict[str, float] = {}
    raw: dict[str, list[tuple[str, float, float | None]]] = {}
    for r in csv.DictReader(open(os.path.join(d, "food_portion.csv"), encoding="utf-8")):
        fid = r["fdc_id"]
        if fid not in foods or r["gram_weight"] == "":
            continue
        amount = float(r["amount"] or 1) or 1.0
        mod = (r["modifier"] or r["portion_description"] or units.get(r["measure_unit_id"], "")).strip()
        grams = float(r["gram_weight"])
        ml = None
        # A size spelled out in the label wins ("1 can (6.8 fl oz, 200 ml)", "1 serving 5 fl oz"): the count is of that.
        stated_ml = re.search(r"(\d+(?:\.\d+)?)\s*ml\b", mod)
        stated_oz = re.search(r"(\d+(?:\.\d+)?)\s*fl oz", mod)
        if stated_ml:
            ml = float(stated_ml.group(1)) * (amount if amount > 1 else 1)
        elif stated_oz:
            ml = float(stated_oz.group(1)) * FL_OZ_ML * (amount if amount > 1 else 1)
        elif re.search(r"fl oz", mod):
            ml = amount * FL_OZ_ML
        elif re.fullmatch(r"cup.*", mod) or mod == "cup":
            ml = amount * CUP_ML
        raw.setdefault(fid, []).append((f"{r['amount'] or 1} {mod}".strip(), grams, ml))
    for fid, rows in raw.items():
        ratios = [g / ml for _, g, ml in rows if ml and 0.75 <= g / ml <= 1.35]
        density[fid] = sum(ratios) / len(ratios) if ratios else 1.0
        if not 0.8 <= density[fid] <= 1.3:
            density[fid] = 1.0
        # A stated volume that does not fit the weight (a dry-mix cup, a typo in the source) is dropped for the food's own g/ml.
        servings[fid] = [(name, g, ml if ml and 0.75 <= g / ml <= 1.35 else round(g / density[fid], 1)) for name, g, ml in rows
                         if not re.search(r"\bdry\b|\bmix\b|\bpacket\b|\btsp\b|\btbsp\b|\bscoop\b", name)]
    return foods, nutrients, servings


# Taiwan FDA: which rows are drinks, their class / subtype, an English name for search, and the usual cup.
TW_DRINKS = {
    "O0701301": ("Green tea, brewed", "tea", None), "O0700501": ("Black tea, brewed", "tea", None),
    "O0700801": ("Oolong tea, brewed", "tea", None), "O0702401": ("Jasmine tea, brewed", "tea", None),
    "O0703201": ("Vitamin C drink, bottled", "juice", None), "O0600701": ("Soda, vitamin enriched, canned", "soda", None),
    "O0200601": ("Guilinggao herbal tea, canned", "tea", "herbal"), "O0600301": ("Sarsaparilla (sha shi), canned", "soda", None),
    "O0700401": ("Coffee with milk and sugar, bottled (3-in-1)", "coffee", "latte"), "O0100601": ("Black date tea, bottled", "tea", "herbal"),
    "O0200301": ("Ginger tea, canned", "tea", "herbal"), "O0200701": ("Barley tea, canned", "tea", "herbal"),
    "O0703001": ("Beta-carotene drink, bottled", "juice", None), "O0600501": ("Sarsaparilla, diet", "soda", None),
    "O0600101": ("Cola", "soda", None), "O0702101": ("Bubble milk tea, half sugar, no ice", "tea", "bubble_tea"),
    "O0702001": ("Bubble milk tea, light sugar, no ice", "tea", "bubble_tea"), "O0200201": ("Grass jelly honey drink, canned", "juice", None),
    "O0400101": ("Fruit vinegar drink", "juice", None), "O0703101": ("Multivitamin drink, bottled", "juice", None),
    "O0702901": ("Lactic acid drink (Yakult-style)", "milk", "cultured"), "O0701701": ("Oolong milk tea, light sugar, no ice", "tea", "milk_tea"),
    "O0702201": ("Bubble milk tea, full sugar, no ice", "tea", "bubble_tea"), "O0700701": ("Black tea, apple, canned", "tea", None),
    "O0701201": ("Oolong tea, full sugar, no ice (shop)", "tea", None), "O0701101": ("Oolong tea, half sugar, no ice (shop)", "tea", None),
    "O0701001": ("Oolong tea, light sugar, no ice (shop)", "tea", None), "O0701901": ("Oolong milk tea, full sugar, no ice", "tea", "milk_tea"),
    "O0701401": ("Green tea, sweetened, bottled", "tea", None), "O0703401": ("Rice vinegar drink", "juice", None),
    "O0200801": ("Black malt drink (hei mai zhi), canned", "soda", None), "O0200501": ("Black bean tea, canned", "tea", "herbal"),
    "O0702801": ("Winter melon tea, canned", "juice", None), "O0700601": ("Black tea with barley, bottled", "tea", None),
    "O0600601": ("Soda, mixed flavours, canned", "soda", None), "O0702501": ("Jasmine tea, bottled", "tea", None),
    "O0700901": ("Oolong tea, bottled", "tea", None), "O0702601": ("Chrysanthemum tea, bottled", "tea", "herbal"),
    "O0200401": ("Aloe drink, canned", "juice", None), "O0600201": ("Cola, diet", "soda", None),
    "O0701601": ("Milk tea, bottled (3-in-1)", "tea", "milk_tea"), "O0702701": ("Yakult green tea, full sugar (shop)", "tea", None),
    "O0703301": ("Sports drink (isotonic)", "electrolyte", None), "O0701801": ("Oolong milk tea, half sugar, no ice", "tea", "milk_tea"),
    "O0100501": ("Sour plum drink (suan mei tang)", "juice", None), "O0600401": ("Salted sarsaparilla, canned", "soda", None),
    "O0700301": ("Caffe latte, unsweetened (shop)", "coffee", "latte"), "O0101201": ("Apple juice drink (not 100%)", "juice", None),
    "O0700101": ("Americano, unsweetened (shop)", "coffee", None), "O0200101": ("Tomato juice (not 100%)", "juice", None),
    "O0100901": ("Pineapple juice drink (not 100%)", "juice", None), "O0100301": ("Orange juice drink (not 100%)", "juice", None),
    "O0100801": ("Grapefruit juice drink (not 100%)", "juice", None), "O9900301": ("Coconut milk drink", "plant_milk", None),
    "O0100101": ("Watermelon juice (shop)", "juice", None), "O0101101": ("Lemon juice drink (shop)", "juice", None),
    "O0701501": ("Fresh milk tea, unsweetened (shop)", "tea", "milk_tea"), "O0700201": ("Black coffee, unsweetened, canned", "coffee", None),
    "O0100401": ("Orange juice, 100%", "juice", None), "O0101301": ("Apple juice, 100%", "juice", None),
    "O0100201": ("Guava juice drink (not 100%)", "juice", None), "O0100701": ("Star fruit juice drink (not 100%)", "juice", None),
    "O0101001": ("Cranberry juice drink (not 100%)", "juice", None),
    "L01021": ("Whole fresh milk (Taiwan average)", "milk", None), "L0100101": ("High-fat fresh milk", "milk", None),
    "L0104101": ("Medium-fat fresh milk", "milk", None), "L0106101": ("Low-fat fresh milk", "milk", None),
    "L0130101": ("High-fat UHT milk", "milk", None), "L0132101": ("Whole UHT milk", "milk", None),
    "L0134101": ("Medium-fat UHT milk", "milk", None), "L0124101": ("Chocolate milk, reduced fat", "milk", "flavoured_milk"),
    "L0154101": ("Chocolate milk, reduced fat, UHT", "milk", "flavoured_milk"), "L0124201": ("Cereal milk, reduced fat", "milk", "flavoured_milk"),
    "L0124301": ("Fruit milk, low fat", "milk", "flavoured_milk"), "L0126101": ("Papaya flavoured milk, low fat", "milk", "flavoured_milk"),
    "L0154201": ("Pudding flavoured milk, UHT", "milk", "flavoured_milk"), "L1000201": ("Papaya milk (shop)", "milk", "flavoured_milk"),
    "L1000101": ("Fresh milk cocoa (shop)", "milk", "flavoured_milk"), "L1100101": ("Whole fresh goat milk", "milk", None),
    "L1104101": ("Medium-fat fresh goat milk", "milk", None), "L1132101": ("Goat milk, UHT", "milk", None),
    "L0308101": ("Drinking yoghurt, skimmed", "milk", "cultured"), "L0308201": ("Drinking yoghurt, skimmed, calcium enriched", "milk", "cultured"),
    "L0338101": ("Drinking yoghurt, skimmed, UHT", "milk", "cultured"),
}

# Caffeine per 100 ml for Taiwan rows (the table has none): the USDA brew it matches, diluted for milk teas, cans and
# shop cups. All stored as estimates.
TW_CAFFEINE = {
    "O0701301": 12, "O0700501": 20, "O0700801": 16, "O0702401": 12,
    "O0701401": 8, "O0702501": 8, "O0700901": 10, "O0700701": 8, "O0700601": 8,
    "O0701201": 15, "O0701101": 15, "O0701001": 15, "O0702701": 6,
    "O0702101": 14, "O0702001": 14, "O0702201": 14,
    "O0701701": 12, "O0701801": 12, "O0701901": 12, "O0701501": 12, "O0701601": 8,
    "O0700101": 45, "O0700301": 25, "O0700201": 35, "O0700401": 20,
    "O0600101": 10, "O0600201": 12, "L1000101": 2, "L0124101": 2, "L0154101": 2,
}

TW_NUTRIENTS = {
    "熱量": ("calories", "kcal"), "粗蛋白": ("protein", "g"), "粗脂肪": ("fat", "g"), "總碳水化合物": ("carbs", "g"),
    "膳食纖維": ("fiber", "g"), "糖質總量": ("sugar", "g"), "鈉": ("sodium", "mg"), "水分": ("water", "g"),
    "酒精含量": ("alcohol", "g"),
}


def tw_drinks(path: str):
    foods, nutrients, servings = {}, {}, {}
    for r in csv.DictReader(open(path, encoding="utf-8-sig")):
        code = r["整合編號"]
        if code not in TW_DRINKS:
            continue
        en, cls, sub = TW_DRINKS[code]
        if code not in foods:
            common = [a.strip() for a in (r["俗名"] or "").split(",") if a.strip()]
            foods[code] = {"name": en, "name_zh": r["樣品名稱"], "category": r["食品分類"], "class": cls, "subtype": sub,
                           "aliases": common + ([r["樣品英文名稱"].strip()] if r["樣品英文名稱"].strip() else [])}
            desc = r["內容物描述"]
            cup = re.search(r"約\s*(\d+)\s*mL", desc)
            s = []
            if cup:
                s.append(("large cup", float(cup.group(1))))
            elif "現調" in desc:
                s.append(("cup", 500.0))
            elif "罐" in desc:
                s.append(("can", 330.0))
            elif "瓶" in desc:
                s.append(("bottle", 600.0))
            elif cls == "milk":
                s.append(("glass", 240.0))
            elif cls == "tea" and "茶湯" in r["樣品名稱"]:
                s.append(("cup", 240.0))
            servings[code] = [(name, ml * 1.03 if cls == "milk" else ml, ml) for name, ml in s]
        m = TW_NUTRIENTS.get(r["分析項"])
        v = (r["每100克含量"] or "").strip()
        if m and v not in ("", "-"):
            try:
                nutrients.setdefault(code, {})[m[0]] = (float(v), m[1])
            except ValueError:
                pass
    for code, mg in TW_CAFFEINE.items():
        if code in foods:
            nutrients.setdefault(code, {})["caffeine_est"] = (float(mg), "mg")
    return foods, nutrients, servings


def q(v):
    if v is None or v == "":
        return "NULL"
    return "'" + str(v).replace("'", "''") + "'"


def arr(items):
    return "ARRAY[" + ",".join(q(i) for i in items) + "]::text[]" if items else "'{}'::text[]"


def write(out, source, foods, nutrients, servings):
    src = f"(select id from public.food_sources where name = '{source}')"
    ids = list(foods)
    for i in range(0, len(ids), BATCH):
        vals = ",\n  ".join(
            f"({src}, {q(fid)}, {q(f['name'])}, {q(f.get('name_zh'))}, {arr(f['aliases'])}, {q(f['category'])}, {q(f['class'])}, {q(f['subtype'])})"
            for fid in ids[i:i + BATCH] for f in [foods[fid]])
        out.write("insert into public.foods (food_source_id, source_food_id, name, name_zh, aliases, category, beverage_class, beverage_subtype)\n"
                  f"values\n  {vals}\non conflict (food_source_id, source_food_id) do update set name = excluded.name, name_zh = excluded.name_zh,\n"
                  "  aliases = excluded.aliases, category = excluded.category, beverage_class = excluded.beverage_class,\n"
                  "  beverage_subtype = excluded.beverage_subtype, updated_at = now();\n\n")
    rows = []
    for fid in ids:
        for n, (amt, unit) in nutrients.get(fid, {}).items():
            est = n.endswith("_est")
            rows.append((fid, n.removesuffix("_est"), amt, unit, est))
    for i in range(0, len(rows), BATCH * 3):
        vals = ",\n  ".join(f"({q(fid)}, {q(n)}, {amt}, {q(u)}, {'true' if e else 'false'})" for fid, n, amt, u, e in rows[i:i + BATCH * 3])
        out.write("insert into public.food_nutrients (food_id, nutrient, amount, unit, basis_qty, basis_unit, estimated)\n"
                  "select f.id, v.n, v.a, v.u, 100, 'g', v.e from (values\n  " + vals + "\n) as v(sid, n, a, u, e)\n"
                  f"join public.foods f on f.food_source_id = {src} and f.source_food_id = v.sid\n"
                  "on conflict (food_id, nutrient) do update set amount = excluded.amount, unit = excluded.unit, estimated = excluded.estimated;\n\n")
    srows = [(fid, n, g, ml) for fid in ids for n, g, ml in servings.get(fid, [])]
    for i in range(0, len(srows), BATCH * 3):
        vals = ",\n  ".join(f"({q(fid)}, {q(n)}, {round(g, 1)}, {round(ml, 1) if ml else 'NULL'}::numeric)" for fid, n, g, ml in srows[i:i + BATCH * 3])
        out.write("insert into public.food_servings (food_id, serving_name, grams, ml)\n"
                  "select f.id, v.n, v.g, v.ml from (values\n  " + vals + "\n) as v(sid, n, g, ml)\n"
                  f"join public.foods f on f.food_source_id = {src} and f.source_food_id = v.sid\n"
                  "on conflict (food_id, serving_name) do update set grams = excluded.grams, ml = excluded.ml;\n\n")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--usda-dir", required=True)
    p.add_argument("--tw-csv", required=True)
    p.add_argument("--tw-version", required=True)
    p.add_argument("--out", required=True)
    a = p.parse_args()
    uf, un, us = usda_drinks(a.usda_dir)
    tf, tn, ts = tw_drinks(a.tw_csv)
    with open(a.out, "w", encoding="utf-8") as out:
        out.write("insert into public.food_sources (name, version, license, attribution, url, country, language)\n"
                  f"values ('taiwan_fda_nutrient_db', {q(a.tw_version)}, 'Open Government Data License, version 1.0 (Taiwan)', "
                  "'Taiwan Food and Drug Administration, Food Nutrient Database (食品營養成分資料庫).', "
                  "'https://consumer.fda.gov.tw/Food/TFND.aspx', 'TW', 'zh')\n"
                  "on conflict (name) do update set version = excluded.version;\n\n")
        write(out, "usda_fdc_sr_legacy", uf, un, us)
        write(out, "taiwan_fda_nutrient_db", tf, tn, ts)
    by = {}
    for f in list(uf.values()) + list(tf.values()):
        by[f["class"]] = by.get(f["class"], 0) + 1
    print(f"USDA {len(uf)} drinks, Taiwan FDA {len(tf)} drinks; by class {by} -> {a.out}")


if __name__ == "__main__":
    main()
