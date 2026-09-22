// nutrition-estimate-text — DAV-166
//
// Server-side Gemini call for natural-language meal/beverage logging.
// Gemini extracts structured *candidates* only (food description, quantity,
// confidence, ambiguity, beverage fields) -- it is never asked for
// authoritative nutrient values, per this ticket's own explicit rule.
// DAV-164's deterministic resolver is what turns a candidate into real
// numbers once it's matched (by text search, here or in a later review step)
// to a canonical food row.
//
// The Gemini key is a Supabase Edge Function secret (GEMINI_API_KEY) --
// unlike this app's existing Gemini features (NutritionEstimationRepository,
// LabExtractionRepository, SupplementImpactRepository), which call Gemini
// directly from the Android client using a key the user pastes into
// Settings. This ticket explicitly requires the key stay server-side; that
// requirement applies to this new candidate-extraction flow only -- the
// existing client-side features are unrelated and untouched.

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

// Same primary/fallback pair this app's client-side Gemini calls already use
// (GeminiClient.kt's postGeminiWithFallback) -- kept in sync manually since
// this function is a separate runtime (Deno, not the Android app).
const MODEL = "gemini-3.8-flash";
const FALLBACK_MODEL = "gemini-3.5-flash-lite";
const MODEL_VERSION = "1";

const PROMPT = `Extract structured food and beverage logging candidates from the text below.
For each distinct food or beverage item mentioned, extract:
- description: a short, clear description of the item
- quantityValue and quantityUnit: only if an amount is explicitly stated (e.g. "250 g", "1 cup", "2 slices")
- quantityLow and quantityHigh: a plausible range only if the amount is vague (e.g. "a bowl of", "a handful") -- omit if quantityValue was given
- preparation: cooking/preparation state if mentioned (e.g. "grilled", "raw"), else omit
- isBeverage: true if this item is a drink
- beverageClass: one of water/coffee/tea/milk/plant_milk/soda/juice/electrolyte/alcohol if isBeverage and determinable, else omit
- caffeineMg: ONLY if the text states an explicit caffeine amount, or names a specific well-known product/preparation where a caffeine amount is safely known (e.g. "one espresso shot"). Omit otherwise -- never estimate a precise number.
- foodConfidence: 0 to 1, how confident you are in the food identity
- portionConfidence: 0 to 1, how confident you are in the quantity
- ambiguous: true if the food identity itself is genuinely unclear and a person should confirm it

Do NOT provide calories, protein, fat, carbs, fiber, sugar, sodium, or any other nutrient amounts for any item -- nutrient totals are computed separately from a food database, never estimated by you. If the text describes a composite dish, decompose it into separate items only when the components are reasonably distinguishable; otherwise return it as one item.

Text: """`;

const RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    items: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          description: { type: "STRING" },
          quantity_value: { type: "NUMBER" },
          quantity_unit: { type: "STRING" },
          quantity_low: { type: "NUMBER" },
          quantity_high: { type: "NUMBER" },
          preparation: { type: "STRING" },
          is_beverage: { type: "BOOLEAN" },
          beverage_class: { type: "STRING" },
          caffeine_mg: { type: "NUMBER" },
          food_confidence: { type: "NUMBER" },
          portion_confidence: { type: "NUMBER" },
          ambiguous: { type: "BOOLEAN" },
        },
        required: ["description", "is_beverage", "food_confidence", "portion_confidence", "ambiguous"],
      },
    },
  },
  required: ["items"],
};

async function callGemini(apiKey: string, text: string): Promise<{ status: number; body: string }> {
  const requestBody = JSON.stringify({
    contents: [{ parts: [{ text: PROMPT + text.trim() + '"""' }] }],
    generationConfig: { response_mime_type: "application/json", response_schema: RESPONSE_SCHEMA },
  });

  async function attempt(model: string) {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: requestBody },
    );
    return { status: res.status, body: await res.text() };
  }

  let result = await attempt(MODEL);
  for (const delayMs of [1000, 2000]) {
    if (result.status !== 503) return result;
    await new Promise((r) => setTimeout(r, delayMs));
    result = await attempt(MODEL);
  }
  if (result.status === 503) result = await attempt(FALLBACK_MODEL);
  return result;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
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

    const apiKey = Deno.env.get("GEMINI_API_KEY");
    if (!apiKey) {
      return json({ error: "not_configured", message: "GEMINI_API_KEY must be set as an Edge Function secret." }, 500);
    }

    let body: { text?: string };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const text = body.text?.trim();
    if (!text) return json({ error: "missing_text" }, 400);

    const startedAt = Date.now();
    const { status, body: geminiBodyText } = await callGemini(apiKey, text);
    const latencyMs = Date.now() - startedAt;

    const db = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    if (status < 200 || status >= 300) {
      await db.from("ai_estimates").insert({
        user_id: user.id,
        model: MODEL,
        model_version: MODEL_VERSION,
        prompt_text: text,
        raw_response: safeParseJson(geminiBodyText),
        latency_ms: latencyMs,
        accepted: false,
      });
      return json({ error: "gemini_error", message: `Gemini request failed (${status})` }, 502);
    }

    const geminiResponse = safeParseJson(geminiBodyText);
    const candidateText = geminiResponse?.candidates?.[0]?.content?.parts?.[0]?.text;
    const parsedOutput = candidateText ? safeParseJson(candidateText) : null;

    // Persist the audit trail regardless of downstream validation outcome --
    // ai_estimates.meal_id/meal_item_id/meal_input_id are all nullable
    // (DAV-161), so this can exist before a meal is created; DAV-168's
    // review/save step links it once a real meal_item exists.
    const { data: estimateRow, error: insertError } = await db
      .from("ai_estimates")
      .insert({
        user_id: user.id,
        model: MODEL,
        model_version: MODEL_VERSION,
        prompt_text: text,
        raw_response: geminiResponse,
        parsed_output: parsedOutput,
        latency_ms: latencyMs,
        accepted: null,
      })
      .select("id")
      .single();
    if (insertError) return json({ error: "db_error", message: insertError.message }, 500);

    const items = Array.isArray(parsedOutput?.items) ? parsedOutput.items : null;
    if (!items) {
      // Invalid/unparseable model output fails safely -- the audit row above
      // still captures exactly what came back, for debugging.
      return json({ error: "invalid_model_output", estimateId: estimateRow.id }, 502);
    }

    return json({ estimateId: estimateRow.id, items }, 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});

function safeParseJson(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
