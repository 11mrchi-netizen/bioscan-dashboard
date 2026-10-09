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

const PROBE_VERSION = "t12";
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
// The app's TrainingTemplateEntityForCreate: the structure goes in "trainingInterval" (singular).
function stripExtras(v: unknown) {
  return JSON.parse(JSON.stringify(v), (k, x) => (k.endsWith("I18nKey") || k === "strengthWeightValue" || k === "strengthWeightUnit" ? undefined : x));
}
function createBodyFor(t: typeof TEST_TEMPLATE, title: string) {
  return {
    trainingTypeId: t.trainingTypeId,
    title,
    description: t.description,
    trainingInterval: stripExtras(t.trainingIntervals),
    target: [],
    difficulty: [],
    sourceType: 0,
    modalities: [],
  };
}

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
    const zeppUserId = Deno.env.get("ZEPP_USER_ID");
    if (!zeppToken || !zeppHost || !zeppUserId) return json({ error: "not_configured" }, 500);

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
      const createBody = createBodyFor(TEST_TEMPLATE, TEST_TEMPLATE.title);
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

    if (body?.action === "schedule_test") {
      // Create a throwaway template, schedule it, read the schedule back, then delete schedule and template.
      // The schedule entity's value formats are not known (users/training/plan/schedules takes
      // {events:[{id,title,description,scheduledStartAt,scheduledEndAt,timezone,isRecurring,icalendarData,
      // provider,status}]}), so on "Error parameter 'x'" the next candidate for x is tried.
      const tplTitle = "ZZ TEST sched tpl";
      const made = await callZepp("PUT", "/users/training/templates", createBodyFor(TEST_TEMPLATE, tplTitle));
      await record("tpl create", "PUT /users/training/templates", made);
      let tplId: string | null = null;
      try { tplId = String(JSON.parse(made.text)?.id ?? ""); } catch { /* keep null */ }
      try {
        if (!tplId) return json({ results, error: "template not created" }, 200);
        const startMs = Date.UTC(2026, 9, 19, 23, 0, 0); // 20 Oct 2026 07:00 Asia/Taipei
        const endMs = startMs + 3600_000;
        // Schedule API, read from the app's TrainingScheduleCloudApiImpl: POST users/{uid}/training/calendar adds
        // one TrainingScheduleEntity (no wrapper): id String, title String, description String, scheduledStartAt
        // Long, scheduledEndAt Long, timezone String, isRecurring Boolean, icalendarData String, provider String,
        // status Int. GET lists with startTime/endTime (ms), DELETE .../{id} removes. Values for id/provider/
        // status are not known, so combinations are tried until one answers 200.
        const cal = `/users/${zeppUserId}/training/calendar`;
        const times: Array<(ms: number) => number> = [(ms) => ms, (ms) => Math.floor(ms / 1000)];
        const provs = ["USER_CUSTOM", "MANUAL_TRAINING_CALENDAR"];
        const stats = [1, 0];
        const ids = ["", crypto.randomUUID()];
        const ics = (uid: string) => [
          "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Training App//Schedule//EN", "CALSCALE:GREGORIAN", "BEGIN:VEVENT",
          `UID:${uid}`, `DTSTAMP:${new Date().toISOString().replace(/[-:]/g, "").slice(0, 15)}Z`,
          "DTSTART;TZID=Asia/Taipei:20261020T070000", "DTEND;TZID=Asia/Taipei:20261020T080000",
          "SUMMARY:ZZ TEST sched", `X-TRAINING-TEMPLATE-ID:${tplId}`, "END:VEVENT", "END:VCALENDAR",
        ].join("\r\n");
        let ok: { status: number; text: string } | null = null;
        let n = 0;
        const evFor = (id: string, prov: string, status: number, ti: number) => ({
          id, title: "ZZ TEST sched", description: "delete me",
          scheduledStartAt: times[ti](startMs), scheduledEndAt: times[ti](endMs),
          timezone: "Asia/Taipei", isRecurring: false, icalendarData: ics(id || "zz-test"), provider: prov, status,
        });
        // POST is 405 here; find the verb that is not.
        let verb = "";
        for (const m of ["PUT", "PATCH"]) {
          const r = await callZepp(m, cal, evFor("", provs[0], stats[0], 0));
          await record(`verb ${m}`, `${m} ${cal}`, r);
          if (r.status !== 405) { verb = m; if (r.status === 200) ok = r; break; }
        }
        search:
        for (const id of verb && !ok ? ids : []) {
          for (const prov of provs) {
            for (const status of stats) {
              for (let ti = 0; ti < times.length; ti++) {
                const r = await callZepp(verb, cal, evFor(id, prov, status, ti));
                await record(`sched#${n++} ${verb} id=${id ? "uuid" : "''"} prov=${prov || "''"} status=${status} time=${ti === 0 ? "ms" : "s"}`, `${verb} ${cal}`, r);
                if (r.status === 200) { ok = r; break search; }
              }
            }
          }
        }
        if (ok) {
          let created: string[] = [];
          try {
            const j = JSON.parse(ok.text);
            if (j?.data?.id) created.push(String(j.data.id));
          } catch { /* look in the list below */ }
          const q = `${cal}?startTime=${startMs - 86400_000}&endTime=${endMs + 86400_000}&limit=100`;
          const listed = await callZepp("GET", q);
          await record("sched list after create", q, listed);
          try {
            const arr = JSON.parse(listed.text)?.data?.items ?? [];
            for (const e of arr) if (e.title === "ZZ TEST sched" && !created.includes(String(e.id))) created.push(String(e.id));
          } catch { /* none */ }
          for (const id of created) {
            const d = await callZepp("DELETE", `${cal}/${id}`);
            await record(`sched delete ${id}`, `DELETE ${cal}/${id}`, d);
            if (d.text.includes('"deleted": 0') || d.status !== 200) {
              await record(`sched delete body ${id}`, `DELETE ${cal} {ids}`, await callZepp("DELETE", cal, { ids: [id] }));
            }
          }
          await record("sched list after delete", q, await callZepp("GET", q));
        }
      } finally {
        if (tplId) await record("tpl delete", `DELETE /users/training/templates/${tplId}`, await callZepp("DELETE", `/users/training/templates/${tplId}`));
      }
      return json({ results }, 200);
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
