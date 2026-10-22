// Run: node supabase/functions/_shared/nutrition-lookup/label.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { labelPerServing, normalizeLabel } from "./label.ts";

// A typical Taiwanese drink label: 每一份量 375 毫升, 本包裝含 1 份, per serving and per 100 ml both printed.
const milk = normalizeLabel({
  product_name: "超能蛋白 低脂 咖啡口味",
  serving_size: 375,
  serving_unit: "ml",
  servings_per_package: 1,
  energy_unit: "kcal",
  per_serving: { calories: 210, protein_g: 24, fat_g: 3.8, carbs_g: 20.3, sugar_g: 19.5, sodium_mg: 225 },
  per_100: { calories: 56, protein_g: 6.4, fat_g: 1.0, carbs_g: 5.4, sugar_g: 5.2, sodium_mg: 60 },
});
assert.equal(milk.per100.calories, 56);
assert.equal(milk.per100.sodium, 60);
assert.equal(milk.basisUnit, "ml");
assert.deepEqual(milk.serving, { name: "1 serving (375 ml)", grams: 375 });
assert.equal(milk.wholePackage, null); // a single-serving package
assert.deepEqual(milk.checks, []);
assert.deepEqual(labelPerServing(milk), { calories: 210, protein: 24, fat: 3.75, carbs: 20.25, sugar: 19.5, sodium: 225 });

// Snack: per serving only, 3 servings per bag, energy printed in kJ.
const chips = normalizeLabel({
  serving_size: 30,
  serving_unit: "g",
  servings_per_package: 3,
  energy_unit: "kJ",
  per_serving: { calories: 669.4, protein_g: 2, fat_g: 10, carbs_g: 15, sodium_mg: 150 },
});
assert.equal(chips.per100.calories, 533.3); // 669.4 kJ = 159.99 kcal per 30 g
assert.equal(chips.per100.sodium, 500);
assert.deepEqual(chips.wholePackage, { name: "Whole package (90 g)", grams: 90 });
assert.deepEqual(chips.checks, []);

// A misread label: per-serving column read as per 100, sugar above carbs, sodium in grams.
const bad = normalizeLabel({
  serving_size: 50,
  serving_unit: "g",
  energy_unit: "kcal",
  per_serving: { calories: 250, protein_g: 5, fat_g: 10, carbs_g: 35 },
  per_100: { calories: 250, protein_g: 5, fat_g: 10, carbs_g: 35, sugar_g: 40, sodium_mg: 0.3 },
});
const fields = bad.checks.map((c) => c.field);
assert.ok(fields.includes("calories"));
assert.ok(fields.includes("sugar"));
assert.ok(fields.includes("sodium"));

// Calories that don't fit the macros.
const off = normalizeLabel({ serving_size: 100, serving_unit: "g", per_100: { calories: 500, protein_g: 5, fat_g: 1, carbs_g: 20 } });
assert.ok(off.checks.some((c) => c.field === "calories" && /macros/.test(c.message)));

// Nothing usable.
const empty = normalizeLabel({ product_name: "x" });
assert.ok(empty.checks.some((c) => c.field === "calories"));
assert.equal(labelPerServing(empty), null);

console.log("label.test.ts: all assertions passed");
