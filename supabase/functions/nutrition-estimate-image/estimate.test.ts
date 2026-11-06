// Run: node supabase/functions/nutrition-estimate-image/estimate.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { buildPrompt, shapeEstimate } from "./estimate.ts";

// The note is authoritative, a weight is to be shared out, and answered questions are not asked again.
const prompt = buildPrompt("it's ghee roast dosa", 420, [{ question: "Oil or butter?", answer: "ghee" }, { question: " ", answer: "x" }]);
assert.match(prompt, /ghee roast dosa/);
assert.match(prompt, /AUTHORITATIVE/);
assert.match(prompt, /420 g in total/);
assert.match(prompt, /Oil or butter\? -> ghee/);
assert.doesNotMatch(buildPrompt(), /AUTHORITATIVE|weighed|answered/);

// The model's answer: foods kept with their numbers, whole-meal v2 fields summed from them, junk dropped.
const shaped = shapeEstimate({
  description: "Chicken rice bowl",
  confidence: "medium",
  confidence_reason: "sauce unclear",
  items: [
    { name: "grilled chicken thigh", canonical_name: "chicken thigh", grams: 120, portion: "1 thigh", preparation: "grilled",
      calories: 250, protein_g: 30, carbs_g: 0, fat_g: 14, sodium_mg: 400, iron_mg: 1.2, confidence: "high", assumptions: ["skin on", ""] },
    { name: "jasmine rice", canonical_name: "", grams: 180, calories: 234, protein_g: 4.3, carbs_g: 51, fat_g: 0.5, fiber_g: 0.7, confidence: "weird" },
    { name: "", grams: 50, calories: 10 },
    { name: "air", grams: 0, calories: 0 },
  ],
  questions: [
    { question: "Was the chicken cooked in oil?", options: ["No oil", "A little", "A lot"] },
    { question: "Only one option?", options: ["yes"] },
  ],
})!;
assert.equal(shaped.description, "Chicken rice bowl");
const items = shaped.items as Array<Record<string, unknown>>;
assert.equal(items.length, 2);
assert.equal(items[1].canonical_name, "jasmine rice"); // falls back to the name
assert.equal(items[1].confidence, "medium"); // unknown level -> medium
assert.deepEqual(items[0].assumptions, ["skin on"]);
assert.equal((items[0].extra as Record<string, number>).sodium, 400);
assert.equal((items[0].extra as Record<string, number>).zinc, 0);
assert.equal(shaped.calories, 484);
assert.equal(shaped.sodium_mg, 400);
assert.equal(shaped.total_grams, 300);
assert.equal(shaped.confidence, 0.6);
assert.equal(shaped.confidence_level, "medium");
assert.equal((shaped.questions as unknown[]).length, 1);
assert.equal(shapeEstimate({ items: [] }), null);
assert.equal(shapeEstimate("nonsense"), null);
console.log("estimate.test.ts: all assertions passed");
