// analysis-run - Analysis Layer 3 publisher (docs/analysis-layer-3).
//
// Reads a user's raw rows, runs the shared TypeScript core (../_shared/analysis-core) and upserts versioned results
// into analysis_results. Dual-client pattern as in nutrition-estimate-text: a client carrying the caller's Authorization
// header proves who is asking; a service-role client does the reads and writes (analysis_results has no write policies
// for end users). The engines and their I/O live in the shared core so they are tested in Node (runSeriesBreaks.test.ts).
//
// Actions
//   selftest  -> numbers from constants only (proves the shared core bundles in Deno); no user data
//   run       -> series-break detection for the caller's own data (user JWT), or for a given/all users when the caller is
//                the scheduler: pg_cron -> private.run_analysis() sends a random secret that lives only in Vault in the
//                x-analysis-cron header; it is checked through public.analysis_cron_secret_ok() (service role only), so
//                no human-held secret and no service role key is involved
//
// Publishes `series_break` (Phase 1) and `relationship` (Phase 3, the confirmatory catalogue) results; the typicality and
// experiment engines plug in here later.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { runRelationships } from "../_shared/analysis-core/runRelationships.ts";
import { runSeriesBreaks } from "../_shared/analysis-core/runSeriesBreaks.ts";
import { pearson, pFromR } from "../_shared/analysis-core/stats.ts";
import { CORE_VERSION } from "../_shared/analysis-core/version.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-analysis-cron",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  let body: { action?: string; user_id?: string } = {};
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (body.action === "selftest") {
    // Golden values from the independent Python implementation (stats.test.ts): p(r=0.253, n=61) = 0.049155.
    return json({
      coreVersion: CORE_VERSION,
      p_r0253_n61: Number(pFromR(0.253, 61).toFixed(6)),
      pearson_1_10: Number(pearson([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], [2.1, 1.9, 3.5, 3.9, 5.2, 5.8, 7.7, 7.9, 9.4, 9.6]).toFixed(12)),
    }, 200);
  }

  if (body.action === "run") {
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const admin = createClient(url, service);

    // Scheduler: the Vault secret arrives in x-analysis-cron; only a service role-only SQL function can say if it matches.
    const cronHeader = req.headers.get("x-analysis-cron");
    let isScheduler = false;
    if (cronHeader) {
      const { data: ok } = await admin.rpc("analysis_cron_secret_ok", { candidate: cronHeader });
      isScheduler = ok === true;
    }

    let userIds: string[] = [];
    if (isScheduler) {
      if (body.user_id) userIds = [body.user_id];
      else {
        const { data } = await admin.from("wearable_daily").select("user_id").limit(1000);
        userIds = [...new Set(((data ?? []) as { user_id: string }[]).map((r) => r.user_id))];
      }
    } else {
      const authHeader = req.headers.get("Authorization") ?? "";
      const userClient = createClient(url, anon, { global: { headers: { Authorization: authHeader } } });
      const { data: { user } } = await userClient.auth.getUser();
      if (!user) return json({ error: "unauthorized" }, 401);
      userIds = [user.id];
    }

    const results = [];
    for (const id of userIds) {
      const breaks = await runSeriesBreaks(admin, id);
      // One engine failing must not hide the other: each reports its own error.
      const relationships = await runRelationships(admin, id).catch((e) => ({ userId: id, error: String(e) }));
      results.push({ ...breaks, relationships });
    }
    return json({ coreVersion: CORE_VERSION, results }, 200);
  }

  return json({ error: "unknown_action" }, 400);
});
