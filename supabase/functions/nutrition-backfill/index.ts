// nutrition-backfill — re-check meals already logged against the current food lookup.
//
// Started from SQL, with no human-held secret (same pattern as analysis-run):
//   select private.run_nutrition_backfill();            -- every user's Cronometer-enriched meal items; returns the run id
//   select private.run_nutrition_backfill('<user id>');  -- one user's
//   select private.run_nutrition_backfill(null, array['ai_text_enriched']);  -- typed entries only (photo meals are skipped)
// private.run_nutrition_backfill() sends a random Vault secret in x-nutrition-backfill, checked through the service
// role-only public.nutrition_backfill_secret_ok().
//
// It only reviews: each meal's saved description goes through ../_shared/nutrition-lookup again, and the old and new
// numbers land in nutrition_backfill_review (flagged when calories moved by >= 100 kcal, or >= 25% and >= 30 kcal).
// The meal log itself is untouched. Applying is a separate, explicit step on chosen rows:
//   select public.apply_nutrition_backfill_review(array[<review ids>]);   -- undo: public.revert_nutrition_backfill_review
// Work runs in the background in small batches; each batch calls the function again until the run has a row per item.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import { cronoLogin, isFailure, lookupDescription, mapLimit, memoryLoader, type ResolvedItem } from "../_shared/nutrition-lookup/lookup.ts";
import { itemCalories } from "../_shared/nutrition-lookup/matching.ts";

const BATCH = 6; // meal items per invocation, well inside the background-task time limit
const MIN_BATCH_MS = 45_000; // at most ~6 items a minute through Gemini
const SOURCES = ["ai_text_enriched", "ai_image_enriched"];

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

interface ItemRow {
  id: number;
  meal_id: number;
  user_id: string;
  description: string | null;
  calories: number | null;
  protein_g: number | null;
  carbs_g: number | null;
  fat_g: number | null;
  source: string;
}

interface MealRow {
  id: number;
  logged_at: string;
  description: string | null;
}

const num = (v: unknown): number | null => (v == null ? null : Number(v));

export function isFlagged(oldKcal: number | null, newKcal: number | null): boolean {
  if (newKcal == null) return false;
  if (oldKcal == null) return true;
  const diff = Math.abs(newKcal - oldKcal);
  return diff >= 100 || (diff >= 30 && oldKcal > 0 && diff / oldKcal >= 0.25);
}

const counts = (r: ResolvedItem) => (r.used_estimate && r.ai_estimate != null) || r.match_quality === "exact" || r.match_quality === "good";

// Same shape the app writes to meal_items.cronometer_items.
function audit(r: ResolvedItem) {
  return {
    query: r.query,
    food_id: r.cronometer_food_id,
    food: r.cronometer_food_name,
    source: r.cronometer_source,
    grams: r.quantity_g,
    serving: r.serving_used,
    kcal: itemCalories(r),
    quality: r.match_quality,
    ai_kcal: r.ai_estimate?.calories ?? null,
    used_estimate: r.used_estimate,
    disagreement: r.disagreement,
    from_memory: r.from_memory,
    user_edited: false,
  };
}

function primarySource(results: ResolvedItem[]): string | null {
  const tally = new Map<string, number>();
  for (const r of results) {
    if (r.used_estimate || !r.cronometer_source || !(r.match_quality === "exact" || r.match_quality === "good")) continue;
    tally.set(r.cronometer_source, (tally.get(r.cronometer_source) ?? 0) + 1);
  }
  return [...tally.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
}

// A failed item (Gemini rate limits, a timeout) is tried once more at the end of the run; a second failure stays.
const RETRIED = "retried: ";

async function reviewBatch(
  admin: SupabaseClient,
  runId: string,
  userId: string | null,
  sources: string[],
  secret: string,
): Promise<void> {
  const started = Date.now();
  const { data: doneRows, error: doneError } = await admin
    .from("nutrition_backfill_review")
    .select("meal_item_id, status, error")
    .eq("run_id", runId);
  if (doneError) return console.log(`review ${runId}: ${doneError.message}`);
  const existing = doneRows as { meal_item_id: number; status: string; error: string | null }[];
  const retryable = new Set(
    existing.filter((r) => r.status === "error" && !r.error?.startsWith(RETRIED)).map((r) => r.meal_item_id),
  );
  const reviewed = new Set(existing.map((r) => r.meal_item_id));

  let query = admin
    .from("meal_items")
    .select("id, meal_id, user_id, description, calories, protein_g, carbs_g, fat_g, source")
    .in("source", sources)
    .order("id");
  if (userId) query = query.eq("user_id", userId);
  const { data: itemRows, error: itemError } = await query;
  if (itemError) return console.log(`review ${runId}: ${itemError.message}`);

  const items = itemRows as ItemRow[];
  const queue = [...items.filter((i) => !reviewed.has(i.id)), ...items.filter((i) => retryable.has(i.id))];
  const todo = queue.slice(0, BATCH);
  if (todo.length === 0) return console.log(`review ${runId}: complete, ${reviewed.size} items`);

  const { data: mealRows, error: mealError } = await admin
    .from("meals")
    .select("id, logged_at, description")
    .in("id", [...new Set(todo.map((i) => i.meal_id))]);
  if (mealError) return console.log(`review ${runId}: ${mealError.message}`);
  const meals = new Map((mealRows as MealRow[]).map((m) => [m.id, m]));

  const session = await cronoLogin(Deno.env.get("CRONOMETER_EMAIL")!, Deno.env.get("CRONOMETER_PASSWORD")!);
  const geminiKey = Deno.env.get("GEMINI_API_KEY");

  const rows = await mapLimit(todo, 2, async (item) => {
    const meal = meals.get(item.meal_id);
    const description = (meal?.description ?? item.description ?? "").trim();
    // Every row carries every column: a bulk upsert fills a key missing from one row with null, not the default.
    const base = {
      run_id: runId,
      user_id: item.user_id,
      meal_id: item.meal_id,
      meal_item_id: item.id,
      logged_at: meal?.logged_at ?? new Date().toISOString(),
      description,
      source: item.source,
      old_calories: num(item.calories),
      old_protein_g: num(item.protein_g),
      old_carbs_g: num(item.carbs_g),
      old_fat_g: num(item.fat_g),
      new_calories: null as number | null,
      new_protein_g: null as number | null,
      new_carbs_g: null as number | null,
      new_fat_g: null as number | null,
      new_totals: null as Record<string, number> | null,
      new_units: null as Record<string, string> | null,
      new_items: null as unknown[] | null,
      new_confidence: null as number | null,
      new_primary_source: null as string | null,
      new_grams: null as number | null,
      ai_estimate_total: null as number | null,
      mode: null as string | null,
      flagged: false,
      status: "error",
      error: null as string | null,
    };
    const failed = (message: string) => ({ ...base, error: (retryable.has(item.id) ? RETRIED : "") + message });
    if (!meal || !description) return failed("meal or description missing");
    try {
      const result = await lookupDescription({ session, geminiKey, loadMemory: memoryLoader(admin, item.user_id) }, description);
      if (isFailure(result)) return failed(result.message);
      const t = result.totals;
      const newKcal = t.calories ?? null;
      return {
        ...base,
        new_calories: newKcal,
        new_protein_g: t.protein ?? null,
        new_carbs_g: t.carbs ?? null,
        new_fat_g: t.fat ?? null,
        new_totals: t,
        new_units: result.nutrient_units,
        new_items: result.results.map(audit),
        new_confidence: result.confidence,
        new_primary_source: primarySource(result.results),
        new_grams: Math.round(result.results.filter(counts).reduce((s, r) => s + r.quantity_g, 0) * 10) / 10 || null,
        ai_estimate_total: result.ai_estimate_total,
        mode: result.mode,
        flagged: isFlagged(base.old_calories, newKcal),
        status: "pending",
      };
    } catch (e) {
      return failed(String(e));
    }
  });

  const { error: writeError } = await admin.from("nutrition_backfill_review").upsert(rows, { onConflict: "run_id,meal_item_id" });
  // A failed write stops the chain: the next batch would pick the same items again.
  if (writeError) return console.log(`review ${runId}: ${writeError.message}`);
  const failures = rows.filter((r) => r.status === "error").length;
  console.log(`review ${runId}: batch of ${rows.length} written (${failures} failed), ${queue.length - todo.length} left`);

  if (queue.length > todo.length) {
    // Pace the chain so Gemini's per-minute limit is not hit (each item makes one or two Gemini calls).
    const wait = MIN_BATCH_MS - (Date.now() - started);
    if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
    const res = await fetch(`${Deno.env.get("SUPABASE_URL")}/functions/v1/nutrition-backfill`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${Deno.env.get("SUPABASE_ANON_KEY")}`,
        "x-nutrition-backfill": secret,
      },
      body: JSON.stringify({ action: "review", run_id: runId, user_id: userId, sources }),
    });
    if (!res.ok) console.log(`review ${runId}: next batch not started (HTTP ${res.status})`);
  }
}

Deno.serve(async (req: Request) => {
  try {
    const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

    // Only private.run_nutrition_backfill() (or this function's own next batch) holds the Vault secret.
    const secret = req.headers.get("x-nutrition-backfill");
    if (!secret) return json({ error: "unauthorized" }, 401);
    const { data: ok } = await admin.rpc("nutrition_backfill_secret_ok", { candidate: secret });
    if (ok !== true) return json({ error: "unauthorized" }, 401);

    if (!Deno.env.get("CRONOMETER_EMAIL") || !Deno.env.get("CRONOMETER_PASSWORD")) {
      return json({ error: "not_configured", message: "CRONOMETER_EMAIL and CRONOMETER_PASSWORD must be set." }, 500);
    }

    let body: { action?: string; run_id?: string; user_id?: string | null; sources?: string[] | null };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    if (body.action !== "review") return json({ error: "unknown_action", message: "Supported: review." }, 400);

    const sources = body.sources?.length ? body.sources.filter((s) => SOURCES.includes(s)) : SOURCES;
    if (sources.length === 0) return json({ error: "invalid_sources", message: `Supported: ${SOURCES.join(", ")}.` }, 400);

    const runId = body.run_id ?? crypto.randomUUID();
    const work = reviewBatch(admin, runId, body.user_id ?? null, sources, secret).catch((e) => console.log(`review ${runId}: ${String(e)}`));
    // Answer the caller at once and keep working (Supabase's edge runtime); elsewhere, finish before answering.
    const runtime = (globalThis as { EdgeRuntime?: { waitUntil(p: Promise<unknown>): void } }).EdgeRuntime;
    if (runtime) runtime.waitUntil(work);
    else await work;
    return json({ accepted: true, run_id: runId }, 202);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});
