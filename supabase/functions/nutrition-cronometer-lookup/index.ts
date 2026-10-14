// nutrition-cronometer-lookup — prototype
//
// Searches Cronometer's food database (via their unofficial mobile REST API)
// and returns enriched nutrition data with 80+ micronutrients.
//
// Two modes:
//   1. { items: [{ description, quantity_g? }] }
//      Direct lookup — search each item in Cronometer and return nutrients.
//   2. { description: "Pan-seared steak with brown rice..." }
//      Decompose + lookup — uses Gemini to split an aggregate meal description
//      into individual items with estimated gram weights, then looks each up
//      in Cronometer. Designed for enriching photo-based estimates.
//
// Secrets: CRONOMETER_EMAIL, CRONOMETER_PASSWORD, GEMINI_API_KEY (existing).

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

// ---------------------------------------------------------------------------
// Cronometer mobile API client
// ---------------------------------------------------------------------------

const CRONO_BASE = "https://mobile.cronometer.com";
const CRONO_HEADERS = {
  "Content-Type": "text/plain; charset=utf-8",
  "User-Agent": "Dart/3.9 (dart:io)",
  "Accept-Encoding": "gzip",
};

interface CronoSession {
  userId: number;
  sessionKey: string;
  expiresAt: number;
}

let cachedSession: CronoSession | null = null;

// Every outbound call has a deadline: one stalled Cronometer or Gemini request must not hold the whole lookup open.
async function fetchT(url: string, init: RequestInit, ms = 15000): Promise<Response> {
  try {
    return await fetch(url, { ...init, signal: AbortSignal.timeout(ms) });
  } catch (e) {
    if (e instanceof DOMException && e.name === "TimeoutError") {
      throw new Error(`timed out after ${ms / 1000}s calling ${new URL(url).host}`);
    }
    throw e;
  }
}

// Run fn over items with at most `limit` in flight; results keep the input order.
async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i]);
    }
  }));
  return out;
}

function authBlock(session: CronoSession) {
  return {
    userId: session.userId,
    token: session.sessionKey,
    api: 3,
    os: "Android",
    build: "2807",
    flavour: "free",
  };
}

async function cronoLogin(email: string, password: string): Promise<CronoSession> {
  if (cachedSession && Date.now() < cachedSession.expiresAt) return cachedSession;

  const res = await fetchT(`${CRONO_BASE}/api/v2/login`, {
    method: "POST",
    headers: CRONO_HEADERS,
    body: JSON.stringify({
      email,
      password,
      timezone: null,
      userCode: null,
      build: "4.48.2 b2807-a",
      device: "Android 14 (SDK 34), Google Pixel 6 Pro",
      firebaseToken: "",
      features: {
        food_search_config: '{"newSearch": true, "newSpellcheck": true}',
        use_gpt_autofill: "true",
      },
      auth: { userId: null, token: null, api: 3, os: "Android", build: "2807", flavour: "free" },
      lastSeen: 0,
      config: { call_version: 2 },
    }),
  });

  const body = await res.json();
  if (body.result !== "SUCCESS") {
    throw new Error(`Cronometer login failed: ${body.error ?? body.result ?? "unknown"}`);
  }

  cachedSession = {
    userId: body.id,
    sessionKey: body.sessionKey,
    expiresAt: Date.now() + 55 * 60 * 1000, // refresh after ~55 min
  };
  return cachedSession;
}

interface CronoFoodSearchResult {
  id: number;
  name: string;
  source: string;
  measureId: number;
  measureDisplayName?: string;
  globalPopularity?: number;
  score?: number;
}

async function cronoFindFood(session: CronoSession, query: string): Promise<CronoFoodSearchResult[]> {
  const res = await fetchT(`${CRONO_BASE}/api/v2/find_food`, {
    method: "POST",
    headers: CRONO_HEADERS,
    body: JSON.stringify({
      query,
      tab: "ALL",
      sources: ["All"],
      config: { newSearch: true, newSpellcheck: true, call_version: 1 },
      auth: authBlock(session),
      lastSeen: 0,
    }),
  });

  const body = await res.json();
  return body.foods ?? [];
}

interface CronoNutrient {
  id: number;
  amount: number;
}

interface CronoMeasure {
  id: number;
  name: string;
  value: number; // grams per unit
  amount: number;
  type: string;
}

interface CronoFoodDetail {
  id: number;
  name: string;
  source: string;
  measures: CronoMeasure[];
  nutrients: CronoNutrient[];
  defaultMeasureId: number;
}

async function cronoGetFood(session: CronoSession, foodId: number): Promise<CronoFoodDetail | null> {
  const res = await fetchT(`${CRONO_BASE}/api/v2/get_food`, {
    method: "POST",
    headers: CRONO_HEADERS,
    body: JSON.stringify({
      id: foodId,
      config: { call_version: 1 },
      auth: authBlock(session),
      lastSeen: 0,
    }),
  });

  const body = await res.json();
  if (!body.id) return null;
  return body;
}

// Cronometer nutrient IDs → canonical names matching our food_nutrients table
const NUTRIENT_MAP: Record<number, { name: string; unit: string }> = {
  // Macros
  208: { name: "calories", unit: "kcal" },
  203: { name: "protein", unit: "g" },
  204: { name: "fat", unit: "g" },
  205: { name: "carbs", unit: "g" },
  291: { name: "fiber", unit: "g" },
  269: { name: "sugar", unit: "g" },
  209: { name: "starch", unit: "g" },
  307: { name: "sodium", unit: "mg" },
  255: { name: "water", unit: "g" },
  221: { name: "alcohol", unit: "g" },
  // Detailed fats
  606: { name: "saturated_fat", unit: "g" },
  645: { name: "monounsaturated_fat", unit: "g" },
  646: { name: "polyunsaturated_fat", unit: "g" },
  605: { name: "trans_fat", unit: "g" },
  601: { name: "cholesterol", unit: "mg" },
  10001: { name: "omega_3", unit: "g" },
  10002: { name: "omega_6", unit: "g" },
  621: { name: "dha", unit: "g" },
  629: { name: "epa", unit: "g" },
  851: { name: "ala", unit: "g" },
  // Vitamins
  318: { name: "vitamin_a", unit: "IU" },
  401: { name: "vitamin_c", unit: "mg" },
  324: { name: "vitamin_d", unit: "IU" },
  323: { name: "vitamin_e", unit: "mg" },
  430: { name: "vitamin_k", unit: "mcg" },
  404: { name: "thiamin_b1", unit: "mg" },
  405: { name: "riboflavin_b2", unit: "mg" },
  406: { name: "niacin_b3", unit: "mg" },
  410: { name: "pantothenic_acid_b5", unit: "mg" },
  415: { name: "vitamin_b6", unit: "mg" },
  417: { name: "folate_b9", unit: "mcg" },
  418: { name: "vitamin_b12", unit: "mcg" },
  421: { name: "choline", unit: "mg" },
  // Minerals
  301: { name: "calcium", unit: "mg" },
  303: { name: "iron", unit: "mg" },
  304: { name: "magnesium", unit: "mg" },
  305: { name: "phosphorus", unit: "mg" },
  306: { name: "potassium", unit: "mg" },
  309: { name: "zinc", unit: "mg" },
  312: { name: "copper", unit: "mg" },
  315: { name: "manganese", unit: "mg" },
  317: { name: "selenium", unit: "mcg" },
  // Amino acids
  501: { name: "tryptophan", unit: "g" },
  502: { name: "threonine", unit: "g" },
  503: { name: "isoleucine", unit: "g" },
  504: { name: "leucine", unit: "g" },
  505: { name: "lysine", unit: "g" },
  506: { name: "methionine", unit: "g" },
  507: { name: "cystine", unit: "g" },
  508: { name: "phenylalanine", unit: "g" },
  509: { name: "tyrosine", unit: "g" },
  510: { name: "valine", unit: "g" },
  511: { name: "arginine", unit: "g" },
  512: { name: "histidine", unit: "g" },
  513: { name: "alanine", unit: "g" },
  514: { name: "aspartic_acid", unit: "g" },
  515: { name: "glutamic_acid", unit: "g" },
  516: { name: "glycine", unit: "g" },
  517: { name: "proline", unit: "g" },
  518: { name: "serine", unit: "g" },
  // Other
  262: { name: "caffeine", unit: "mg" },
};

interface ResolvedItem {
  query: string;
  cronometer_food_id: number | null;
  cronometer_food_name: string | null;
  cronometer_source: string | null;
  search_score: number | null;
  quantity_g: number;
  serving_used: string | null;
  nutrients_per_100g: Record<string, number>;
  nutrients_for_portion: Record<string, number>;
  all_measures: Array<{ name: string; grams: number }>;
  match_quality: "exact" | "good" | "weak" | "none";
}

function resolveNutrients(food: CronoFoodDetail, quantityG: number): {
  per100g: Record<string, number>;
  forPortion: Record<string, number>;
} {
  const per100g: Record<string, number> = {};
  const forPortion: Record<string, number> = {};
  const scale = quantityG / 100;

  for (const n of food.nutrients) {
    const mapped = NUTRIENT_MAP[n.id];
    if (!mapped || n.amount === 0) continue;
    per100g[mapped.name] = Math.round(n.amount * 1000) / 1000;
    forPortion[mapped.name] = Math.round(n.amount * scale * 100) / 100;
  }

  return { per100g, forPortion };
}

// "one banana", "2 eggs", "150 g rice", "a cup of oats": the leading count/unit is the portion, the rest is the food.
const COUNT_WORDS: Record<string, number> = {
  a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, half: 0.5,
};
const WEIGHT_UNIT_GRAMS: Record<string, number> = { g: 1, gram: 1, kg: 1000, oz: 28.35, ounce: 28.35 };

interface Portion {
  count: number;
  unit: string | null; // lower-case, singular; null when the count is of the food itself ("2 eggs")
  rest: string;
}

function parsePortion(desc: string): Portion | null {
  const m = desc.trim().match(
    /^(\d+(?:[.,]\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|half)\s*(g|grams?|kg|oz|ounces?|ml|cups?|tbsp|tablespoons?|tsp|teaspoons?|slices?|pieces?|scoops?|servings?|bowls?|glass(?:es)?|bottles?|cans?)?\b\s*(?:of\s+)?(.+)$/i,
  );
  if (!m) return null;
  const raw = m[1].toLowerCase();
  const count = COUNT_WORDS[raw] ?? Number(raw.replace(",", "."));
  const rest = m[3].trim();
  if (!Number.isFinite(count) || count <= 0 || !rest) return null;
  const u = m[2]?.toLowerCase();
  const unit = u ? (u.startsWith("glass") ? "glass" : u.replace(/s$/, "")) : null;
  return { count, unit, rest };
}

const UNIT_ALIASES: Record<string, string[]> = {
  tbsp: ["tbsp", "tablespoon"], tablespoon: ["tbsp", "tablespoon"], tsp: ["tsp", "teaspoon"], teaspoon: ["tsp", "teaspoon"],
};

const PLAIN_WEIGHT_MEASURE = /^\s*[\d.]*\s*(g|gram|grams|oz|ounce|ounces|ml)\s*$/i;

// Grams for the portion from the food's own serving sizes. Null when nothing trustworthy is known (caller then uses 100 g).
function portionGrams(food: CronoFoodDetail, portion: Portion): { grams: number; label: string } | null {
  const usable = food.measures.filter((m) => m.value > 0 && !PLAIN_WEIGHT_MEASURE.test(m.name));
  let measure: CronoMeasure | undefined;
  if (portion.unit && portion.unit !== "serving") {
    const names = UNIT_ALIASES[portion.unit] ?? [portion.unit];
    measure = usable.find((m) => names.some((n) => m.name.toLowerCase().includes(n)));
  } else {
    measure = usable.find((m) => m.id === food.defaultMeasureId) ?? usable[0];
  }
  if (!measure) return null;
  const grams = portion.count * measure.value;
  if (!(grams >= 1 && grams <= 3000)) return null;
  return { grams: Math.round(grams * 10) / 10, label: `${portion.count} x ${measure.name}` };
}

// Words that describe cooking/preparation but not the food itself.
const MODIFIER_WORDS = new Set([
  "cooked", "raw", "fresh", "frozen", "dried", "dry", "canned",
  "grilled", "baked", "fried", "deep-fried", "pan-fried", "stir-fried",
  "roasted", "steamed", "boiled", "poached", "smoked", "braised",
  "sauteed", "sautéed", "blanched", "seared", "pan-seared",
  "sliced", "chopped", "diced", "minced", "ground", "whole", "mashed",
  "with", "and", "in", "on", "of", "the", "a", "an",
  "salted", "unsalted", "seasoned", "plain", "flavored",
  "lean", "boneless", "skinless",
  "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "half", "couple",
  "small", "medium", "large", "piece", "pieces", "slice", "slices",
]);

function extractFoodNouns(text: string): string[] {
  return text
    .toLowerCase()
    .replace(/[,()]/g, " ")
    .split(/\s+/)
    .filter((w) => w.length > 1 && !MODIFIER_WORDS.has(w));
}

// Preparation words that change what the food is (raw is the baseline, so it is not here).
const PREP_WORDS = new Set([
  "cooked", "frozen", "dried", "dry", "canned", "grilled", "baked", "fried", "deep-fried", "pan-fried", "stir-fried",
  "roasted", "steamed", "boiled", "poached", "smoked", "braised", "sauteed", "sautéed", "blanched", "seared", "pan-seared",
  "mashed", "salted", "seasoned", "flavored",
]);

function words(text: string): string[] {
  return text.toLowerCase().replace(/[,()]/g, " ").split(/\s+/).filter(Boolean);
}

// How far the name strays from the query: food words the query did not ask for ("cream", "pie" for "banana") plus
// preparation words it did not ask for ("fried"), so plain "Bananas, raw" beats "Banana cream pie" and "Banana, fried".
function extraNounCount(query: string, foodName: string): number {
  const queryNouns = extractFoodNouns(query);
  const extraNouns = extractFoodNouns(foodName).filter((nw) =>
    !queryNouns.some((qw) => nw === qw || nw.includes(qw) || qw.includes(nw))
  ).length;
  const queryWords = new Set(words(query));
  const extraPrep = words(foodName).filter((w) => PREP_WORDS.has(w) && !queryWords.has(w)).length;
  return extraNouns + extraPrep;
}

function assessMatchQuality(query: string, foodName: string, score?: number): "exact" | "good" | "weak" | "none" {
  const q = query.toLowerCase().trim();
  const n = foodName.toLowerCase().trim();

  // Exact match: the name is the query, or contains it without adding other foods ("Bananas, raw" for "banana", but not
  // "Banana cream pie").
  if (q === n) return "exact";
  if (n.includes(q)) return extraNounCount(q, n) === 0 ? "exact" : "good";

  const queryNouns = extractFoodNouns(q);
  const nameNouns = extractFoodNouns(n);

  if (queryNouns.length === 0) return "none";

  // Count how many core food nouns from the query appear in the food name
  const matchedNouns = queryNouns.filter((w) =>
    nameNouns.some((nw) => nw === w || nw.includes(w) || w.includes(nw))
  );
  const nounMatchRatio = matchedNouns.length / queryNouns.length;

  // Also check: does the food name contain nouns NOT in the query that
  // signal a completely different food? (e.g. "cheese" when query is "oysters")
  const extraNouns = nameNouns.filter((nw) =>
    !queryNouns.some((qw) => nw === qw || nw.includes(qw) || qw.includes(nw))
  );

  if (nounMatchRatio >= 0.8) return "good";
  if (nounMatchRatio >= 0.5 && extraNouns.length <= 2) return "good";
  if (nounMatchRatio >= 0.3 && extraNouns.length <= 1) return "weak";

  // If core nouns don't match, it's a bad result no matter what
  return "none";
}

// Generic USDA/government sources have richer nutrient profiles than branded entries
const PREFERRED_SOURCES = new Set(["USDAsr", "NCCDB", "CFCD", "USDAleg"]);

function sourceRank(source: string): number {
  // Check if source starts with a preferred prefix (e.g. "NCCDB:8535")
  const prefix = source.split(":")[0];
  if (PREFERRED_SOURCES.has(prefix)) return 2;
  // FDC:Branded is least preferred
  if (source.startsWith("FDC:Branded")) return 0;
  return 1;
}

function isBetterMatch(
  candidate: { quality: "exact" | "good" | "weak" | "none"; source: string; nutrientCount: number; extra: number },
  current: { quality: "exact" | "good" | "weak" | "none"; source: string; nutrientCount: number; extra: number },
): boolean {
  const qDiff = qualityRank(candidate.quality) - qualityRank(current.quality);
  if (qDiff !== 0) return qDiff > 0;
  // Same match quality — prefer generic USDA sources over branded
  const sDiff = sourceRank(candidate.source) - sourceRank(current.source);
  if (sDiff !== 0) return sDiff > 0;
  // Same source tier — the plainer entry wins ("Bananas, raw" over "Banana nut bread")
  if (candidate.extra !== current.extra) return candidate.extra < current.extra;
  // Same source tier — prefer the entry with more nutrients populated
  return candidate.nutrientCount > current.nutrientCount;
}

async function tryLookup(
  session: CronoSession,
  query: string,
  quantityG: number,
  portion?: Portion,
): Promise<ResolvedItem | null> {
  const searchResults = await cronoFindFood(session, query);
  if (searchResults.length === 0) return null;

  // Check the top 5 results for the best match (USDA sources over branded). Details are fetched together, not one by one.
  let bestMatch: { food: CronoFoodDetail; searchResult: CronoFoodSearchResult; quality: "exact" | "good" | "weak" | "none"; extra: number } | null = null;

  const candidates = searchResults.slice(0, 5)
    .map((candidate) => ({ candidate, quality: assessMatchQuality(query, candidate.name, candidate.score ?? undefined) }))
    .filter((c) => c.quality !== "none");
  const details = await Promise.all(candidates.map((c) => cronoGetFood(session, c.candidate.id).catch(() => null)));

  for (let idx = 0; idx < candidates.length; idx++) {
    const { candidate, quality } = candidates[idx];
    const food = details[idx];
    if (!food) continue;
    const nutrientCount = food.nutrients.filter((n) => n.amount > 0).length;
    const extra = extraNounCount(query, candidate.name);
    if (!bestMatch || isBetterMatch(
      { quality, source: candidate.source, nutrientCount, extra },
      { quality: bestMatch.quality, source: bestMatch.searchResult.source, nutrientCount: bestMatch.food.nutrients.filter((n) => n.amount > 0).length, extra: bestMatch.extra },
    )) {
      bestMatch = { food, searchResult: candidate, quality, extra };
    }
  }

  if (!bestMatch) {
    // All top results were "none" quality — return a none result with the top hit for transparency
    const top = searchResults[0];
    return {
      query,
      cronometer_food_id: top.id,
      cronometer_food_name: top.name,
      cronometer_source: top.source,
      search_score: top.score ?? null,
      quantity_g: quantityG,
      serving_used: null,
      nutrients_per_100g: {},
      nutrients_for_portion: {},
      all_measures: [],
      match_quality: "none",
    };
  }

  const { food, searchResult, quality } = bestMatch;
  const defaultMeasure = food.measures.find((m) => m.id === food.defaultMeasureId);
  const resolved = portion ? portionGrams(food, portion) : null;
  const grams = resolved?.grams ?? quantityG;
  const { per100g, forPortion } = resolveNutrients(food, grams);

  return {
    query,
    cronometer_food_id: food.id,
    cronometer_food_name: food.name,
    cronometer_source: food.source,
    search_score: searchResult.score ?? null,
    quantity_g: grams,
    serving_used: resolved?.label ?? defaultMeasure?.name ?? "100g",
    nutrients_per_100g: per100g,
    nutrients_for_portion: forPortion,
    all_measures: food.measures.map((m) => ({ name: m.name, grams: m.value })),
    match_quality: quality,
  };
}

function qualityRank(q: "exact" | "good" | "weak" | "none"): number {
  return { exact: 3, good: 2, weak: 1, none: 0 }[q];
}

// Simplify a query by stripping cooking modifiers to get a more generic search
function simplifyQuery(query: string): string | null {
  const nouns = extractFoodNouns(query);
  if (nouns.length === 0) return null;
  const simplified = nouns.join(" ");
  return simplified !== query.toLowerCase().trim() ? simplified : null;
}

async function lookupItem(
  session: CronoSession,
  description: string,
  quantityG?: number,
  portion?: Portion,
): Promise<ResolvedItem> {
  const effectiveG = quantityG ?? 100;

  // First attempt: full query
  let result = await tryLookup(session, description, effectiveG, portion);

  // If no good match, try simplified query (strip modifiers)
  if (!result || result.match_quality === "none") {
    const simplified = simplifyQuery(description);
    if (simplified) {
      const retry = await tryLookup(session, simplified, effectiveG, portion);
      if (retry && qualityRank(retry.match_quality) > qualityRank(result?.match_quality ?? "none")) {
        retry.query = description; // keep original query for context
        result = retry;
      }
    }
  }

  return result ?? {
    query: description,
    cronometer_food_id: null,
    cronometer_food_name: null,
    cronometer_source: null,
    search_score: null,
    quantity_g: effectiveG,
    serving_used: null,
    nutrients_per_100g: {},
    nutrients_for_portion: {},
    all_measures: [],
    match_quality: "none",
  };
}

// ---------------------------------------------------------------------------
// Gemini decomposition — split an aggregate meal description into items
// ---------------------------------------------------------------------------

// Same two flash-lite tiers as nutrition-estimate-text/-image: the second answers when the first is overloaded or slow.
const DECOMPOSE_MODELS = ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite"];

const DECOMPOSE_PROMPT = `You are given a description of a meal (possibly from a photo). Break it down into individual food items with estimated gram weights.

For each item, provide:
- description: a USDA-standard ingredient name that would appear in the USDA FoodData Central or NCCDB databases. Always include the preparation state (raw, cooked, boiled, etc.).
- quantity_g: your best estimate of the gram weight for a typical single-person portion of this item as described
- notes: any relevant preparation detail (optional)

NAMING RULES — use these exact patterns:
- Proteins: "chicken breast, cooked" / "beef steak, cooked" / "pork, ground, cooked" / "oysters, raw" / "shrimp, cooked" / "eggs, scrambled"
- Grains: "rice, white, cooked" / "rice, brown, cooked" / "spaghetti, cooked" / "noodles, egg, cooked" / "bread, white"
- Vegetables: "spinach, cooked" / "broccoli, cooked" / "cabbage, raw" / "carrots, cooked" / "onions, cooked"
- Fats/oils: "vegetable oil" / "olive oil" / "butter" (never "cooking oil")
- Starches: "cornstarch" / "all-purpose flour" / "tapioca starch" (never "flour batter" or "starch batter")
- Sauces: "soy sauce" / "sweet chili sauce" / "ketchup" / "fish sauce"
- Dairy: "whole milk" / "cheddar cheese" / "yogurt, plain"

DECOMPOSITION RULES:
- Always decompose composite dishes into base ingredients — never search for a dish name
- For fried foods, list the food + coating flour/starch + absorbed oil separately
- For soups/noodle dishes, list noodles + protein + broth + vegetables separately
- Specify "raw" for uncooked seafood/meat in dishes where it gets cooked during assembly (omelets, stir-fries)
- Specify "cooked" for pre-cooked proteins or items served ready to eat

Examples:
  "oyster omelet with sauce" →
    eggs, scrambled (~120g) + oysters, raw (~80g) + tapioca starch (~30g) + vegetable oil (~15g) + sweet chili sauce (~25g)
  "beef noodle soup" →
    egg noodles, cooked (~200g) + beef chuck, cooked (~150g) + beef broth (~200g) + bok choy, cooked (~50g)
  "stir-fried morning glory" →
    spinach, cooked (~100g) + vegetable oil (~10g) + garlic, raw (~5g)

Description: """`;

const DECOMPOSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    items: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          description: { type: "STRING" },
          quantity_g: { type: "NUMBER" },
          notes: { type: "STRING" },
        },
        required: ["description", "quantity_g"],
      },
    },
  },
  required: ["items"],
};

interface DecomposedItem {
  description: string;
  quantity_g: number;
  notes?: string;
}

async function decomposeMealDescription(
  apiKey: string,
  description: string,
): Promise<DecomposedItem[]> {
  const requestBody = JSON.stringify({
    contents: [{ parts: [{ text: DECOMPOSE_PROMPT + description.trim() + '"""' }] }],
    generationConfig: {
      response_mime_type: "application/json",
      response_schema: DECOMPOSE_SCHEMA,
    },
  });

  for (const model of DECOMPOSE_MODELS) {
    try {
      const res = await fetchT(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
        { method: "POST", headers: { "Content-Type": "application/json" }, body: requestBody },
        20000,
      );
      if (!res.ok) {
        console.log(`decompose ${model}: HTTP ${res.status}`);
        continue;
      }
      const body = await res.json();
      const text = body?.candidates?.[0]?.content?.parts?.[0]?.text;
      const parsed = text ? JSON.parse(text) : null;
      if (Array.isArray(parsed?.items) && parsed.items.length > 0) return parsed.items;
    } catch (e) {
      console.log(`decompose ${model}: ${String(e)}`);
    }
  }
  return [];
}

// ---------------------------------------------------------------------------
// Single-item vs compound-meal detection
// ---------------------------------------------------------------------------

function looksCompound(desc: string): boolean {
  // Non-ASCII likely needs Gemini to translate/decompose
  if (/[^\x00-\x7F]/.test(desc)) return true;
  // Long descriptions are usually compound meals
  if (desc.length > 80) return true;
  // Multiple commas suggest a list of items
  if ((desc.match(/,/g) ?? []).length >= 2) return true;
  // Conjunctions joining food items
  if (/\b(and|with|plus)\b/i.test(desc)) return true;
  // Slash-separated items
  if (/\w\s*\/\s*\w/.test(desc) && desc.split("/").length >= 2) return true;
  return false;
}

// ---------------------------------------------------------------------------
// Main handler
// ---------------------------------------------------------------------------

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Auth gate — same pattern as every other nutrition function
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return json({ error: "missing_auth" }, 401);

    const callerClient = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_ANON_KEY")!,
      { global: { headers: { Authorization: authHeader } } },
    );
    const token = authHeader.replace("Bearer ", "");
    const { data: { user }, error: userError } = await callerClient.auth.getUser(token);
    if (userError || !user) return json({ error: "unauthorized" }, 401);

    // Cronometer credentials
    const cronoEmail = Deno.env.get("CRONOMETER_EMAIL");
    const cronoPassword = Deno.env.get("CRONOMETER_PASSWORD");
    if (!cronoEmail || !cronoPassword) {
      return json({
        error: "not_configured",
        message: "CRONOMETER_EMAIL and CRONOMETER_PASSWORD must be set as Edge Function secrets.",
      }, 500);
    }

    let body: {
      items?: Array<{ description: string; quantity_g?: number }>;
      description?: string;
    };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    // Login to Cronometer
    const session = await cronoLogin(cronoEmail, cronoPassword);

    const geminiKey = Deno.env.get("GEMINI_API_KEY");

    // Mode 1: direct item lookup
    if (body.items && Array.isArray(body.items) && body.items.length > 0) {
      const wanted = body.items.filter((item) => item.description?.trim());
      const results = await mapLimit(wanted, 4, (item) => lookupItem(session, item.description, item.quantity_g));

      const { totals, nutrient_units, included, skipped } = sumNutrients(results);
      return json({ mode: "direct", results, totals, nutrient_units, items_included: included, items_skipped: skipped }, 200);
    }

    // Mode 2: decompose an aggregate description, then look up each item
    if (body.description?.trim()) {
      const desc = body.description.trim();

      // Simple descriptions (single food item) go through direct lookup
      // to avoid Gemini inflating portions with decomposition guesses.
      if (!looksCompound(desc)) {
        // "one banana": look up "banana" and size the portion from its own serving data, not a flat 100 g.
        const portion = parsePortion(desc);
        const weightG = portion?.unit && WEIGHT_UNIT_GRAMS[portion.unit] ? portion.count * WEIGHT_UNIT_GRAMS[portion.unit] : undefined;
        const result = await lookupItem(session, portion?.rest ?? desc, weightG, weightG === undefined && portion ? portion : undefined);
        const results = [result];
        const { totals, nutrient_units, included, skipped } = sumNutrients(results);
        return json({
          mode: "direct_auto",
          original_description: desc,
          results,
          totals,
          nutrient_units,
          items_included: included,
          items_skipped: skipped,
        }, 200);
      }

      if (!geminiKey) {
        return json({
          error: "not_configured",
          message: "GEMINI_API_KEY needed for decomposition mode.",
        }, 500);
      }

      const decomposed = await decomposeMealDescription(geminiKey, desc);
      if (decomposed.length === 0) {
        return json({ error: "decomposition_failed", message: "Could not split the meal into foods (Gemini did not answer in time).", description: desc }, 502);
      }

      const results = await mapLimit(decomposed, 4, (item) => lookupItem(session, item.description, item.quantity_g));

      const { totals, nutrient_units, included, skipped } = sumNutrients(results);
      return json({
        mode: "decompose",
        original_description: desc,
        decomposed,
        results,
        totals,
        nutrient_units,
        items_included: included,
        items_skipped: skipped,
      }, 200);
    }

    return json({ error: "missing_input", message: "Provide either 'items' or 'description'." }, 400);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});

function sumNutrients(results: ResolvedItem[]): {
  totals: Record<string, number>;
  nutrient_units: Record<string, string>;
  included: number;
  skipped: string[];
} {
  const totals: Record<string, number> = {};
  const skipped: string[] = [];
  let included = 0;

  const unitLookup: Record<string, string> = {};
  for (const entry of Object.values(NUTRIENT_MAP)) {
    unitLookup[entry.name] = entry.unit;
  }

  for (const r of results) {
    // Only include exact/good matches in totals — weak/none pollute accuracy
    if (r.match_quality === "none" || r.match_quality === "weak") {
      skipped.push(r.query);
      continue;
    }
    included++;
    for (const [key, val] of Object.entries(r.nutrients_for_portion)) {
      totals[key] = (totals[key] ?? 0) + val;
    }
  }
  for (const key of Object.keys(totals)) {
    totals[key] = Math.round(totals[key] * 100) / 100;
  }

  const nutrient_units: Record<string, string> = {};
  for (const key of Object.keys(totals)) {
    if (unitLookup[key]) nutrient_units[key] = unitLookup[key];
  }

  return { totals, nutrient_units, included, skipped };
}
