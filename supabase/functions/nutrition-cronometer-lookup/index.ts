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

  const res = await fetch(`${CRONO_BASE}/api/v2/login`, {
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
  const res = await fetch(`${CRONO_BASE}/api/v2/find_food`, {
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
  const res = await fetch(`${CRONO_BASE}/api/v2/get_food`, {
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
  208: { name: "calories", unit: "kcal" },
  203: { name: "protein", unit: "g" },
  204: { name: "fat", unit: "g" },
  205: { name: "carbs", unit: "g" },
  291: { name: "fiber", unit: "g" },
  269: { name: "sugar", unit: "g" },
  307: { name: "sodium", unit: "mg" },
  221: { name: "alcohol", unit: "g" },
  606: { name: "saturated_fat", unit: "g" },
  601: { name: "cholesterol", unit: "mg" },
  605: { name: "trans_fat", unit: "g" },
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
  // Fatty acids
  10001: { name: "omega_3", unit: "g" },
  10002: { name: "omega_6", unit: "g" },
  // Caffeine
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

function estimateGramsFromDescription(description: string, measure?: CronoMeasure): number {
  // If we have a default measure, use its gram weight
  if (measure && measure.value > 0) return measure.value;
  // Fallback: 100g (per-100g basis)
  return 100;
}

function assessMatchQuality(query: string, foodName: string, score?: number): "exact" | "good" | "weak" | "none" {
  const q = query.toLowerCase().trim();
  const n = foodName.toLowerCase().trim();
  if (q === n || n.includes(q)) return "exact";
  const qWords = q.split(/\s+/);
  const matchedWords = qWords.filter((w) => n.includes(w));
  if (matchedWords.length >= qWords.length * 0.7) return "good";
  if (matchedWords.length >= 1) return "weak";
  return "none";
}

async function lookupItem(
  session: CronoSession,
  description: string,
  quantityG?: number,
): Promise<ResolvedItem> {
  const searchResults = await cronoFindFood(session, description);

  if (searchResults.length === 0) {
    return {
      query: description,
      cronometer_food_id: null,
      cronometer_food_name: null,
      cronometer_source: null,
      search_score: null,
      quantity_g: quantityG ?? 100,
      serving_used: null,
      nutrients_per_100g: {},
      nutrients_for_portion: {},
      all_measures: [],
      match_quality: "none",
    };
  }

  const best = searchResults[0];
  const food = await cronoGetFood(session, best.id);

  if (!food) {
    return {
      query: description,
      cronometer_food_id: best.id,
      cronometer_food_name: best.name,
      cronometer_source: best.source,
      search_score: best.score ?? null,
      quantity_g: quantityG ?? 100,
      serving_used: null,
      nutrients_per_100g: {},
      nutrients_for_portion: {},
      all_measures: [],
      match_quality: assessMatchQuality(description, best.name, best.score ?? undefined),
    };
  }

  const defaultMeasure = food.measures.find((m) => m.id === food.defaultMeasureId);
  const effectiveG = quantityG ?? estimateGramsFromDescription(description, defaultMeasure);
  const { per100g, forPortion } = resolveNutrients(food, effectiveG);

  return {
    query: description,
    cronometer_food_id: food.id,
    cronometer_food_name: food.name,
    cronometer_source: food.source,
    search_score: best.score ?? null,
    quantity_g: effectiveG,
    serving_used: defaultMeasure?.name ?? "100g",
    nutrients_per_100g: per100g,
    nutrients_for_portion: forPortion,
    all_measures: food.measures.map((m) => ({ name: m.name, grams: m.value })),
    match_quality: assessMatchQuality(description, food.name, best.score ?? undefined),
  };
}

// ---------------------------------------------------------------------------
// Gemini decomposition — split an aggregate meal description into items
// ---------------------------------------------------------------------------

const DECOMPOSE_MODEL = "gemini-3.5-flash-lite";

const DECOMPOSE_PROMPT = `You are given a description of a meal (possibly from a photo). Break it down into individual food items with estimated gram weights.

For each item, provide:
- description: a short, clear name suitable for searching a food database (e.g. "brown rice, cooked" not "a bed of rice")
- quantity_g: your best estimate of the gram weight for a typical single-person portion of this item as described
- notes: any relevant preparation detail (optional)

Be specific and practical:
- "Pan-seared steak" → "beef steak" ~200g
- "brown rice" → "brown rice, cooked" ~200g
- "Chinese water spinach" → "water spinach, cooked" ~100g

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
  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${DECOMPOSE_MODEL}:generateContent?key=${apiKey}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{ parts: [{ text: DECOMPOSE_PROMPT + description.trim() + '"""' }] }],
        generationConfig: {
          response_mime_type: "application/json",
          response_schema: DECOMPOSE_SCHEMA,
        },
      }),
    },
  );

  const body = await res.json();
  const text = body?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) return [];
  try {
    const parsed = JSON.parse(text);
    return Array.isArray(parsed.items) ? parsed.items : [];
  } catch {
    return [];
  }
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
      const results: ResolvedItem[] = [];
      for (const item of body.items) {
        if (!item.description?.trim()) continue;
        results.push(await lookupItem(session, item.description, item.quantity_g));
      }

      const totals = sumNutrients(results);
      return json({ mode: "direct", results, totals }, 200);
    }

    // Mode 2: decompose an aggregate description, then look up each item
    if (body.description?.trim()) {
      if (!geminiKey) {
        return json({
          error: "not_configured",
          message: "GEMINI_API_KEY needed for decomposition mode.",
        }, 500);
      }

      const decomposed = await decomposeMealDescription(geminiKey, body.description);
      if (decomposed.length === 0) {
        return json({ error: "decomposition_failed", description: body.description }, 502);
      }

      const results: ResolvedItem[] = [];
      for (const item of decomposed) {
        results.push(await lookupItem(session, item.description, item.quantity_g));
      }

      const totals = sumNutrients(results);
      return json({
        mode: "decompose",
        original_description: body.description,
        decomposed,
        results,
        totals,
      }, 200);
    }

    return json({ error: "missing_input", message: "Provide either 'items' or 'description'." }, 400);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});

function sumNutrients(results: ResolvedItem[]): Record<string, number> {
  const totals: Record<string, number> = {};
  for (const r of results) {
    for (const [key, val] of Object.entries(r.nutrients_for_portion)) {
      totals[key] = (totals[key] ?? 0) + val;
    }
  }
  // Round totals
  for (const key of Object.keys(totals)) {
    totals[key] = Math.round(totals[key] * 100) / 100;
  }
  return totals;
}
