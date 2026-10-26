// Run: node supabase/functions/_shared/nutrition-lookup/catalog.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { catalogDisplayName, catalogMeasures, catalogPer100, catalogResolvedItem, catalogSuggestion } from "./catalog.ts";

const yogurt = { id: 7, name: "Greek yogurt", name_zh: "希臘優格", brand: "質立", barcode: "4710784965537", source: "openfoodfacts" };
assert.equal(catalogDisplayName(yogurt), "Greek yogurt (希臘優格), 質立");
assert.equal(catalogDisplayName({ ...yogurt, name_zh: null, brand: null }), "Greek yogurt");

// Per 100 g in the lookup's vocabulary; a per-100 ml row counts as per 100 g; other units are left out.
assert.deepEqual(
  catalogPer100([
    { nutrient: "calories", amount: 97, unit: "kcal", basis_qty: 100, basis_unit: "g" },
    { nutrient: "protein", amount: 9, unit: "g", basis_qty: 100, basis_unit: "g" },
    { nutrient: "sodium", amount: 40, unit: "mg", basis_qty: 100, basis_unit: "g" },
    { nutrient: "vitamin_a", amount: 30, unit: "mcg", basis_qty: 100, basis_unit: "g" },
  ]),
  { calories: 97, protein: 9, sodium: 40 },
);
// Missing calories (many USDA foundation rows) come from the macros.
assert.deepEqual(
  catalogPer100([
    { nutrient: "protein", amount: 10, unit: "g", basis_qty: 100, basis_unit: "g" },
    { nutrient: "carbs", amount: 20, unit: "g", basis_qty: 100, basis_unit: "g" },
    { nutrient: "fat", amount: 5, unit: "g", basis_qty: 100, basis_unit: "g" },
  ]).calories,
  165,
);
// A per-serving basis is rescaled to 100.
assert.equal(catalogPer100([{ nutrient: "calories", amount: 150, unit: "kcal", basis_qty: 375, basis_unit: "ml" }]).calories, 40);

assert.deepEqual(catalogMeasures([{ serving_name: "375 ml", grams: null, ml: 375 }]), [{ name: "375 ml", grams: 375 }, { name: "100 g", grams: 100 }]);
assert.deepEqual(catalogMeasures([{ serving_name: "100 g", grams: 100, ml: null }]), [{ name: "100 g", grams: 100 }]);

const nutrients = [
  { nutrient: "calories", amount: 40, unit: "kcal", basis_qty: 100, basis_unit: "ml" },
  { nutrient: "protein", amount: 6.4, unit: "g", basis_qty: 100, basis_unit: "ml" },
];
const servings = [{ serving_name: "Whole package (375 ml)", grams: null, ml: 375 }];
const milk = { id: 3, name: "Protein Chocolate Milk", name_zh: null, brand: "Fresh Delight", barcode: "4710339007057", source: "openfoodfacts" };

// No grams: its default serving (the package here).
const whole = catalogResolvedItem("protein milk", milk, nutrients, servings);
assert.equal(whole.catalog_food_id, 3);
assert.equal(whole.cronometer_food_id, null);
assert.equal(whole.quantity_g, 375);
assert.equal(whole.nutrients_for_portion.calories, 150);
assert.equal(whole.serving_used, "1 x Whole package (375 ml)");
assert.equal(whole.match_quality, "exact");
assert.deepEqual(whole.all_measures.map((m) => m.grams), [375, 100]);

// Given grams: scaled from per 100.
const half = catalogResolvedItem("protein milk", milk, nutrients, servings, 187.5);
assert.equal(half.nutrients_for_portion.calories, 75);
assert.equal(half.serving_used, "187.5 g");

const s = catalogSuggestion(milk, 40, servings);
assert.deepEqual(s.ref, { kind: "catalog", id: 3 });
assert.equal(s.default_grams, 375);

console.log("catalog.test.ts: all assertions passed");
