// Foods from the app's own catalog (foods / food_nutrients / food_servings: barcode products, scanned labels, USDA rows)
// as lookup items, so the app handles them exactly like Cronometer matches.

import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import { defaultServing, emptyResolvedItem, type Measure, NUTRIENT_MAP, type ResolvedItem, type Suggestion } from "./matching.ts";

export interface CatalogFood {
  id: number;
  name: string;
  name_zh: string | null;
  brand: string | null;
  barcode: string | null;
  source: string;
}

export interface CatalogNutrient {
  nutrient: string;
  amount: number;
  unit: string;
  basis_qty: number;
  basis_unit: string;
}

export interface CatalogServing {
  serving_name: string;
  grams: number | null;
  ml: number | null;
}

const UNIT_OF = new Map(Object.values(NUTRIENT_MAP).map((n) => [n.name, n.unit]));

export function catalogDisplayName(food: CatalogFood): string {
  const name = food.name_zh && food.name_zh !== food.name ? `${food.name} (${food.name_zh})` : food.name;
  return food.brand ? `${name}, ${food.brand}` : name;
}

// Per 100 g (or 100 ml, taken as grams) in the lookup's own vocabulary. Values in a different unit than the lookup uses
// are left out rather than mixed into totals; calories missing from a row (many USDA foundation rows) come from macros.
export function catalogPer100(nutrients: CatalogNutrient[]): Record<string, number> {
  const per100: Record<string, number> = {};
  for (const n of nutrients) {
    const unit = UNIT_OF.get(n.nutrient);
    if (!unit || unit !== n.unit || !(n.basis_qty > 0)) continue;
    per100[n.nutrient] = Math.round((Number(n.amount) * 100 / Number(n.basis_qty)) * 1000) / 1000;
  }
  if (per100.calories == null && (per100.protein != null || per100.carbs != null || per100.fat != null)) {
    per100.calories = Math.round(
      (4 * (per100.protein ?? 0) + 4 * (per100.carbs ?? 0) + 9 * (per100.fat ?? 0) + 7 * (per100.alcohol ?? 0)) * 10,
    ) / 10;
  }
  return per100;
}

export function catalogMeasures(servings: CatalogServing[]): Measure[] {
  const measures = servings
    .map((s) => ({ name: s.serving_name, grams: Number(s.grams ?? s.ml ?? 0) }))
    .filter((m) => m.grams > 0);
  return measures.some((m) => m.grams === 100) ? measures : [...measures, { name: "100 g", grams: 100 }];
}

export function catalogResolvedItem(
  query: string,
  food: CatalogFood,
  nutrients: CatalogNutrient[],
  servings: CatalogServing[],
  grams?: number,
  label?: string | null,
): ResolvedItem {
  const per100 = catalogPer100(nutrients);
  const measures = catalogMeasures(servings);
  const serving = grams != null && grams > 0 ? null : defaultServing(measures);
  const g = grams != null && grams > 0 ? grams : serving!.grams;
  const forPortion: Record<string, number> = {};
  for (const [k, v] of Object.entries(per100)) forPortion[k] = Math.round(v * g) / 100;
  return {
    ...emptyResolvedItem(query, g),
    catalog_food_id: food.id,
    cronometer_food_name: catalogDisplayName(food),
    cronometer_source: food.source,
    serving_used: label ?? (serving ? `1 x ${serving.name}` : `${g} g`),
    nutrients_per_100g: per100,
    nutrients_for_portion: forPortion,
    all_measures: measures,
    match_quality: "exact",
  };
}

export function catalogSuggestion(food: CatalogFood, kcalPer100g: number | null, servings: CatalogServing[], remembered = false): Suggestion {
  const measures = catalogMeasures(servings);
  return {
    ref: { kind: "catalog", id: food.id },
    name: catalogDisplayName(food),
    source: food.source,
    kcal_per_100g: kcalPer100g,
    measures,
    default_grams: defaultServing(measures).grams,
    remembered,
  };
}

// ---------------------------------------------------------------------------
// Database access
// ---------------------------------------------------------------------------

export async function loadCatalogFood(
  db: SupabaseClient,
  foodId: number,
): Promise<{ food: CatalogFood; nutrients: CatalogNutrient[]; servings: CatalogServing[] } | null> {
  const [foodRes, nutrientRes, servingRes] = await Promise.all([
    db.from("foods").select("id, name, name_zh, brand, barcode, food_sources(name)").eq("id", foodId).maybeSingle(),
    db.from("food_nutrients").select("nutrient, amount, unit, basis_qty, basis_unit").eq("food_id", foodId),
    db.from("food_servings").select("serving_name, grams, ml").eq("food_id", foodId).order("id"),
  ]);
  if (foodRes.error || !foodRes.data) return null;
  const row = foodRes.data as unknown as CatalogFood & { food_sources: { name: string } | null };
  return {
    food: { id: row.id, name: row.name, name_zh: row.name_zh, brand: row.brand, barcode: row.barcode, source: row.food_sources?.name ?? "catalog" },
    nutrients: (nutrientRes.data ?? []) as CatalogNutrient[],
    servings: (servingRes.data ?? []) as CatalogServing[],
  };
}

export async function catalogItem(db: SupabaseClient, query: string, foodId: number, grams?: number, label?: string | null): Promise<ResolvedItem | null> {
  const loaded = await loadCatalogFood(db, foodId);
  return loaded ? catalogResolvedItem(query, loaded.food, loaded.nutrients, loaded.servings, grams, label) : null;
}

interface SearchRow {
  id: number;
  name: string;
  name_zh: string | null;
  brand: string | null;
  barcode: string | null;
  source: string;
  kcal_per_100g: number | null;
}

// public.search_foods: name, Chinese name, aliases and exact barcode, trigram-ranked.
export async function searchCatalog(db: SupabaseClient, query: string, n: number): Promise<Suggestion[]> {
  const { data, error } = await db.rpc("search_foods", { q: query, n });
  if (error) {
    console.log(`search_foods: ${error.message}`);
    return [];
  }
  const rows = (data ?? []) as SearchRow[];
  if (rows.length === 0) return [];
  const { data: servingRows } = await db
    .from("food_servings")
    .select("food_id, serving_name, grams, ml")
    .in("food_id", rows.map((r) => r.id));
  const servingsOf = (id: number) => ((servingRows ?? []) as (CatalogServing & { food_id: number })[]).filter((s) => s.food_id === id);
  return rows.map((r) => catalogSuggestion(r, r.kcal_per_100g == null ? null : Number(r.kcal_per_100g), servingsOf(r.id)));
}
