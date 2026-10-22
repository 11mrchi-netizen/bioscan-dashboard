// Run: node supabase/functions/_shared/nutrition-lookup/matching.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import {
  type CronoFoodDetail,
  confidence,
  defaultServing,
  defaultState,
  implausibility,
  isPlainQuery,
  looksCompound,
  matchName,
  memoryKey,
  mergeSuggestions,
  parseAmount,
  parsePortion,
  portionGrams,
  rankCandidates,
  rankForSuggestions,
  reconcile,
  type ResolvedItem,
  type Suggestion,
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
assert.deepEqual(parsePortion("1 large apple"), { count: 1, unit: null, size: "large", rest: "apple" }); // "l" is not litres here
assert.deepEqual(parsePortion("a lemon"), { count: 1, unit: null, size: null, rest: "lemon" });

// Amounts wherever they are written (cases from the backfill review of logged meals).
const amount = (d: string) => {
  const a = parseAmount(d);
  return { query: a.query, weightG: a.weightG, count: a.portion?.count, liquid: a.liquid };
};
assert.deepEqual(amount("20gr of dried tofu"), { query: "dried tofu", weightG: 20, count: undefined, liquid: false });
assert.deepEqual(amount("McVitie's digestive biscuit snack bar (30g)"),
  { query: "McVitie's digestive biscuit snack bar", weightG: 30, count: undefined, liquid: false });
assert.deepEqual(amount("Aussie lemonade juiced monster 355ml can"),
  { query: "Aussie lemonade juiced monster can", weightG: 355, count: undefined, liquid: true });
assert.deepEqual(amount("2 breakfast waffles (35gr each)"), { query: "breakfast waffles", weightG: 70, count: undefined, liquid: false });
assert.deepEqual(amount("2 slices of bread (60g)"), { query: "bread", weightG: 60, count: undefined, liquid: false });
assert.deepEqual(amount("2 cans of coke 330ml"), { query: "coke", weightG: 660, count: undefined, liquid: true });
assert.deepEqual(amount("1.5l water"), { query: "water", weightG: 1500, count: undefined, liquid: true });
assert.deepEqual(amount("150 g rice"), { query: "rice", weightG: 150, count: undefined, liquid: false });
assert.deepEqual(amount("150 g rice x2"), { query: "rice", weightG: 300, count: undefined, liquid: false });
assert.deepEqual(amount("one apple"), { query: "apple", weightG: undefined, count: 1, liquid: false });
assert.deepEqual(amount("apple"), { query: "apple", weightG: undefined, count: 1, liquid: false });
assert.deepEqual(amount("breakfast waffle x4"), { query: "breakfast waffle", weightG: undefined, count: 4, liquid: false });
assert.deepEqual(amount('3 20" pizza slices'), { query: '20" pizza slices', weightG: undefined, count: 3, liquid: false });
assert.equal(memoryKey("Aussie lemonade juiced monster can"), memoryKey("Aussie lemonade juiced monster")); // containers are not food

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
// A drink measured by volume cannot carry a per-serving entry's 211 kcal per 100 g; milk and beer are fine.
const drink = (kcal: number, carbs: number, fat = 0, alcohol = 0): CronoFoodDetail => ({
  ...rawApple, name: "drink", nutrients: [{ id: 208, amount: kcal }, { id: 205, amount: carbs }, { id: 204, amount: fat }, { id: 221, amount: alcohol }],
});
assert.match(implausibility(drink(211, 52), "aussie lemonade juiced monster", true) ?? "", /drink/);
assert.equal(implausibility(drink(45, 11), "aussie lemonade juiced monster", true), null);
assert.equal(implausibility(drink(211, 52), "aussie lemonade juiced monster", false), null);
assert.equal(implausibility(drink(43, 3.6, 1), "beer", true), null);
assert.equal(implausibility(drink(130, 4, 13), "cream", true), null);
assert.equal(isPlainQuery("apple"), true);
assert.equal(isPlainQuery("baked apple"), false);

// Reconciling the database match with the independent AI estimate.
function item(quality: ResolvedItem["match_quality"], kcal: number | null, est: number | null): ResolvedItem {
  return {
    query: "x", cronometer_food_id: 1, catalog_food_id: null, cronometer_food_name: "x", cronometer_source: "USDAsr", search_score: null,
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

const exactButOff = item("exact", 250, 95);
reconcile(exactButOff);
assert.equal(exactButOff.disagreement, true);
assert.equal(exactButOff.used_estimate, false);

// An exact name that is off by more than 3x is bad data or a wrong portion: the estimate stands in.
const exactWayOff = item("exact", 570, 95);
reconcile(exactWayOff);
assert.equal(exactWayOff.disagreement, true);
assert.equal(exactWayOff.used_estimate, true);

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

// Suggestions while typing: nothing is dropped (a half-typed word matches no whole word yet), whole words still lead.
const typed = rankForSuggestions("pepp", [
  { id: 1, name: "Pepperoni pizza", source: "NCCDB" },
  { id: 2, name: "Peppers, sweet, red, raw", source: "USDAsr" },
]);
assert.deepEqual(typed.map((r) => r.id), [1, 2]);
const full = rankForSuggestions("pepperoni pizza", [
  { id: 1, name: "Pizza Shell", source: "CRDB" },
  { id: 2, name: "Pizza, pepperoni topping, regular crust", source: "USDAsr" },
]);
assert.equal(full[0].id, 2);

assert.deepEqual(defaultServing([{ name: "1 cup, sliced", grams: 109 }, { name: "medium (3\" dia)", grams: 182 }, { name: "100 g", grams: 100 }]),
  { name: "medium (3\" dia)", grams: 182 });
assert.deepEqual(defaultServing([{ name: "1 cup", grams: 158 }]), { name: "1 cup", grams: 158 });
assert.deepEqual(defaultServing([{ name: "100 g", grams: 100 }]), { name: "100 g", grams: 100 });
assert.deepEqual(defaultServing([]), { name: "100 g", grams: 100 });

const sug = (kind: "cronometer" | "catalog", id: number, name: string, remembered = false): Suggestion =>
  ({ ref: { kind, id }, name, source: null, kcal_per_100g: null, measures: [], default_grams: 100, remembered });
const merged = mergeSuggestions(
  "greek yogurt",
  [sug("cronometer", 9, "Greek yogurt, plain, nonfat", true)],
  [sug("catalog", 1, "希臘優格"), sug("catalog", 2, "Greek Yogurt, Fage"), sug("catalog", 3, "Yogurt, plain")],
  [sug("cronometer", 9, "Greek yogurt, plain, nonfat"), sug("cronometer", 10, "Greek Yogurt, Fage"), sug("cronometer", 11, "Greek yogurt, vanilla")],
  5,
);
// Remembered first; the catalog's word-for-word match before Cronometer; duplicates by id or name dropped; capped.
assert.deepEqual(merged.map((s) => `${s.ref.kind}:${s.ref.id}`), ["cronometer:9", "catalog:2", "cronometer:11", "catalog:1", "catalog:3"]);

console.log("matching.test.ts: all assertions passed");
