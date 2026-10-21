// Run: node supabase/functions/_shared/nutrition-lookup/matching.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import {
  type CronoFoodDetail,
  confidence,
  defaultState,
  implausibility,
  isPlainQuery,
  looksCompound,
  matchName,
  memoryKey,
  parsePortion,
  portionGrams,
  rankCandidates,
  reconcile,
  type ResolvedItem,
  simplifyQuery,
  sumNutrients,
} from "./matching.ts";

const q = (query: string, name: string) => matchName(query, name).quality;

// "apple" is the raw fruit: never pineapple, never a dessert, juice or baby food.
assert.equal(q("apple", "Apples, raw, with skin"), "exact");
assert.equal(q("apple", "Apple, raw, without skin"), "exact");
assert.equal(q("red apple", "Apples, raw, with skin"), "exact");
assert.equal(q("apple", "Pineapple, raw"), "none");
assert.equal(q("apple", "Applesauce, sweetened"), "none");
for (const dish of ["Apple crisp", "Apple pie", "Apple juice", "Babyfood, apples", "Apple Strudel"]) {
  assert.equal(q("apple", dish), "weak", dish);
}
assert.equal(q("apple", "Apples, baked"), "good");
assert.equal(q("apple pie", "Apple pie"), "exact");
assert.equal(q("apple juice", "Apple juice"), "exact");

// The same holds for other single foods.
assert.equal(q("banana", "Bananas, raw"), "exact");
assert.equal(q("banana", "Banana cream pie"), "weak");
assert.equal(q("tomatoes", "Tomato, red, raw"), "exact");
assert.equal(q("strawberries", "Strawberry, raw"), "exact");

// Decomposed, USDA-style item names still match their verbose database entries.
assert.equal(q("chicken breast, cooked", "Chicken, broilers or fryers, breast, meat only, cooked, roasted"), "good");
assert.equal(q("rice, white, cooked", "Rice, white, long-grain, regular, enriched, cooked"), "good");
assert.equal(q("egg noodles, cooked", "Noodles, egg, cooked, enriched"), "exact");
assert.equal(q("whey protein", "Protein bar, whey"), "weak");

// Grains, legumes and meat default to cooked; produce defaults to raw; an explicit state is respected.
assert.equal(defaultState("rice"), "cooked");
assert.equal(defaultState("chicken breast"), "cooked");
assert.equal(defaultState("apple"), "raw");
assert.equal(defaultState("raw salmon"), null);
assert.equal(defaultState("whey protein"), null);
const rice = rankCandidates("rice", [
  { id: 1, name: "Rice, white, long-grain, regular, raw, enriched", source: "USDAsr" },
  { id: 2, name: "Rice, white, long-grain, regular, enriched, cooked", source: "USDAsr" },
]);
assert.equal(rice[0].result.id, 2);
assert.equal(q("chicken breast", "Chicken, broilers or fryers, breast, meat only, cooked, roasted"), "exact");
assert.equal(q("raw salmon", "Fish, salmon, Atlantic, wild, raw"), "good");

// Ranking: the plain government entry beats dishes and branded entries, whatever Cronometer's own order.
const ranked = rankCandidates("apple", [
  { id: 1, name: "Apple crisp", source: "NCCDB:4043" },
  { id: 2, name: "Pineapple, raw", source: "USDAsr" },
  { id: 3, name: "Organic Gala Apples", source: "FDC:Branded" },
  { id: 4, name: "Apples, raw, with skin", source: "USDAsr" },
  { id: 5, name: "Apple pie", source: "NCCDB" },
]);
assert.deepEqual(ranked.map((c) => c.result.id), [4, 3, 1, 5]);

// Portions.
assert.deepEqual(parsePortion("one apple"), { count: 1, unit: null, size: null, rest: "apple" });
assert.deepEqual(parsePortion("1 large apple"), { count: 1, unit: null, size: "large", rest: "apple" });
assert.deepEqual(parsePortion("2 eggs"), { count: 2, unit: null, size: null, rest: "eggs" });
assert.deepEqual(parsePortion("150 g rice"), { count: 150, unit: "g", size: null, rest: "rice" });
assert.deepEqual(parsePortion("a cup of oats"), { count: 1, unit: "cup", size: null, rest: "oats" });
assert.deepEqual(parsePortion("breakfast waffle x2"), { count: 2, unit: null, size: null, rest: "breakfast waffle" });
assert.equal(parsePortion("apple"), null);

const rawApple: CronoFoodDetail = {
  id: 4, name: "Apples, raw, with skin", source: "USDAsr", defaultMeasureId: 10,
  measures: [
    { id: 10, name: "1 cup, sliced", value: 109, amount: 1, type: "" },
    { id: 11, name: "small (2-3/4\" dia)", value: 149, amount: 1, type: "" },
    { id: 12, name: "medium (3\" dia)", value: 182, amount: 1, type: "" },
    { id: 13, name: "large (3-1/4\" dia)", value: 223, amount: 1, type: "" },
    { id: 14, name: "100 g", value: 100, amount: 1, type: "" },
  ],
  nutrients: [
    { id: 208, amount: 52 }, { id: 203, amount: 0.26 }, { id: 205, amount: 13.81 }, { id: 204, amount: 0.17 },
    { id: 255, amount: 85.56 },
  ],
};
assert.equal(portionGrams(rawApple, parsePortion("one apple")!)?.grams, 182);
assert.equal(portionGrams(rawApple, parsePortion("1 large apple")!)?.grams, 223);
assert.equal(portionGrams(rawApple, parsePortion("2 apples")!)?.grams, 364);
assert.equal(portionGrams(rawApple, parsePortion("1 cup of apple")!)?.grams, 109);

// A dessert served by the cup is never "one" of anything.
const appleCrisp: CronoFoodDetail = {
  id: 1, name: "Apple crisp", source: "NCCDB:4043", defaultMeasureId: 20,
  measures: [{ id: 20, name: "1 cup", value: 246, amount: 1, type: "" }],
  nutrients: [
    { id: 208, amount: 227.08 }, { id: 203, amount: 1.88 }, { id: 205, amount: 41.08 }, { id: 204, amount: 7.37 },
    { id: 255, amount: 62.69 },
  ],
};
assert.equal(portionGrams(appleCrisp, parsePortion("one apple crisp")!), null);

// Plausibility: the dessert profile cannot be a plain apple; it is fine for "apple crisp".
assert.equal(implausibility(rawApple, "apple"), null);
assert.match(implausibility(appleCrisp, "apple") ?? "", /energy-dense/);
assert.equal(implausibility(appleCrisp, "apple crisp"), null);
const brokenEntry: CronoFoodDetail = { ...rawApple, nutrients: [{ id: 208, amount: 300 }, { id: 203, amount: 1 }] };
assert.match(implausibility(brokenEntry, "granola") ?? "", /macros/);
assert.equal(isPlainQuery("apple"), true);
assert.equal(isPlainQuery("baked apple"), false);

// Reconciling the database match with the independent AI estimate.
function item(quality: ResolvedItem["match_quality"], kcal: number | null, est: number | null): ResolvedItem {
  return {
    query: "x", cronometer_food_id: 1, cronometer_food_name: "x", cronometer_source: "USDAsr", search_score: null,
    quantity_g: 100, serving_used: null, nutrients_per_100g: {},
    nutrients_for_portion: kcal == null ? {} : { calories: kcal, protein: 1, carbs: 10, fat: 1, vitamin_c: 5 },
    all_measures: [], match_quality: quality, alternatives: [], rejected: [], memory_key: "x", portion_count: null,
    portion_unit: null, from_memory: false,
    ai_estimate: est == null ? null : { calories: est, protein_g: 0.5, carbs_g: 25, fat_g: 0.3 },
    used_estimate: false, disagreement: false,
  };
}
const goodButWrong = item("good", 570, 95);
reconcile(goodButWrong);
assert.equal(goodButWrong.disagreement, true);
assert.equal(goodButWrong.used_estimate, true);

const exactButOff = item("exact", 570, 95);
reconcile(exactButOff);
assert.equal(exactButOff.disagreement, true);
assert.equal(exactButOff.used_estimate, false);

const agrees = item("good", 100, 95);
reconcile(agrees);
assert.equal(agrees.disagreement || agrees.used_estimate, false);

const unmatched = item("weak", 300, 120);
reconcile(unmatched);
assert.equal(unmatched.used_estimate, true);

// Totals: estimated items count instead of silently disappearing; unmatched items without an estimate are skipped.
const noEstimate = item("none", null, null);
reconcile(noEstimate);
const sums = sumNutrients([agrees, goodButWrong, noEstimate]);
assert.equal(sums.totals.calories, 195);
assert.equal(sums.totals.vitamin_c, 5);
assert.equal(sums.included, 2);
assert.deepEqual(sums.skipped, ["x"]);
assert.equal(confidence([agrees]), 0.85);
assert.ok(confidence([agrees, goodButWrong]) < 0.85);
assert.equal(confidence([noEstimate]), 0.3);

// Memory keys and query simplification.
assert.equal(memoryKey("Apples, raw"), "apple");
assert.equal(memoryKey("apple"), "apple");
assert.equal(memoryKey("apple, baked"), "apple baked");
assert.equal(simplifyQuery("grilled chicken breast"), "chicken breast");
assert.equal(looksCompound("one apple"), false);
assert.equal(looksCompound("one scoop of whey protein and one banana"), true);

console.log("matching.test.ts: all assertions passed");
