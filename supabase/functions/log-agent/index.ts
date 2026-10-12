// log-agent -- Analysis Layer 3 / R2 (docs/analysis-layer-3).
//
// Propose-only natural-language logging. One message in, a list of PROPOSED rows out; this function never writes a health row.
// The app shows the proposals, the person confirms or edits them, and the app writes them through the same repositories as the
// forms. All rules (number grounding, ethanol grams, dates, roster matching) live in _shared/log-core/proposal.ts and are
// unit-tested; Gemini only reads the text into loosely typed entries.
//
// Actions (POST JSON, user JWT):
//   propose  { text, client_turn_id, local_now, }   -> { run_id, proposals, unparsed }   (idempotent per client_turn_id)
//   committed { run_id, count }                      -> { ok }                            (audit: how many rows the app wrote)
// The Gemini key is an Edge Function secret (GEMINI_API_KEY). Database access uses the caller's JWT (RLS), no service role.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { LOG_AGENT_PROMPT, LOG_AGENT_RESPONSE_SCHEMA } from "../_shared/log-core/geminiSchema.ts";
import { buildProposals, parseClock, type RawEntry } from "../_shared/log-core/proposal.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

// Same two flash-lite tiers (separate daily quotas) as nutrition-estimate-text.
const MODEL = "gemini-3.5-flash-lite";
const FALLBACK_MODEL = "gemini-3.1-flash-lite";
const MAX_TEXT = 1000;

async function callGemini(apiKey: string, text: string): Promise<{ status: number; body: string; model: string }> {
  const requestBody = JSON.stringify({
    contents: [{ parts: [{ text: LOG_AGENT_PROMPT + text + '"""' }] }],
    generationConfig: { response_mime_type: "application/json", response_schema: LOG_AGENT_RESPONSE_SCHEMA, temperature: 0 },
  });
  async function attempt(model: string) {
    const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: requestBody,
    });
    return { status: res.status, body: await res.text(), model };
  }
  let result = await attempt(MODEL);
  if (result.status === 429) return await attempt(FALLBACK_MODEL);
  for (const delayMs of [1000, 2000]) {
    if (result.status !== 503) return result;
    await new Promise((r) => setTimeout(r, delayMs));
    result = await attempt(MODEL);
  }
  if (result.status === 503 || result.status === 429) result = await attempt(FALLBACK_MODEL);
  return result;
}

function safeParseJson(text: string): any {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return json({ error: "missing_auth" }, 401);
    const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: authHeader } } });
    const { data: { user }, error: userError } = await db.auth.getUser(authHeader.replace("Bearer ", ""));
    if (userError || !user) return json({ error: "unauthorized" }, 401);

    let body: { action?: string; text?: string; client_turn_id?: string; local_now?: string; run_id?: string; count?: number };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    if (body.action === "committed") {
      if (!body.run_id || !Number.isInteger(body.count) || body.count! < 0) return json({ error: "bad_request" }, 400);
      const { error } = await db.from("log_agent_runs").update({ committed_at: new Date().toISOString(), committed_count: body.count }).eq("id", body.run_id);
      return error ? json({ error: "db_error", message: error.message }, 500) : json({ ok: true }, 200);
    }

    if (body.action !== "propose") return json({ error: "unknown_action" }, 400);
    const text = body.text?.trim();
    const turnId = body.client_turn_id?.trim();
    if (!text || !turnId || !body.local_now) return json({ error: "missing_fields", message: "text, client_turn_id and local_now are required" }, 400);
    if (text.length > MAX_TEXT) return json({ error: "text_too_long", max: MAX_TEXT }, 400);
    try {
      parseClock(body.local_now);
    } catch {
      return json({ error: "bad_local_now", message: "local_now must be an ISO time with offset, e.g. 2026-10-12T21:05:00+08:00" }, 400);
    }

    // Retried turn: return what was proposed the first time.
    const { data: prior } = await db.from("log_agent_runs").select("id,proposals,unparsed").eq("client_turn_id", turnId).maybeSingle();
    if (prior) return json({ run_id: prior.id, proposals: prior.proposals, unparsed: prior.unparsed, replayed: true }, 200);

    const apiKey = Deno.env.get("GEMINI_API_KEY");
    if (!apiKey) return json({ error: "not_configured", message: "GEMINI_API_KEY must be set as an Edge Function secret." }, 500);

    const { data: roster, error: rosterError } = await db.from("supplements").select("id,name").eq("status", "active");
    if (rosterError) return json({ error: "db_error", message: rosterError.message }, 500);

    const startedAt = Date.now();
    const { status, body: geminiText, model } = await callGemini(apiKey, text);
    const latencyMs = Date.now() - startedAt;
    const gemini = safeParseJson(geminiText);
    const candidateText = gemini?.candidates?.[0]?.content?.parts?.[0]?.text;
    const entries: RawEntry[] | null = status >= 200 && status < 300 && candidateText ? safeParseJson(candidateText)?.entries : null;

    if (!Array.isArray(entries)) {
      // Keep the failed attempt for debugging, but under a distinct turn id so a retry of the same turn is allowed.
      await db.from("log_agent_runs").insert({ client_turn_id: `${turnId}#failed#${Date.now()}`, utterance: text, model, latency_ms: latencyMs, raw_output: gemini });
      return json({ error: status >= 200 && status < 300 ? "invalid_model_output" : "gemini_error", message: `Gemini request failed (${status})` }, 502);
    }

    const { proposals, unparsed } = buildProposals(text, entries, { nowLocalIso: body.local_now, roster: roster ?? [] });
    const { data: run, error: insertError } = await db
      .from("log_agent_runs")
      .insert({ client_turn_id: turnId, utterance: text, model, latency_ms: latencyMs, raw_output: entries, proposals, unparsed })
      .select("id")
      .single();
    if (insertError) return json({ error: "db_error", message: insertError.message }, 500);
    return json({ run_id: run.id, proposals, unparsed }, 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});
