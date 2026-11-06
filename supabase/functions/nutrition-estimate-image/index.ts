// nutrition-estimate-image — DAV-167
//
// v3 (2026-11, photo logging v3): the meal's foods one by one -- generic name for the database, grams, preparation,
// assumptions, confidence and micronutrients -- which the app sends straight to the lookup (photo_items), plus the
// person's note (authoritative) and weighed total as inputs and up to three questions whose answers rerun it. The v2
// whole-meal fields below stay in the response, summed from the foods. Prompt and shaping live in estimate.ts.
//
// Server-side Gemini vision call for photo-based meal/beverage logging.
//
// v2 (2026-09-30, user request): reworked from "candidates only, matched
// against a food database" to "one approximate whole-meal estimate,
// directly usable." The original design (DAV-166/167's "never authoritative
// nutrients, the resolver is") required every detected item to be manually
// searched and matched against `foods` before a meal could even be saved --
// real friction for a quick photo log. This version asks Gemini to look at
// one or more photos of the SAME meal and return one combined, approximate
// nutrition estimate for everything visible, skipping database-matching
// entirely for this path. Text-description and barcode logging are
// unaffected -- they still go through the resolver, which stays exactly as
// implemented (still authoritative when a real database match exists).
//
// Photos are forwarded to Gemini inline (base64) and never written to
// Supabase Storage or any other persistent location -- nothing in this
// function writes the image bytes anywhere, they only exist for the
// duration of the one Gemini request.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { buildPrompt, RESPONSE_SCHEMA, shapeEstimate } from "./estimate.ts";

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

// DAV-220: moved off gemini-3.8-flash's scarce 20/day free-tier quota onto
// the two flash-lite tiers (500/day each), same reasoning as
// nutrition-estimate-text -- see that function's own comment.
const MODEL = "gemini-3.5-flash-lite";
const FALLBACK_MODEL = "gemini-3.1-flash-lite";
// 3: the meal's foods one by one (grams, preparation, assumptions, confidence, micronutrients), the person's note and
// plate weight as inputs, and up to three questions; the v2 whole-meal fields stay, summed from the foods.
const MODEL_VERSION = "3";

async function callGemini(apiKey: string, prompt: string, images: string[], mimeType: string): Promise<{ status: number; body: string; model: string }> {
  const requestBody = JSON.stringify({
    contents: [
      {
        parts: [
          { text: prompt },
          ...images.map((data) => ({ inline_data: { mime_type: mimeType, data } })),
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
    return { status: res.status, body: await res.text(), model };
  }

  let result = await attempt(MODEL);

  // DAV-220: 429 means MODEL's daily quota is exhausted -- go straight to
  // FALLBACK_MODEL's separate quota instead of retry-delaying a request
  // that can't succeed again until tomorrow.
  if (result.status === 429) return await attempt(FALLBACK_MODEL);

  for (const delayMs of [1000, 2000]) {
    if (result.status !== 503) return result;
    await new Promise((r) => setTimeout(r, delayMs));
    result = await attempt(MODEL);
  }
  if (result.status === 503 || result.status === 429) result = await attempt(FALLBACK_MODEL);
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

    // v3: note (what the person says the meal is, authoritative), totalWeightG (weighed food), answers (to the questions
    // of an earlier estimate of the same photos).
    let body: { images?: string[]; mimeType?: string; note?: string; totalWeightG?: number; answers?: Array<{ question: string; answer: string }> };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const images = body.images;
    const mimeType = body.mimeType || "image/jpeg";
    if (!images || !Array.isArray(images) || images.length === 0) return json({ error: "missing_image" }, 400);

    const startedAt = Date.now();
    const prompt = buildPrompt(body.note, body.totalWeightG, body.answers);
    const { status, body: geminiBodyText, model: servedByModel } = await callGemini(apiKey, prompt, images, mimeType);
    const inputLabel = `[image estimate v3, ${images.length} photo(s)` +
      (body.note?.trim() ? `; note: ${body.note.trim().slice(0, 200)}` : "") +
      (body.totalWeightG ? `; weighed ${body.totalWeightG} g` : "") +
      ((body.answers ?? []).length ? `; ${(body.answers ?? []).length} answer(s)` : "") + "]";
    const latencyMs = Date.now() - startedAt;

    const db = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    // The images themselves are never part of what gets persisted -- only
    // Gemini's text response and a marker of what kind of input produced it.
    if (status < 200 || status >= 300) {
      await db.from("ai_estimates").insert({
        user_id: user.id,
        model: servedByModel,
        model_version: MODEL_VERSION,
        prompt_text: inputLabel,
        raw_response: safeParseJson(geminiBodyText),
        latency_ms: latencyMs,
        accepted: false,
      });
      return json({ error: "gemini_error", message: `Gemini request failed (${status})` }, 502);
    }

    const geminiResponse = safeParseJson(geminiBodyText);
    const candidateText = geminiResponse?.candidates?.[0]?.content?.parts?.[0]?.text;
    const parsedOutput = candidateText ? shapeEstimate(safeParseJson(candidateText)) : null;

    const { data: estimateRow, error: insertError } = await db
      .from("ai_estimates")
      .insert({
        user_id: user.id,
        model: servedByModel,
        model_version: MODEL_VERSION,
        prompt_text: inputLabel,
        raw_response: geminiResponse,
        parsed_output: parsedOutput,
        latency_ms: latencyMs,
        accepted: null,
      })
      .select("id")
      .single();
    if (insertError) return json({ error: "db_error", message: insertError.message }, 500);

    if (!parsedOutput || typeof parsedOutput.calories !== "number") {
      return json({ error: "invalid_model_output", estimateId: estimateRow.id }, 502);
    }

    return json({ estimateId: estimateRow.id, estimate: parsedOutput }, 200);
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
