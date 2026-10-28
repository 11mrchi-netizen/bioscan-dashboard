// health-agent - the personal health agent, phase 1: EXPLAIN (docs/personal-health-agent/README.md).
//
// The person taps WHY? on a card; the app sends the card's own words and values as context. One agent turn
// (agent-core/turn.ts) runs: symptom triage, a tool loop over the person's own data, the guards with one regeneration,
// then the reply that is shown. Every turn is kept in agent_turns. Nothing else is written: no health row, no decision.
//
// All reads use the caller's JWT, so row-level security applies, and pass agent-core/scope.ts. The assistant answers
// only when agent_settings.enabled is true for the caller; it is off by default.
//
// Model: AGENT_MODEL (default requesty/novita/ling-3.1-flash, zero retention); the route refuses endpoints that log
// prompts (agent-core/routes.ts, purpose 'live'). AGENT_DAILY_CAP limits turns per person per day (default 40).
//
// Actions (POST JSON, user JWT):
//   explain { question, context, client_turn_id, local_now }  -> { turn_id, shown, refs, regenerated, violations, triage }
//   rate    { turn_id, rating 1-5 }                            -> { ok }

import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import { makeExecutor, type Db, type Query, type Row } from "../_shared/agent-core/executor.ts";
import { modelConfig } from "../_shared/agent-core/routes.ts";
import { openAiChat } from "../_shared/agent-core/models.ts";
import { OPT_IN_GROUPS, type OptInGroup, type ScopeSettings } from "../_shared/agent-core/scope.ts";
import { runTurn } from "../_shared/agent-core/turn.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

const DEFAULT_MODEL = "requesty/novita/ling-3.1-flash";
const PAGE = 1000;
const MAX_ROWS = 5000;
// Reasoning models spend part of the output cap on hidden reasoning; 2048 leaves room for a short reply.
const MAX_TOKENS = 2048;

/** The executor's Db over supabase-js with the caller's JWT; pages past the API's 1000-row cap. */
function supabaseDb(db: SupabaseClient): Db {
  return async (q: Query) => {
    const want = Math.min(q.limit ?? MAX_ROWS, MAX_ROWS);
    const out: Row[] = [];
    for (let from = 0; from < want; from += PAGE) {
      let b = db.from(q.table).select(q.columns);
      for (const [k, v] of Object.entries(q.eq ?? {})) b = b.eq(k, v);
      for (const [k, v] of Object.entries(q.in ?? {})) b = b.in(k, v);
      for (const [k, v] of Object.entries(q.gte ?? {})) b = b.gte(k, v);
      for (const [k, v] of Object.entries(q.lte ?? {})) b = b.lte(k, v);
      if (q.order) b = b.order(q.order.column, { ascending: q.order.ascending });
      const to = Math.min(from + PAGE, want) - 1;
      const { data, error } = await b.range(from, to);
      if (error) throw new Error(error.message);
      out.push(...((data ?? []) as Row[]));
      if (!data || data.length < to - from + 1) break;
    }
    return out;
  };
}

const ISO_LOCAL = /^(\d{4}-\d{2}-\d{2})T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return json({ error: "missing_auth" }, 401);
    const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: authHeader } } });
    const { data: { user }, error: userError } = await db.auth.getUser(authHeader.replace("Bearer ", ""));
    if (userError || !user) return json({ error: "unauthorized" }, 401);

    let body: { action?: string; question?: string; context?: Record<string, unknown>; client_turn_id?: string; local_now?: string; turn_id?: string; rating?: number };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    if (body.action === "rate") {
      if (!body.turn_id || !Number.isInteger(body.rating) || body.rating! < 1 || body.rating! > 5) return json({ error: "bad_request" }, 400);
      const { error } = await db.from("agent_turns").update({ rating: body.rating }).eq("id", body.turn_id);
      return error ? json({ error: "db_error", message: error.message }, 500) : json({ ok: true }, 200);
    }

    if (body.action !== "explain") return json({ error: "unknown_action" }, 400);
    const question = body.question?.trim();
    const turnId = body.client_turn_id?.trim();
    const local = ISO_LOCAL.exec(body.local_now ?? "");
    if (!question || !turnId || !local) return json({ error: "missing_fields", message: "question, client_turn_id and local_now (ISO with offset) are required" }, 400);
    if (question.length > 1000) return json({ error: "question_too_long" }, 400);
    const today = local[1];

    // Off unless the person turned it on.
    const { data: s } = await db.from("agent_settings").select("enabled,opt_in").maybeSingle();
    if (!s?.enabled) return json({ error: "assistant_off", message: "Turn the assistant on in Setup to use WHY?." }, 403);
    const settings: ScopeSettings = { enabled: true, optIn: ((s.opt_in ?? []) as string[]).filter((g): g is OptInGroup => (OPT_IN_GROUPS as string[]).includes(g)) };

    // A retried turn returns what was shown the first time.
    const { data: prior } = await db.from("agent_turns").select("id,shown,refs,regenerated,violations,triage").eq("client_turn_id", turnId).maybeSingle();
    if (prior) return json({ turn_id: prior.id, shown: prior.shown, refs: prior.refs, regenerated: prior.regenerated, violations: (prior.violations as unknown[]).length, triage: prior.triage, replayed: true }, 200);

    const cap = Number(Deno.env.get("AGENT_DAILY_CAP") ?? 40);
    const { count } = await db.from("agent_turns").select("id", { count: "exact", head: true }).gte("created_at", `${today}T00:00:00${local[2] === "Z" ? "+00:00" : local[2]}`);
    if ((count ?? 0) >= cap) return json({ error: "daily_cap", message: `The assistant has answered ${cap} questions today; it will answer again tomorrow.` }, 429);

    const slug = Deno.env.get("AGENT_MODEL") ?? DEFAULT_MODEL;
    const cfg = modelConfig(slug, (n) => Deno.env.get(n), { purpose: "live", maxTokens: MAX_TOKENS, timeoutMs: 40_000 });
    if ("error" in cfg) return json({ error: "not_configured", message: cfg.error }, 500);

    const context = body.context && typeof body.context === "object" ? body.context : {};
    const started = Date.now();
    let turn;
    let failure: string | null = null;
    try {
      turn = await runTurn({ mode: "explain", question, context, settings, today, chat: openAiChat(cfg), executeTool: makeExecutor(supabaseDb(db), settings, today), maxSteps: 6 });
    } catch (e) {
      failure = String(e).slice(0, 300);
    }
    const shown = turn?.shown ?? "The assistant could not answer right now. Try again in a minute.";
    const refs = (turn?.outputs ?? []).flatMap((o) => {
      const results = (o.data as { results?: { ref?: string }[] })?.results;
      return Array.isArray(results) ? results.map((r) => r.ref).filter((x): x is string => typeof x === "string") : [];
    });
    const row = {
      client_turn_id: turnId,
      mode: "explain",
      question,
      context,
      model: slug,
      calls: turn?.calls ?? [],
      refs,
      violations: turn?.violations ?? [],
      regenerated: turn?.regenerated ?? false,
      triage: turn?.triage?.level ?? null,
      shown,
      steps: turn?.steps ?? 0,
      tokens_prompt: turn?.tokens.prompt ?? 0,
      tokens_completion: turn?.tokens.completion ?? 0,
      latency_ms: Date.now() - started,
      error: failure,
    };
    const { data: saved, error: insertError } = await db.from("agent_turns").insert(row).select("id").single();
    if (insertError) return json({ error: "db_error", message: insertError.message }, 500);
    if (failure) return json({ error: "model_error", message: shown, turn_id: saved.id }, 502);
    return json({ turn_id: saved.id, shown, refs, regenerated: row.regenerated, violations: row.violations.length, triage: row.triage }, 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});
