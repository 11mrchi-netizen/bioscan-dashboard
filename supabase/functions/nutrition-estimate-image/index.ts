// nutrition-estimate-image — DAV-167
//
// Server-side Gemini vision call for photo-based meal/beverage logging.
// Same "candidates only, never authoritative nutrients" rule as
// nutrition-estimate-text (DAV-166) -- DAV-164's resolver is what turns a
// candidate matched against USDA/TFND/Open Food Facts into real numbers.
//
// The photo is forwarded to Gemini inline (base64, same shape this app's
// existing client-side NutritionEstimationRepository.kt already uses) and
// never written to Supabase Storage or any other persistent location --
// this ticket's own "original images are temporary/not retained permanently
// by default" requirement is satisfied by construction: nothing in this
// function writes the image bytes anywhere, they only exist for the
// duration of the one Gemini request.

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

const MODEL = "gemini-3.8-flash";
const FALLBACK_MODEL = "gemini-3.5-flash-lite";
const MODEL_VERSION = "1";

// Explicitly tells Gemini what a photo cannot reveal (DAV-167's own
// "Important limitation" section) rather than letting it imply false
// precision -- the low/high range exists specifically because a photo
// estimate is never as certain as a stated quantity.
const PROMPT = `Look at this food/beverage photo and extract structured logging candidates.
Photos cannot reliably reveal exact weight, hidden ingredients, cooking oil, or obscured
components -- always reflect that uncertainty in quantityLow/quantityHigh and portionConfidence
rather than stating a single precise weight as if measured.

For each distinct food or beverage item visible, extract:
- description: a short, clear description of the item
- quantity_low and quantity_high: a plausible gram/ml range for the visible portion
- quantity_unit: "g" or "ml"
- preparation: cooking/preparation state if visually apparent (e.g. "grilled", "fried"), else omit
- is_beverage: true if this item is a drink
- beverage_class: one of water/coffee/tea/milk/plant_milk/soda/juice/electrolyte/alcohol if is_beverage and visually determinable, else omit
- food_confidence: 0 to 1, how confident you are in the food identity
- portion_confidence: 0 to 1, how confident you are in the estimated portion range -- this should usually be modest, since a photo cannot weigh anything
- ambiguous: true if the food identity itself is genuinely unclear and a person should confirm it

Do NOT provide calories, protein, fat, carbs, fiber, sugar, sodium, caffeine, or any other nutrient
amounts -- nutrient totals are computed separately by matching each item against a food database,
never estimated by you from the image. Do not attempt to read any visible nutrition-label text --
base every estimate on visual inspection of the food itself.`;

const RESPONSE_SCHEMA = {
  type: "OBJECT",
  properties: {
    items: {
      type: "ARRAY",
      items: {
        type: "OBJECT",
        properties: {
          description: { type: "STRING" },
          quantity_low: { type: "NUMBER" },
          quantity_high: { type: "NUMBER" },
          quantity_unit: { type: "STRING" },
          preparation: { type: "STRING" },
          is_beverage: { type: "BOOLEAN" },
          beverage_class: { type: "STRING" },
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

async function callGemini(apiKey: string, imageBase64: string, mimeType: string): Promise<{ status: number; body: string }> {
  const requestBody = JSON.stringify({
    contents: [
      {
        parts: [
          { text: PROMPT },
          { inline_data: { mime_type: mimeType, data: imageBase64 } },
        ],
      },
    ],
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

    let body: { imageBase64?: string; mimeType?: string };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const imageBase64 = body.imageBase64;
    const mimeType = body.mimeType || "image/jpeg";
    if (!imageBase64) return json({ error: "missing_image" }, 400);

    const startedAt = Date.now();
    const { status, body: geminiBodyText } = await callGemini(apiKey, imageBase64, mimeType);
    const latencyMs = Date.now() - startedAt;

    const db = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    // The image itself is never part of what gets persisted -- only Gemini's
    // text response and a marker of what kind of input produced it.
    if (status < 200 || status >= 300) {
      await db.from("ai_estimates").insert({
        user_id: user.id,
        model: MODEL,
        model_version: MODEL_VERSION,
        prompt_text: "[image estimate]",
        raw_response: safeParseJson(geminiBodyText),
        latency_ms: latencyMs,
        accepted: false,
      });
      return json({ error: "gemini_error", message: `Gemini request failed (${status})` }, 502);
    }

    const geminiResponse = safeParseJson(geminiBodyText);
    const candidateText = geminiResponse?.candidates?.[0]?.content?.parts?.[0]?.text;
    const parsedOutput = candidateText ? safeParseJson(candidateText) : null;

    const { data: estimateRow, error: insertError } = await db
      .from("ai_estimates")
      .insert({
        user_id: user.id,
        model: MODEL,
        model_version: MODEL_VERSION,
        prompt_text: "[image estimate]",
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
