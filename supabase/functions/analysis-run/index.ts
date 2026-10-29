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
// Publishes `series_break` (Phase 1), `relationship` (Phase 3, the confirmatory catalogue), `typicality` (Phase 4,
// "is today typical for you"), `expected_band` (analysis review phase C: each morning against a forecast band, with
// flags), `event_curve`, `case_crossover` and `lag_curve` (analysis review phase D: the evidence board's three views),
// `experiment` (Phase 5, pre-registered supplement N-of-1; analysis review phase F adds the randomised designs) and
// `intervention_response` (Intervention & Protocol Intelligence 08.6, observational baseline-vs-active for any active
// intervention) and `intervention_its` (analysis review phase F: each intervention read once against a forecast from
// the eight weeks before it started) and `fitness_fatigue` (analysis review phase G: the personal fitness and fatigue
// spans, adopted only when they forecast tomorrow's HRV better than 42 and 7 on held-out weeks) results; the `typicality`
// payload carries its scorecard against logged illness.
// The action `experiment_preview` returns the smallest detectable effect of a planned design for the signed-in user.
// Analysis hub phase C (docs/analysis-console/PLAN.md): `integrity`, one row a night with the last 28 days of every
// stream of metric-series.json (coverage, freshness, implausible, conflicting and duplicate rows, source switches).
// Health concerns (docs/health-concerns): `health_overview`, `health_concern_state`, `health_finding` and
// `health_trial_read` rows, nightly after the evidence engines, and on demand with the action `health` (user JWT; the app
// calls it right after a check-in so the new state shows at once).

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { buildPreview, runExperiments } from "../_shared/analysis-core/runExperiments.ts";
import { runInterventionIts, runInterventionResponses } from "../_shared/analysis-core/runInterventionResponse.ts";
import { runEventEvidence } from "../_shared/analysis-core/runEventEvidence.ts";
import { loadFrame, runRelationships } from "../_shared/analysis-core/runRelationships.ts";
import { runEvidence } from "../_shared/analysis-core/runEvidence.ts";
import { runExpectedBands } from "../_shared/analysis-core/runExpectedBands.ts";
import { runFitnessFatigue } from "../_shared/analysis-core/runFitnessFatigue.ts";
import { runHealth } from "../_shared/analysis-core/runHealth.ts";
import { runIntegrity } from "../_shared/analysis-core/runIntegrity.ts";
import type { SeriesFile } from "../_shared/analysis-core/seriesContract.ts";
import seriesFile from "../_shared/metric-registry/metric-series.json" with { type: "json" };
import { runSeriesBreaks } from "../_shared/analysis-core/runSeriesBreaks.ts";
import { runTypicality } from "../_shared/analysis-core/runTypicality.ts";
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

  let body: { action?: string; user_id?: string; planned_a?: number; planned_b?: number } = {};
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

  if (body.action === "experiment_preview") {
    // Pre-registration preview for the signed-in user: what could a design with these phase lengths detect on their own data?
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const userClient = createClient(url, anon, { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } });
    const { data: { user } } = await userClient.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    const plannedA = Math.round(body.planned_a ?? 28);
    const plannedB = Math.round(body.planned_b ?? 28);
    if (!(plannedA >= 7 && plannedA <= 120 && plannedB >= 7 && plannedB <= 120)) return json({ error: "bad_request", message: "planned_a and planned_b must be 7 to 120 days" }, 400);
    const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    const loaded = await loadFrame(admin, user.id);
    if ("error" in loaded) return json({ error: "load_failed", message: loaded.error }, 500);
    return json(await buildPreview(admin, loaded, plannedA, plannedB), 200);
  }

  if (body.action === "health") {
    // On demand for the signed-in user only: the frame (for sleep, load and the HRV band) and the health engine.
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const userClient = createClient(url, anon, { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } });
    const { data: { user } } = await userClient.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    const loaded = await loadFrame(admin, user.id);
    if ("error" in loaded) return json({ error: "load_failed", message: loaded.error }, 500);
    const health = await runHealth(admin, loaded).catch((e) => ({ userId: user.id, error: String(e) }));
    return json({ coreVersion: CORE_VERSION, health }, "error" in health && health.error ? 500 : 200);
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
      // Integrity reads its own rows, so a frame that fails to load does not hide it.
      const integrity = await runIntegrity(admin, id, seriesFile as SeriesFile).catch((e) => ({ userId: id, error: String(e) }));
      // The frame is read once and shared; one engine failing must not hide the others: each reports its own error.
      // Typicality and the expected bands run after the series breaks because they restart at confirmed ones.
      const loaded = await loadFrame(admin, id).catch((e) => ({ userId: id, error: String(e) }));
      if ("error" in loaded) {
        results.push({ ...breaks, integrity, relationships: loaded, typicality: loaded, expectedBands: loaded, evidence: loaded, experiments: loaded });
        continue;
      }
      const relationships = await runRelationships(admin, id, new Date(), loaded).catch((e) => ({ userId: id, error: String(e) }));
      const typicality = await runTypicality(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      const expectedBands = await runExpectedBands(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      const evidence = await runEvidence(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      const experiments = await runExperiments(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      const interventionResponses = await runInterventionResponses(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      const interventionIts = await runInterventionIts(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      const eventEvidence = await runEventEvidence(admin, loaded, seriesFile as SeriesFile).catch((e) => ({ userId: id, error: String(e) }));
      const fitnessFatigue = await runFitnessFatigue(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      const health = await runHealth(admin, loaded).catch((e) => ({ userId: id, error: String(e) }));
      results.push({ ...breaks, integrity, relationships, typicality, expectedBands, evidence, experiments, interventionResponses, interventionIts, eventEvidence, fitnessFatigue, health });
    }
    return json({ coreVersion: CORE_VERSION, results }, 200);
  }

  return json({ error: "unknown_action" }, 400);
});
