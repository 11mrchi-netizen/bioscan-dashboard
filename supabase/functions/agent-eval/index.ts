// agent-eval - runs live models against the personal health agent's golden set (docs/personal-health-agent, phase 0).
//
// Planted data only: no table is read, nothing is written, and no request carries the owner's data. The OpenRouter key
// is the Edge Function secret OPENROUTER_API. A model slug ending in ":free" runs with logged endpoints allowed (the free
// endpoints log prompts, which is acceptable for planted cases only); any other slug requires zero data retention.
//
// Caller: the scheduler secret in the x-analysis-cron header (the same Vault secret analysis-run accepts, checked through
// the service role-only public.analysis_cron_secret_ok()), so a run is started from SQL with pg_net and no human-held
// key. Users cannot call it: it spends the OpenRouter quota.
//
// POST { models: string[], only?: string, offset?: number, limit?: number }
//   -> { summary, runs, next_offset }   next_offset is null when the selected cases are done
// A request stops starting new cases after DEADLINE_MS so it ends inside the Edge Function wall-clock limit; call again
// with next_offset to continue.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { isFreeSlug, runCase, summarise, type CaseRun } from "../_shared/agent-core/evalRun.ts";
import { GOLDEN } from "../_shared/agent-core/golden.ts";

const DEADLINE_MS = 100_000;
const MAX_LIMIT = 12;
const CONCURRENCY = 3;

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

Deno.serve(async (req) => {
  try {
    const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    const secret = req.headers.get("x-analysis-cron");
    const { data: ok } = secret ? await admin.rpc("analysis_cron_secret_ok", { candidate: secret }) : { data: false };
    if (ok !== true) return json({ error: "unauthorized" }, 401);

    const apiKey = Deno.env.get("OPENROUTER_API");
    if (!apiKey) return json({ error: "not_configured", message: "OPENROUTER_API must be set as an Edge Function secret." }, 500);

    let body: { models?: string[]; only?: string; offset?: number; limit?: number };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const models = (body.models ?? []).filter((m) => typeof m === "string" && /^[a-z0-9._/:-]+$/i.test(m));
    if (!models.length || models.length > 4) return json({ error: "bad_request", message: "1 to 4 model slugs" }, 400);

    const selected = GOLDEN.cases.filter((c) => !body.only || c.id.startsWith(body.only));
    const offset = Math.max(0, Math.floor(body.offset ?? 0));
    const limit = Math.min(MAX_LIMIT, Math.max(1, Math.floor(body.limit ?? 6)));
    const batch = selected.slice(offset, offset + limit);

    const jobs = batch.flatMap((c) => models.map((model) => ({ c, model })));
    const runs: CaseRun[] = [];
    const doneCases = new Set<string>();
    const started = Date.now();
    let next = 0;
    async function worker() {
      while (next < jobs.length && Date.now() - started < DEADLINE_MS) {
        const { c, model } = jobs[next++];
        runs.push(await runCase(c, { baseUrl: "https://openrouter.ai/api/v1", apiKey: apiKey!, model, allowLogged: isFreeSlug(model), timeoutMs: 45_000 }));
      }
    }
    await Promise.all(Array.from({ length: CONCURRENCY }, worker));

    // A case counts as done when every model ran it; the next call starts at the first case that is not.
    for (const c of batch) if (models.every((m) => runs.some((r) => r.id === c.id && r.model === m))) doneCases.add(c.id);
    const firstOpen = batch.findIndex((c) => !doneCases.has(c.id));
    const reached = firstOpen === -1 ? offset + batch.length : offset + firstOpen;
    const nextOffset = reached < selected.length ? reached : null;
    const complete = runs.filter((r) => doneCases.has(r.id));
    return json({ goldenVersion: GOLDEN.version, cases: selected.length, offset, next_offset: nextOffset, summary: summarise(complete), runs: complete }, 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});
