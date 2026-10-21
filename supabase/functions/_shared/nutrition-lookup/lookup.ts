// Cronometer food lookup core, shared by nutrition-cronometer-lookup (the app's live lookup) and nutrition-backfill
// (re-checking meals already logged). Searches Cronometer's food database via their unofficial mobile REST API.
//
// A single food ("one apple") is looked up directly; a compound meal is split into items with gram weights by Gemini
// first. Either way Gemini also gives an independent calorie/macro estimate per item, which stands in for items with
// no trustworthy match and flags matches that disagree with it. Matching, portion, plausibility and reconciliation
// rules live in matching.ts (tested by matching.test.ts). A user's confirmed food choices (food_match_memory) are
// reused before searching.

import type { SupabaseClient } from "npm:@supabase/supabase-js@2";
import {
  type Alternative,
  confidence,
  type CronoFoodDetail,
  type CronoFoodSearchResult,
  defaultState,
  implausibility,
  looksCompound,
  type MacroEstimate,
  memoryKey,
  parseAmount,
  type Portion,
  portionGrams,
  type Quality,
  qualityRank,
  rankCandidates,
  reconcile,
  type ResolvedItem,
  resolveNutrients,
  simplifyQuery,
  sumNutrients,
  WEIGHT_UNIT_GRAMS,
} from "./matching.ts";

export type { ResolvedItem } from "./matching.ts";

// ---------------------------------------------------------------------------
// Cronometer mobile API client
// ---------------------------------------------------------------------------

const CRONO_BASE = "https://mobile.cronometer.com";
const CRONO_HEADERS = {
  "Content-Type": "text/plain; charset=utf-8",
  "User-Agent": "Dart/3.9 (dart:io)",
  "Accept-Encoding": "gzip",
};

export interface CronoSession {
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
export async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
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

export async function cronoLogin(email: string, password: string): Promise<CronoSession> {
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

// ---------------------------------------------------------------------------
// Item resolution
// ---------------------------------------------------------------------------

const SEARCH_POOL = 20; // search hits considered per query
const DETAIL_FETCH = 3; // best-ranked candidates fetched in full for the plausibility check

export function emptyItem(query: string, grams: number): ResolvedItem {
  return {
    query,
    cronometer_food_id: null,
    cronometer_food_name: null,
    cronometer_source: null,
    search_score: null,
    quantity_g: grams,
    serving_used: null,
    nutrients_per_100g: {},
    nutrients_for_portion: {},
    all_measures: [],
    match_quality: "none",
    alternatives: [],
    rejected: [],
    memory_key: memoryKey(query),
    portion_count: null,
    portion_unit: null,
    from_memory: false,
    ai_estimate: null,
    used_estimate: false,
    disagreement: false,
  };
}

function itemFromFood(
  query: string,
  food: CronoFoodDetail,
  grams: number,
  label: string | null,
  quality: Quality,
): ResolvedItem {
  const { per100g, forPortion } = resolveNutrients(food, grams);
  return {
    ...emptyItem(query, grams),
    cronometer_food_id: food.id,
    cronometer_food_name: food.name,
    cronometer_source: food.source,
    serving_used: label ?? `${grams} g`,
    nutrients_per_100g: per100g,
    nutrients_for_portion: forPortion,
    all_measures: food.measures.filter((m) => m.value > 0).map((m) => ({ name: m.name, grams: m.value })),
    match_quality: quality,
  };
}

function gramsFor(food: CronoFoodDetail, quantityG?: number, portion?: Portion): { grams: number; label: string | null } {
  if (quantityG != null && quantityG > 0) return { grams: quantityG, label: null };
  const resolved = portion ? portionGrams(food, portion) : null;
  return resolved ?? { grams: 100, label: null };
}

// A plain query is also searched in its usual state ("apple, raw", "rice, cooked"), so the plain entry is in the pool
// even when Cronometer's popularity ranking buries it under recipes and brands.
async function searchCandidates(session: CronoSession, query: string): Promise<CronoFoodSearchResult[]> {
  const state = defaultState(query);
  const queries = state ? [query, `${query}, ${state}`] : [query];
  const lists = await Promise.all(queries.map((q) => cronoFindFood(session, q).catch(() => [])));
  const seen = new Set<number>();
  const out: CronoFoodSearchResult[] = [];
  for (const list of lists) {
    for (const r of list.slice(0, SEARCH_POOL)) {
      if (!seen.has(r.id)) {
        seen.add(r.id);
        out.push(r);
      }
    }
  }
  return out;
}

function kcalPer100g(food: CronoFoodDetail | null | undefined): number | null {
  return food?.nutrients.find((n) => n.id === 208)?.amount ?? null;
}

async function tryLookup(
  session: CronoSession,
  query: string,
  quantityG?: number,
  portion?: Portion,
  liquid = false,
): Promise<ResolvedItem | null> {
  const results = await searchCandidates(session, query);
  if (results.length === 0) return null;

  const ranked = rankCandidates(query, results);
  if (ranked.length === 0) {
    // Nothing resembles the query — keep the top hit for transparency, contribute nothing.
    const top = results[0];
    return {
      ...emptyItem(query, quantityG ?? 100),
      cronometer_food_id: top.id,
      cronometer_food_name: top.name,
      cronometer_source: top.source,
      search_score: top.score ?? null,
    };
  }

  const top = ranked.slice(0, DETAIL_FETCH);
  const details = await Promise.all(top.map((c) => cronoGetFood(session, c.result.id).catch(() => null)));

  const rejected: string[] = [];
  let pick = -1;
  for (let i = 0; i < top.length; i++) {
    const food = details[i];
    if (!food) continue;
    const why = implausibility(food, query, liquid);
    if (why) {
      rejected.push(`${food.name}: ${why}`);
      continue;
    }
    pick = i;
    break;
  }

  // Every fetched candidate was implausible: keep the best-ranked one for display, but it contributes as "weak".
  let quality: Quality;
  if (pick === -1) {
    pick = details.findIndex(Boolean);
    if (pick === -1) return null;
    quality = "weak";
  } else {
    quality = top[pick].match.quality;
  }

  const food = details[pick]!;
  const { grams, label } = gramsFor(food, quantityG, portion);
  const alternatives: Alternative[] = ranked
    .filter((c) => c.result.id !== food.id)
    .slice(0, 6)
    .map((c) => {
      const i = top.indexOf(c);
      return { food_id: c.result.id, name: c.result.name, source: c.result.source, kcal_per_100g: i >= 0 ? kcalPer100g(details[i]) : null };
    });

  return {
    ...itemFromFood(query, food, grams, label, quality),
    search_score: top[pick].result.score ?? null,
    alternatives,
    rejected,
  };
}

async function searchItem(
  session: CronoSession,
  query: string,
  quantityG?: number,
  portion?: Portion,
  liquid = false,
): Promise<ResolvedItem> {
  let result = await tryLookup(session, query, quantityG, portion, liquid);

  if (!result || qualityRank(result.match_quality) < qualityRank("good")) {
    const simplified = simplifyQuery(query);
    if (simplified) {
      const retry = await tryLookup(session, simplified, quantityG, portion, liquid);
      if (retry && qualityRank(retry.match_quality) > qualityRank(result?.match_quality ?? "none")) {
        retry.query = query; // keep original query for context
        result = retry;
      }
    }
  }

  return result ?? emptyItem(query, quantityG ?? 100);
}

interface MemoryRow {
  query_key: string;
  cronometer_food_id: number;
  grams_per_unit: number | null;
  unit: string | null;
}

export type MemoryLoader = (queries: string[]) => Promise<Map<string, MemoryRow>>;

// One user's confirmed choices for these queries. Best-effort: lookups work the same without it.
export function memoryLoader(client: SupabaseClient, userId: string): MemoryLoader {
  return async (queries) => {
    const keys = [...new Set(queries.map(memoryKey).filter(Boolean))];
    if (keys.length === 0) return new Map();
    const { data, error } = await client
      .from("food_match_memory")
      .select("query_key, cronometer_food_id, grams_per_unit, unit")
      .eq("user_id", userId)
      .in("query_key", keys);
    if (error) {
      console.log(`food_match_memory: ${error.message}`);
      return new Map();
    }
    return new Map((data as MemoryRow[]).map((row) => [row.query_key, row]));
  };
}

interface ItemSpec {
  query: string;
  quantityG?: number;
  portion?: Portion;
  foodId?: number;
  liquid?: boolean;
}

async function resolveItem(session: CronoSession, spec: ItemSpec, memory: Map<string, MemoryRow>): Promise<ResolvedItem> {
  const key = memoryKey(spec.query);
  const portion = spec.portion;
  const portionCount = portion && !(portion.unit && WEIGHT_UNIT_GRAMS[portion.unit]) ? portion.count : null;
  const portionUnit = portionCount != null ? portion!.unit : null;
  const remembered = spec.foodId == null ? memory.get(key) : undefined;
  const foodId = spec.foodId ?? remembered?.cronometer_food_id;

  let item: ResolvedItem | null = null;
  if (foodId != null) {
    const food = await cronoGetFood(session, foodId).catch(() => null);
    if (food) {
      let quantityG = spec.quantityG;
      let label: string | null = null;
      if (quantityG == null && remembered?.grams_per_unit && portionCount != null && (remembered.unit ?? null) === portionUnit) {
        quantityG = Math.round(portionCount * remembered.grams_per_unit * 10) / 10;
        label = `${portionCount} x your usual portion`;
      }
      const g = gramsFor(food, quantityG, portion);
      item = itemFromFood(spec.query, food, g.grams, label ?? g.label, "exact");
      item.from_memory = remembered != null;
    }
  }
  item ??= await searchItem(session, spec.query, spec.quantityG, portion, spec.liquid ?? false);

  item.memory_key = key;
  item.portion_count = portionCount;
  item.portion_unit = portionUnit;
  return item;
}

function finalize(item: ResolvedItem, estimate: MacroEstimate | null | undefined, mode: string): ResolvedItem {
  item.ai_estimate = estimate ?? null;
  reconcile(item);
  console.log(JSON.stringify({
    mode,
    query: item.query,
    food: item.cronometer_food_name,
    source: item.cronometer_source,
    grams: item.quantity_g,
    kcal: item.nutrients_for_portion.calories ?? null,
    quality: item.match_quality,
    ai_kcal: item.ai_estimate?.calories ?? null,
    used_estimate: item.used_estimate,
    disagreement: item.disagreement,
    from_memory: item.from_memory,
    rejected: item.rejected,
  }));
  return item;
}

// ---------------------------------------------------------------------------
// Gemini: meal decomposition and the independent estimate
// ---------------------------------------------------------------------------

// Same two flash-lite tiers as nutrition-estimate-text/-image: the second answers when the first is overloaded or slow.
const GEMINI_MODELS = ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite"];

const DECOMPOSE_PROMPT = `You are given a description of a meal (possibly from a photo). Break it down into individual food items with estimated gram weights.

For each item, provide:
- description: a USDA-standard ingredient name that would appear in the USDA FoodData Central or NCCDB databases. Always include the preparation state (raw, cooked, boiled, etc.).
- quantity_g: your best estimate of the gram weight for a typical single-person portion of this item as described
- calories, protein_g, carbs_g, fat_g: your own estimate for that gram weight of the item
- notes: any relevant preparation detail (optional)

NAMING RULES — use these exact patterns:
- Proteins: "chicken breast, cooked" / "beef steak, cooked" / "pork, ground, cooked" / "oysters, raw" / "shrimp, cooked" / "eggs, scrambled"
- Grains: "rice, white, cooked" / "rice, brown, cooked" / "spaghetti, cooked" / "noodles, egg, cooked" / "bread, white"
- Vegetables: "spinach, cooked" / "broccoli, cooked" / "cabbage, raw" / "carrots, cooked" / "onions, cooked"
- Fruit: "apples, raw" / "bananas, raw" (a whole fruit is one medium fruit unless the size or count is given)
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
- Use the counts and amounts in the description exactly ("2 eggs" is two eggs, "30 g nuts" is 30 g)

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
          calories: { type: "NUMBER" },
          protein_g: { type: "NUMBER" },
          carbs_g: { type: "NUMBER" },
          fat_g: { type: "NUMBER" },
          notes: { type: "STRING" },
        },
        required: ["description", "quantity_g", "calories", "protein_g", "carbs_g", "fat_g"],
      },
    },
  },
  required: ["items"],
};

const ESTIMATE_PROMPT = `Estimate the nutrition of the food below as a typical person eats it. If no amount is given, assume one usual portion (one medium fruit, one cup of cooked rice, one egg). Grains, legumes and meat are cooked unless stated otherwise. Give calories, protein_g, carbs_g and fat_g for the whole amount described.

Food: """`;

const ESTIMATE_SCHEMA = {
  type: "OBJECT",
  properties: {
    calories: { type: "NUMBER" },
    protein_g: { type: "NUMBER" },
    carbs_g: { type: "NUMBER" },
    fat_g: { type: "NUMBER" },
  },
  required: ["calories", "protein_g", "carbs_g", "fat_g"],
};

interface DecomposedItem extends MacroEstimate {
  description: string;
  quantity_g: number;
  notes?: string;
}

async function gemini<T>(apiKey: string, prompt: string, schema: unknown, timeoutMs: number, label: string): Promise<T | null> {
  const requestBody = JSON.stringify({
    contents: [{ parts: [{ text: prompt }] }],
    generationConfig: { response_mime_type: "application/json", response_schema: schema, temperature: 0 },
  });
  for (const model of GEMINI_MODELS) {
    try {
      const res = await fetchT(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
        { method: "POST", headers: { "Content-Type": "application/json" }, body: requestBody },
        timeoutMs,
      );
      if (!res.ok) {
        console.log(`${label} ${model}: HTTP ${res.status}`);
        continue;
      }
      const body = await res.json();
      const text = body?.candidates?.[0]?.content?.parts?.[0]?.text;
      if (text) return JSON.parse(text) as T;
    } catch (e) {
      console.log(`${label} ${model}: ${String(e)}`);
    }
  }
  return null;
}

function asEstimate(e: Partial<MacroEstimate> | null | undefined): MacroEstimate | null {
  if (!e || typeof e.calories !== "number" || !(e.calories > 0)) return null;
  return { calories: e.calories, protein_g: e.protein_g ?? 0, carbs_g: e.carbs_g ?? 0, fat_g: e.fat_g ?? 0 };
}

async function decomposeMealDescription(apiKey: string, description: string): Promise<DecomposedItem[]> {
  const parsed = await gemini<{ items?: DecomposedItem[] }>(
    apiKey, DECOMPOSE_PROMPT + description.trim() + '"""', DECOMPOSE_SCHEMA, 20000, "decompose",
  );
  return Array.isArray(parsed?.items) ? parsed!.items.filter((i) => i.description?.trim()) : [];
}

async function estimateFood(apiKey: string | undefined, description: string): Promise<MacroEstimate | null> {
  if (!apiKey) return null;
  return asEstimate(await gemini<MacroEstimate>(apiKey, ESTIMATE_PROMPT + description.trim() + '"""', ESTIMATE_SCHEMA, 12000, "estimate"));
}

// ---------------------------------------------------------------------------
// Entry points
// ---------------------------------------------------------------------------

export interface LookupResult {
  mode: string;
  original_description?: string;
  decomposed?: DecomposedItem[];
  results: ResolvedItem[];
  totals: Record<string, number>;
  nutrient_units: Record<string, string>;
  items_included: number;
  items_skipped: string[];
  confidence: number;
  ai_estimate_total: number | null;
}

export interface LookupFailure {
  error: string;
  message: string;
  status: number;
}

export interface LookupContext {
  session: CronoSession;
  geminiKey: string | undefined;
  loadMemory: MemoryLoader;
}

function summarize(mode: string, results: ResolvedItem[], extra: Partial<LookupResult> = {}): LookupResult {
  const { totals, nutrient_units, included, skipped } = sumNutrients(results);
  const estimates = results.map((r) => r.ai_estimate);
  const ai_estimate_total = estimates.length > 0 && estimates.every(Boolean)
    ? Math.round(estimates.reduce((s, e) => s + e!.calories, 0))
    : null;
  return {
    mode,
    ...extra,
    results,
    totals,
    nutrient_units,
    items_included: included,
    items_skipped: skipped,
    confidence: confidence(results),
    ai_estimate_total,
  };
}

// Mode 1: direct item lookup (barcode products, a food the user picked, an edited portion).
export async function lookupItems(
  ctx: LookupContext,
  items: Array<{ description: string; quantity_g?: number; food_id?: number }>,
): Promise<LookupResult> {
  const wanted = items.filter((item) => item.description?.trim());
  const memory = await ctx.loadMemory(wanted.filter((i) => i.food_id == null).map((i) => i.description));
  const results = await mapLimit(wanted, 4, async (item) =>
    finalize(
      await resolveItem(ctx.session, { query: item.description, quantityG: item.quantity_g, foodId: item.food_id ?? undefined }, memory),
      null,
      "direct",
    ));
  return summarize("direct", results);
}

// Mode 2: a free-text description (typed by the user, or the photo estimate's description).
export async function lookupDescription(ctx: LookupContext, description: string): Promise<LookupResult | LookupFailure> {
  const desc = description.trim();

  // A single food ("one apple", "150 g rice"): look it up directly, sized from its own servings, and ask Gemini for an
  // independent estimate at the same time.
  if (!looksCompound(desc)) {
    // "20gr of dried tofu", "biscuit bar (30g)", "monster 355ml can": the weight or volume, wherever it is written; else a
    // count ("one apple"; a bare "apple" is one apple), where measures that are a volume never count as "one".
    const amount = parseAmount(desc);
    const memory = await ctx.loadMemory([amount.query]);
    const [item, estimate] = await Promise.all([
      resolveItem(ctx.session, { query: amount.query, quantityG: amount.weightG, portion: amount.portion, liquid: amount.liquid }, memory),
      estimateFood(ctx.geminiKey, desc),
    ]);
    return summarize("direct_auto", [finalize(item, estimate, "direct_auto")], { original_description: desc });
  }

  if (!ctx.geminiKey) {
    return { error: "not_configured", message: "GEMINI_API_KEY needed for decomposition mode.", status: 500 };
  }

  const decomposed = await decomposeMealDescription(ctx.geminiKey, desc);
  if (decomposed.length === 0) {
    // Splitting failed: a whole-meal AI estimate is still better than no number at all.
    const estimate = await estimateFood(ctx.geminiKey, desc);
    if (!estimate) {
      return { error: "decomposition_failed", message: "Could not split the meal into foods (Gemini did not answer in time).", status: 502 };
    }
    return summarize("estimate_only", [finalize(emptyItem(desc, 0), estimate, "estimate_only")], { original_description: desc });
  }

  const memory = await ctx.loadMemory(decomposed.map((d) => d.description));
  const results = await mapLimit(decomposed, 4, async (d) =>
    finalize(await resolveItem(ctx.session, { query: d.description, quantityG: d.quantity_g }, memory), asEstimate(d), "decompose"));
  return summarize("decompose", results, { original_description: desc, decomposed });
}

export function isFailure(r: LookupResult | LookupFailure): r is LookupFailure {
  return "error" in r;
}
