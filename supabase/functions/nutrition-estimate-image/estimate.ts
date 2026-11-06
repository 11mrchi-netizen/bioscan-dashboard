// Photo estimate v3: the prompt, the response schema and the shaping of the model's answer. Pure (no Deno or network
// APIs), so estimate.test.ts runs under Node.

// Photo v3 (docs: android-app, photo logging v3). The foods are what the app's lookup matches against the food database
// at the grams seen here, so this call is the only place portions are judged; the lookup no longer re-splits a sentence.
export const BASE_PROMPT = `These photos all show ONE meal (several angles, or the plate plus a menu or a package). List
the foods and drinks in it one by one and estimate each. Count an item once even when it appears in several photos,
and prefer printed text (a menu, a label) over what the plate suggests when it is more specific.

For each food give:
- name: the food as a diary would name it ("grilled chicken thigh", "jasmine rice"), never a sentence. A brand only
  when it is printed on the food's own package.
- canonical_name: the same food as a plain English database term, without preparation, brand or amount ("chicken
  thigh", "jasmine rice"). It is searched in a food database, so keep it to the noun the food is filed under.
- grams: your best estimate of this item's weight as eaten.
- portion: the portion in words ("1 medium thigh", "about 1 cup cooked").
- preparation: how it was cooked ("pan-fried in oil", "steamed", "raw"), or "".
- the nutrients of THIS portion: calories, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sodium_mg, saturated_fat_g,
  cholesterol_mg, potassium_mg, calcium_mg, iron_mg, magnesium_mg, zinc_mg, vitamin_c_mg. Estimate every one from the
  food's usual composition; 0 only when it is really 0 (cholesterol in plants). Sodium is rarely 0 in a seasoned dish.
- confidence: "high", "medium" or "low" for this item's identity and portion together.
- assumptions: what you assumed that changes the numbers ("cooked in about 1 tbsp oil", "skin on", "whole milk").
  Empty when there is nothing.

Split a mixed dish into its parts when they can be told apart (a rice bowl: rice, chicken, egg, vegetables, sauce).
Include cooking oil and sauces you can reasonably infer as their own items when they matter.

For the whole meal give:
- description: a short title of the meal (2 to 6 words).
- confidence: "high", "medium" or "low" overall, and confidence_reason: one short sentence on what limits it.
- questions: at most 3 questions whose answers would change the estimate the most (the oil or butter used, the milk,
  a hidden filling, the portion of a mostly hidden item), each with 2 to 4 short answer options. None when you are
  confident.`;

export function buildPrompt(note?: string, totalWeightG?: number, answers?: Array<{ question: string; answer: string }>): string {
  const parts = [BASE_PROMPT];
  if (note?.trim()) {
    parts.push(`The person says about this meal: "${note.trim()}". This is AUTHORITATIVE: when it disagrees with what the
photos suggest (the dish, an ingredient, an amount), follow it.`);
  }
  if (totalWeightG && totalWeightG > 0) {
    parts.push(`The person weighed the food: ${totalWeightG} g in total (without the plate). Make the items' grams add up to
this, shared in proportion to what you see, and set the nutrients to match.`);
  }
  const answered = (answers ?? []).filter((a) => a.question?.trim() && a.answer?.trim());
  if (answered.length > 0) {
    parts.push("The person answered your earlier questions; use the answers and do not ask them again:\n" +
      answered.map((a) => `- ${a.question.trim()} -> ${a.answer.trim()}`).join("\n"));
  }
  return parts.join("\n\n");
}

const LEVEL = { type: "STRING", enum: ["high", "medium", "low"] };
const NUMBER = { type: "NUMBER" };
const ITEM_NUTRIENTS = [
  "calories", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g", "sodium_mg", "saturated_fat_g", "cholesterol_mg",
  "potassium_mg", "calcium_mg", "iron_mg", "magnesium_mg", "zinc_mg", "vitamin_c_mg",
];

export const RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    description: { type: "STRING" },
    confidence: LEVEL,
    confidence_reason: { type: "STRING" },
    items: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          name: { type: "STRING" },
          canonical_name: { type: "STRING" },
          grams: NUMBER,
          portion: { type: "STRING" },
          preparation: { type: "STRING" },
          ...Object.fromEntries(ITEM_NUTRIENTS.map((k) => [k, NUMBER])),
          confidence: LEVEL,
          assumptions: { type: "ARRAY", items: { type: "STRING" } },
        },
        required: ["name", "canonical_name", "grams", "calories", "protein_g", "carbs_g", "fat_g", "confidence"],
      },
    },
    questions: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: { question: { type: "STRING" }, options: { type: "ARRAY", items: { type: "STRING" } } },
        required: ["question", "options"],
      },
    },
  },
  required: ["description", "confidence", "items"],
};

// The estimate's micronutrient fields in the lookup's nutrient names (its NUTRIENT_MAP), for the stand-in of a food
// with no database match.
const EXTRA_KEYS: Record<string, string> = {
  fiber_g: "fiber", sugar_g: "sugar", sodium_mg: "sodium", saturated_fat_g: "saturated_fat", cholesterol_mg: "cholesterol",
  potassium_mg: "potassium", calcium_mg: "calcium", iron_mg: "iron", magnesium_mg: "magnesium", zinc_mg: "zinc",
  vitamin_c_mg: "vitamin_c",
};

const LEVEL_SCORE: Record<string, number> = { high: 0.85, medium: 0.6, low: 0.35 };

const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? v : 0);
const round1 = (v: number) => Math.round(v * 10) / 10;

// Normalizes the model's answer into the v3 response, with the v2 whole-meal fields summed from the foods so an app
// that predates v3 still reads a complete estimate.
// deno-lint-ignore no-explicit-any
export function shapeEstimate(raw: any): Record<string, unknown> | null {
  if (!raw || typeof raw !== "object" || !Array.isArray(raw.items)) return null;
  // deno-lint-ignore no-explicit-any
  const items = raw.items
    .filter((i: any) => i && typeof i.name === "string" && i.name.trim() && num(i.grams) > 0)
    // deno-lint-ignore no-explicit-any
    .map((i: any) => {
      const level = LEVEL_SCORE[i.confidence] ? i.confidence : "medium";
      const extra = Object.fromEntries(Object.entries(EXTRA_KEYS).map(([from, to]) => [to, round1(num(i[from]))]));
      return {
        name: i.name.trim(),
        canonical_name: (typeof i.canonical_name === "string" && i.canonical_name.trim()) || i.name.trim(),
        grams: round1(num(i.grams)),
        portion: typeof i.portion === "string" ? i.portion.trim() : "",
        preparation: typeof i.preparation === "string" ? i.preparation.trim() : "",
        calories: round1(num(i.calories)),
        protein_g: round1(num(i.protein_g)),
        carbs_g: round1(num(i.carbs_g)),
        fat_g: round1(num(i.fat_g)),
        extra,
        confidence: level,
        assumptions: Array.isArray(i.assumptions) ? i.assumptions.filter((a: unknown) => typeof a === "string" && a.trim()).map((a: string) => a.trim()) : [],
      };
    });
  if (items.length === 0) return null;
  // deno-lint-ignore no-explicit-any
  const sum = (pick: (i: any) => number) => round1(items.reduce((s: number, i: any) => s + pick(i), 0));
  const level = LEVEL_SCORE[raw.confidence] ? raw.confidence : "medium";
  // deno-lint-ignore no-explicit-any
  const questions = (Array.isArray(raw.questions) ? raw.questions : [])
    // deno-lint-ignore no-explicit-any
    .filter((q: any) => q && typeof q.question === "string" && q.question.trim() && Array.isArray(q.options) && q.options.length >= 2)
    .slice(0, 3)
    // deno-lint-ignore no-explicit-any
    .map((q: any) => ({ question: q.question.trim(), options: q.options.filter((o: unknown) => typeof o === "string" && o.trim()).slice(0, 4) }));
  return {
    // v2 fields, summed from the foods.
    description: typeof raw.description === "string" && raw.description.trim() ? raw.description.trim() : items.map((i: { name: string }) => i.name).slice(0, 3).join(", "),
    calories: sum((i) => i.calories),
    protein_g: sum((i) => i.protein_g),
    carbs_g: sum((i) => i.carbs_g),
    fat_g: sum((i) => i.fat_g),
    fiber_g: sum((i) => i.extra.fiber),
    sugar_g: sum((i) => i.extra.sugar),
    sodium_mg: sum((i) => i.extra.sodium),
    confidence: LEVEL_SCORE[level],
    // v3 fields.
    confidence_level: level,
    confidence_reason: typeof raw.confidence_reason === "string" ? raw.confidence_reason.trim() : "",
    total_grams: sum((i) => i.grams),
    items,
    questions,
  };
}
