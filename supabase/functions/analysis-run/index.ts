// analysis-run - Analysis Layer 3 publisher (docs/analysis-layer-3).
//
// Reads a user's raw rows, runs the shared TypeScript core (../_shared/analysis-core) and upserts versioned results
// into analysis_results. Dual-client pattern as in nutrition-estimate-text: a client carrying the caller's Authorization
// header proves who is asking; a service-role client does the reads and writes (analysis_results has no write policies
// for end users). The engines and their I/O live in the shared core so they are tested in Node (runSeriesBreaks.test.ts).
//
// Actions
//   selftest  -> numbers from constants only (proves the shared core bundles in Deno); no user data
//   run       -> every engine, or one group of them with `group` (core | bands | interventions | fitness | health; see
//                runGroups.ts: one request per group keeps each under the Edge CPU limit). Series-break detection for the caller's own data (user JWT), or for a given/all users when the caller is
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
// Risk profile R1 (docs/risk-profile): `air` (group `exposures`) writes air_exposure_daily and one `air_exposure` summary;
// on demand with the action `air` (user JWT), so the app can refresh after a first setup.
// Weekly and monthly reports (docs/reports): `period_report` rows (group `reports`), nightly for the last completed week and
// month; on demand with the action `report` (user JWT) for given `keys` (week:<monday> | month:<yyyy-mm>) or a first fill
// of the last `weeks` weeks and `months` months.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { buildPreview, runExperiments } from "../_shared/analysis-core/runExperiments.ts";
import { runInterventionIts, runInterventionResponses } from "../_shared/analysis-core/runInterventionResponse.ts";
import { runEventEvidence } from "../_shared/analysis-core/runEventEvidence.ts";
import { loadFrame, RELATIONSHIP_LONG_LOOKBACK_DAYS, runRelationships } from "../_shared/analysis-core/runRelationships.ts";
import { runEvidence } from "../_shared/analysis-core/runEvidence.ts";
import { runExpectedBands } from "../_shared/analysis-core/runExpectedBands.ts";
import { runFitnessFatigue } from "../_shared/analysis-core/runFitnessFatigue.ts";
import { runHealth } from "../_shared/analysis-core/runHealth.ts";
import { runIntegrity } from "../_shared/analysis-core/runIntegrity.ts";
import { runAir } from "../_shared/analysis-core/runAir.ts";
import { runReports } from "../_shared/analysis-core/runPeriodReport.ts";
import { runDerived } from "../_shared/analysis-core/runDerived.ts";
import type { SeriesFile } from "../_shared/analysis-core/seriesContract.ts";
import seriesFile from "../_shared/metric-registry/metric-series.json" with { type: "json" };
import { runSeriesBreaks } from "../_shared/analysis-core/runSeriesBreaks.ts";
import { runTypicality } from "../_shared/analysis-core/runTypicality.ts";
import type { Trace } from "../_shared/analysis-core/trace.ts";
import { enginesFor, needsFrame, parseGroup, timedEngine } from "../_shared/analysis-core/runGroups.ts";
import { pearson, pFromR } from "../_shared/analysis-core/stats.ts";
import { CORE_VERSION } from "../_shared/analysis-core/version.ts";

// Per-step timings of the health engine, one log line each (steps are named by position, never by concern), so a request
// the Edge runtime kills for CPU time still shows where the time went.
const healthTrace = (step: string, ms: number, info?: Record<string, number>) =>
  console.log(JSON.stringify({ tag: "health-timing", step, ms: Math.round(ms), ...info }));

// The same for the other engines that report timings: one log line per step, tagged with the engine.
const engineTrace = (engine: string): Trace => (step, ms, info) =>
  console.log(JSON.stringify({ tag: "engine-timing", engine, step, ms: Math.round(ms), ...info }));

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

  let body: { action?: string; user_id?: string; planned_a?: number; planned_b?: number; group?: string; keys?: string[]; weeks?: number; months?: number; stress_days?: number } = {};
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
    const health = await runHealth(admin, loaded, new Date(), healthTrace).catch((e) => ({ userId: user.id, error: String(e) }));
    return json({ coreVersion: CORE_VERSION, health }, "error" in health && health.error ? 500 : 200);
  }

  if (body.action === "air") {
    // On demand for the signed-in user only: the air exposure engine (risk profile R1).
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const userClient = createClient(url, anon, { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } });
    const { data: { user } } = await userClient.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    const air = await runAir(admin, user.id, new Date()).catch((e) => ({ userId: user.id, error: String(e) }));
    return json({ air }, air.error ? 500 : 200);
  }

  if (body.action === "report") {
    // On demand for the signed-in user only: rewrite the named reports, or fill the last weeks and months.
    const url = Deno.env.get("SUPABASE_URL")!;
    const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
    const userClient = createClient(url, anon, { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } });
    const { data: { user } } = await userClient.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    const keys = Array.isArray(body.keys) ? body.keys.filter((k): k is string => typeof k === "string").slice(0, 24) : undefined;
    const opts = keys ? { keys } : { weeks: Math.max(0, Math.min(12, Math.round(body.weeks ?? 1))), months: Math.max(0, Math.min(6, Math.round(body.months ?? 1))) };
    const reports = await runReports(admin, user.id, seriesFile as SeriesFile, new Date(), opts).catch((e) => ({ userId: user.id, written: [], skipped: [], error: String(e) }));
    return json({ reports }, reports.error ? 500 : 200);
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

    // One request runs one group of engines (runGroups.ts), so each gets its own CPU budget; no group means all of them,
    // the old single-invocation behaviour. Every engine reports its own error and its time in `timings` (ms).
    const group = parseGroup(body.group);
    if (group === null) return json({ error: "bad_request", message: "unknown group" }, 400);
    const want = new Set(enginesFor(group));

    const results = [];
    for (const id of userIds) {
      const timings: Record<string, number> = {};
      const run = <T>(name: string, fn: () => Promise<T>) => timedEngine(id, name, timings, fn);
      const out: Record<string, unknown> = { userId: id, group };
      // Series breaks and integrity read their own rows, so a frame that fails to load does not hide them.
      if (want.has("breaks")) Object.assign(out, await run("breaks", () => runSeriesBreaks(admin, id, new Date(), engineTrace("series_breaks"))));
      if (want.has("integrity")) out.integrity = await run("integrity", () => runIntegrity(admin, id, seriesFile as SeriesFile));
      // Risk profile R1: the air breathed each day (outdoor feed + indoor sensors + places), group `exposures`.
      if (want.has("air")) out.air = await run("air", () => runAir(admin, id, new Date()));
      // Weekly and monthly reports read their own rows and the Lab's published results (group `reports`, last).
      // The scheduler may name periods (`keys`) or ask for the last `weeks` / `months` (a first fill); otherwise the due ones.
      const reportOpts = isScheduler && Array.isArray(body.keys) ? { keys: body.keys.filter((k): k is string => typeof k === "string").slice(0, 24) }
        : isScheduler && (body.weeks !== undefined || body.months !== undefined) ? { weeks: Math.max(0, Math.min(12, Math.round(body.weeks ?? 0))), months: Math.max(0, Math.min(6, Math.round(body.months ?? 0))) }
        : {};
      // Computed daily values and session fuel for the pages (runDerived.ts), before the reports.
      if (want.has("derived")) out.derived = await run("derived", () => runDerived(admin, id, new Date(), { stressDays: Math.max(1, Math.min(900, Math.round(body.stress_days ?? 60))) }));
      if (want.has("reports")) out.reports = await run("reports", () => runReports(admin, id, seriesFile as SeriesFile, new Date(), reportOpts));
      // The frame is read once and shared; one engine failing must not hide the others: each reports its own error.
      // Typicality and the expected bands run in a later group than the series breaks because they restart at confirmed ones.
      if (needsFrame(group)) {
        // The relationship engine reads about four years (its questions on sleep, resting heart rate, sessions and steps rest on
        // them); the other engines keep the one-year frame they were calibrated on. When relationships is the only engine of
        // the request (group `core`) the one frame is the long one; in a mixed request it loads its own.
        const frameEngines = [...want].filter((n) => !["breaks", "integrity", "air", "derived", "reports"].includes(n));
        const onlyRelationships = frameEngines.length === 1 && frameEngines[0] === "relationships";
        const loaded = await run("loadFrame", () => loadFrame(admin, id, new Date(), onlyRelationships ? RELATIONSHIP_LONG_LOOKBACK_DAYS : undefined));
        if ("error" in loaded) {
          for (const name of want) if (!["breaks", "integrity", "air", "derived", "reports"].includes(name)) out[name] = loaded;
        } else {
          const engines: Record<string, () => Promise<unknown>> = {
            relationships: async () => {
              const long = onlyRelationships ? loaded : await loadFrame(admin, id, new Date(), RELATIONSHIP_LONG_LOOKBACK_DAYS);
              return "error" in long ? long : runRelationships(admin, id, new Date(), long);
            },
            typicality: () => runTypicality(admin, loaded),
            expectedBands: () => runExpectedBands(admin, loaded),
            evidence: () => runEvidence(admin, loaded, new Date(), engineTrace("evidence")),
            experiments: () => runExperiments(admin, loaded),
            interventionResponses: () => runInterventionResponses(admin, loaded),
            interventionIts: () => runInterventionIts(admin, loaded),
            eventEvidence: () => runEventEvidence(admin, loaded, seriesFile as SeriesFile),
            fitnessFatigue: () => runFitnessFatigue(admin, loaded),
            health: () => runHealth(admin, loaded, new Date(), healthTrace),
          };
          for (const name of want) if (engines[name]) out[name] = await run(name, engines[name]);
        }
      }
      out.timings = timings;
      results.push(out);
    }
    return json({ coreVersion: CORE_VERSION, results }, 200);
  }

  return json({ error: "unknown_action" }, 400);
});
