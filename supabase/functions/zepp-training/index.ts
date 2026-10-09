// zepp-training -- feasibility probe for Zepp's training template / schedule cloud API.
//
// Found by reading the Zepp Android app (v10.8.7): templates live under users/training/templates,
// schedules under users/training/plan/schedules. This first version only issues GET requests to
// those paths with the same apptoken zepp-extract uses, and stores the raw answers in
// zepp_raw_extracts (metric "training_probe") so the real response shapes can be read before any
// write call is built. action "write_test" then creates a throwaway strength template, reads it back
// and deletes it again, recording every step. Credentials stay server-side; the caller needs a valid
// Supabase JWT.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { EXERCISES, liftBlock, strengthTemplate } from "./template.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

const PROBE_VERSION = "t7";
const MAX_STORED_CHARS = 200_000;
// Read-only: nothing outside these prefixes is ever requested.
const ALLOWED = [/^\/users\/training\//, /^\/v1\/sport\/shareTrainingTemplate/];
// Throwaway template for action "write_test" (created, read back, deleted).
const TEST_TEMPLATE = strengthTemplate("ZZ TEST cloud", "delete me", [
  ...liftBlock(EXERCISES.standing_barbell_press, 3, 5, 42.5, [[10, 20]]),
  ...liftBlock(EXERCISES.barbell_front_squat, 3, 5, 62.5, [[10, 20]]),
  ...liftBlock(EXERCISES.pull_up, 3, 5, 20, [[10, 0]]),
]);

// What a body-less call (the app's check button) requests. Edited here while the real paths are found.
const DEFAULT_PATHS = [
  "/users/training/templates",
  "/users/training/templates?size=100",
  "/users/training/types?size=100",
  "/users/training/plan/schedules",
  "/users/training/plan/",
];

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return json({ error: "missing_auth" }, 401);

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_ANON_KEY")!,
      { global: { headers: { Authorization: authHeader } } },
    );
    const { data: { user }, error: userError } = await supabase.auth.getUser(authHeader.replace("Bearer ", ""));
    if (userError || !user) return json({ error: "unauthorized" }, 401);

    const zeppToken = Deno.env.get("ZEPP_APP_TOKEN");
    const zeppHost = Deno.env.get("ZEPP_API_HOST");
    if (!zeppToken || !zeppHost) return json({ error: "not_configured" }, 500);

    const body = await req.json().catch(() => ({}));
    const paths: string[] = Array.isArray(body?.paths) ? body.paths.filter((p: unknown) => typeof p === "string") : DEFAULT_PATHS;
    if (paths.length === 0 || paths.length > 12) return json({ error: "paths: 1-12 strings required" }, 400);

    const today = new Date().toISOString().slice(0, 10);
    const results: Array<Record<string, unknown>> = [];

    async function callZepp(method: string, path: string, payload?: unknown): Promise<{ status: number; text: string }> {
      const sep = path.includes("?") ? "&" : "?";
      const url = `https://${zeppHost}${path}${sep}r=${crypto.randomUUID().toUpperCase()}`;
      try {
        const res = await fetch(url, {
          method,
          headers: {
            apptoken: zeppToken!,
            "Content-Type": "application/json",
            appname: "com.huami.midong",
            appplatform: "ios_phone",
            v: "2.0",
            vn: "10.2.5",
            cv: "1722_10.2.5",
            vb: "202604132257",
            "user-agent": "Zepp/10.2.5 (iPhone; iOS 26.3.1; Scale/3.00)",
            lang: "en",
            country: "",
            timezone: "UTC",
          },
          body: payload === undefined ? undefined : JSON.stringify(payload),
        });
        return { status: res.status, text: await res.text() };
      } catch (e) {
        return { status: 0, text: JSON.stringify({ _fetch_error: String(e) }) };
      }
    }

    async function record(label: string, path: string, r: { status: number; text: string }) {
      let raw: unknown;
      try {
        raw = JSON.parse(r.text.slice(0, MAX_STORED_CHARS));
      } catch {
        raw = { _raw_text: r.text.slice(0, MAX_STORED_CHARS) };
      }
      const { error: storeError } = await supabase.from("zepp_raw_extracts").upsert(
        {
          user_id: user.id,
          metric: "training_probe",
          query_date: today,
          track_id: label,
          api_host: zeppHost,
          endpoint: path,
          status_code: r.status,
          raw_body: raw,
          fetched_at: new Date().toISOString(),
          extractor_version: PROBE_VERSION,
        },
        { onConflict: "user_id,metric,query_date,track_id" },
      );
      results.push({ label, path, status: r.status, bytes: r.text.length, stored: !storeError, preview: r.text.slice(0, 300) });
    }

    if (body?.action === "write_test") {
      // Which verb/path creates a template is not known: try the likely ones until one answers 200,
      // then read back, delete every "ZZ TEST cloud" and read again.
      // Create is PUT /users/training/templates with the app's TrainingTemplateEntityForCreate: the structure
      // goes in "trainingInterval" (singular), which is why the share-format "trainingIntervals" failed the
      // server's structure check ("Error parameter 'trainingStructureValid'").
      const newId = Date.now() * 1000 + Math.floor(Math.random() * 1000);
      const strip = (v: unknown) => JSON.parse(JSON.stringify(v), (k, x) => (k.endsWith("I18nKey") || k === "strengthWeightValue" || k === "strengthWeightUnit" ? undefined : x));
      const createBody = {
        trainingTypeId: TEST_TEMPLATE.trainingTypeId,
        title: TEST_TEMPLATE.title,
        description: TEST_TEMPLATE.description,
        trainingInterval: strip(TEST_TEMPLATE.trainingIntervals),
        target: [],
        difficulty: [],
        sourceType: 0,
        modalities: [],
      };
      const attempts: Array<[string, string, unknown]> = [
        ["PUT", "/users/training/templates", createBody],
        ["PUT", "/users/training/templates", { ...createBody, totalTime: 3600 }],
        ["PUT", "/users/training/templates", { ...createBody, clientWorkoutId: newId, totalTime: 3600 }],
        ["POST", `/users/training/templates/${newId}`, createBody],
      ];
      for (const [method, path, payload] of attempts) {
        const r = await callZepp(method, path, payload);
        await record(`create ${method} ${path} ${JSON.stringify(payload).slice(-40)}`, `${method} ${path}`, r);
        if (r.status === 200) break;
      }
      const listed = await callZepp("GET", "/users/training/templates");
      await record("list_after_create", "/users/training/templates", listed);
      let ids: string[] = [];
      try {
        ids = (JSON.parse(listed.text)?.items ?? []).filter((i: { title?: string }) => i.title === TEST_TEMPLATE.title).map((i: { id: string | number }) => String(i.id));
      } catch { /* none */ }
      for (const id of ids) {
        const r = await callZepp("DELETE", `/users/training/templates/${id}`);
        await record(`delete ${id}`, `DELETE /users/training/templates/${id}`, r);
      }
      if (ids.length > 0) await record("list_after_delete", "/users/training/templates", await callZepp("GET", "/users/training/templates"));
      return json({ results, createdIds: ids }, 200);
    }

    for (const path of paths) {
      if (!ALLOWED.some((re) => re.test(path.split("?")[0]))) {
        results.push({ path, error: "path not allowed" });
        continue;
      }
      await record(path, path, await callZepp("GET", path));
    }

    return json({ results }, 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});
