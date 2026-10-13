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
import { BY_MOVEMENT, type BlockOptions, EXERCISES, liftBlock, strengthTemplate } from "./template.ts";
import { addDays, calendarEntry, dayWindowMs } from "./calendar.ts";

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

const PROBE_VERSION = "t18";
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

    if (body?.action === "bulk_test") {
      // Three templates and 13 calendar entries (4 weeks of Mon/Wed/Fri from 2026-10-12, plus one in January),
      // all titled "ZZ BULK ...". Nothing is deleted: the entries are meant to be looked at in the Zepp app.
      // action "bulk_cleanup" removes them.
      const cal = `/users/${zeppUserId}/training/calendar`;
      const plans: Array<{ sets: number; reps: number; loads: [number, number, number] }> = [
        { sets: 3, reps: 5, loads: [42.5, 62.5, 20] },
        { sets: 3, reps: 8, loads: [35, 50, 15] },
        { sets: 4, reps: 3, loads: [47.5, 70, 25] },
      ];
      const tplIds: string[] = [];
      for (let i = 0; i < plans.length; i++) {
        const p = plans[i];
        const t = strengthTemplate(`ZZ BULK Day ${i + 1}`, "bulk schedule test, delete me", [
          ...liftBlock(EXERCISES.standing_barbell_press, p.sets, p.reps, p.loads[0], [[10, 20]]),
          ...liftBlock(EXERCISES.barbell_front_squat, p.sets, p.reps, p.loads[1], [[10, 20]]),
          ...liftBlock(EXERCISES.pull_up, p.sets, p.reps, p.loads[2], [[10, 0]]),
        ]);
        const r = await callZepp("PUT", "/users/training/templates", createBodyFor(t, t.title));
        await record(`bulk tpl ${i + 1}`, "PUT /users/training/templates", r);
        try { tplIds.push(String(JSON.parse(r.text)?.id ?? "")); } catch { tplIds.push(""); }
      }
      if (tplIds.some((x) => !x)) return json({ results, error: "template creation failed" }, 200);

      const days: Array<[string, number]> = [];
      for (let wk = 0; wk < 4; wk++) for (const [off, di] of [[0, 0], [2, 1], [4, 2]] as Array<[number, number]>) days.push([addDays("2026-10-12", wk * 7 + off), di]);
      days.push(["2027-01-11", 0]);
      const entryIds: string[] = [];
      const failures: Array<Record<string, unknown>> = [];
      for (let i = 0; i < days.length; i++) {
        const [date, di] = days[i];
        const wk = Math.floor(i / 3) + 1;
        const title = i === days.length - 1 ? "ZZ BULK Jan Day 1" : `ZZ BULK W${wk} D${di + 1}`;
        const r = await callZepp("PUT", cal, calendarEntry(title, "bulk schedule test, delete me", tplIds[di], date, 7, 60));
        try {
          const id = JSON.parse(r.text)?.data?.id;
          if (r.status === 200 && id) entryIds.push(String(id)); else failures.push({ title, date, status: r.status, text: r.text.slice(0, 200) });
        } catch { failures.push({ title, date, status: r.status, text: r.text.slice(0, 200) }); }
      }
      await record("bulk summary", "bulk_test", { status: failures.length === 0 ? 200 : 207, text: JSON.stringify({ templates: tplIds, entriesCreated: entryIds.length, entryIds, failures }) });
      const [from, to] = dayWindowMs("2026-10-11", "2027-01-31");
      let total = 0;
      let next = "";
      for (let page = 0; page < 5; page++) {
        const q = `${cal}?startTime=${from}&endTime=${to}&limit=100${next ? `&next=${next}` : ""}`;
        const l = await callZepp("GET", q);
        try {
          const j = JSON.parse(l.text);
          total += (j?.data?.items ?? []).filter((e: { title?: string }) => (e.title ?? "").startsWith("ZZ BULK")).length;
          next = j?.data?.next ?? "";
        } catch { next = ""; }
        if (page === 0) await record("bulk list", q, l);
        if (!next) break;
      }
      await record("bulk listed count", "bulk_test", { status: 200, text: JSON.stringify({ listedZzBulk: total }) });
      return json({ results }, 200);
    }

    if (body?.action === "variant_test") {
      // Same 3x5 session written three ways, scheduled for 2026-10-10 at 07:00 / 08:00 / 09:00, to see which
      // structure the Zepp app and watch show in full. Titles start "ZZ VAR"; bulk_cleanup removes them.
      const cal = `/users/${zeppUserId}/training/calendar`;
      const variants: Array<[string, BlockOptions]> = [
        ["ZZ VAR A flat", { flat: true }],
        ["ZZ VAR B timed rest", { restSec: 90 }],
        ["ZZ VAR C warmup as training", { warmupAsTraining: true }],
      ];
      for (let i = 0; i < variants.length; i++) {
        const [title, o] = variants[i];
        const t = strengthTemplate(title, "structure variant test, delete me", [
          ...liftBlock(EXERCISES.standing_barbell_press, 3, 5, 42.5, [[10, 20]], o),
          ...liftBlock(EXERCISES.barbell_front_squat, 3, 5, 62.5, [[10, 20]], o),
          ...liftBlock(EXERCISES.pull_up, 3, 5, 20, [[10, 0]], o),
        ]);
        const made = await callZepp("PUT", "/users/training/templates", createBodyFor(t, title));
        await record(`variant tpl ${i + 1}`, "PUT /users/training/templates", made);
        let id = "";
        try { id = String(JSON.parse(made.text)?.id ?? ""); } catch { /* empty */ }
        if (!id) continue;
        const r = await callZepp("PUT", cal, calendarEntry(title, "structure variant test, delete me", id, "2026-10-10", 7 + i, 60));
        await record(`variant entry ${i + 1}`, `PUT ${cal}`, r);
      }
      return json({ results }, 200);
    }

    if (body?.action === "ical_test") {
      // The app showed none of the bulk/variant entries (built with CRLF and a random UID) but shows its own.
      // Two entries on 2026-10-10 for the template "ZZ VAR A flat": A copies the app's iCalendar text exactly
      // (LF, empty UID), B is LF with a random UID. Titles start "ZZ LF"; bulk_cleanup removes them.
      const cal = `/users/${zeppUserId}/training/calendar`;
      const tl = await callZepp("GET", "/users/training/templates");
      let tplId = "";
      try { tplId = String((JSON.parse(tl.text)?.items ?? []).find((t: { title?: string }) => t.title === "ZZ VAR A flat")?.id ?? ""); } catch { /* none */ }
      await record("ical template", "/users/training/templates", { status: tl.status, text: JSON.stringify({ tplId }) });
      if (!tplId) return json({ results, error: "template ZZ VAR A flat not found" }, 200);
      const styles: Array<[string, { crlf?: boolean; uid?: string }, number]> = [
        ["ZZ LF A app-exact", {}, 10],
        ["ZZ LF B random uid", { uid: crypto.randomUUID() }, 11],
      ];
      for (const [title, style, hour] of styles) {
        const r = await callZepp("PUT", cal, calendarEntry(title, "ical format test, delete me", tplId, "2026-10-10", hour, 60, style));
        await record(`ical ${title}`, `PUT ${cal}`, r);
      }
      return json({ results }, 200);
    }

    if (body?.action === "ref_test") {
      // The dump showed the app's own entry carries UID = X-TRAINING-LOCAL-ID and an X-TRAINING-TEMPLATE-LOCAL-ID,
      // and points at a template the phone itself made. Three entries on 2026-10-10 tell which part matters:
      // A = our plain text on the app-made "Fighter" template, B = A plus the app's local-id lines,
      // C = B on a cloud-made template ("ZZ VAR A flat"). Titles start "ZZ LF"; bulk_cleanup removes them.
      const cal = `/users/${zeppUserId}/training/calendar`;
      const tl = await callZepp("GET", "/users/training/templates");
      let items: Array<{ id?: unknown; title?: string }> = [];
      try { items = JSON.parse(tl.text)?.items ?? []; } catch { /* none */ }
      await record("ref templates", "/users/training/templates", { status: tl.status, text: JSON.stringify(items.map((t) => ({ id: t.id, title: t.title }))) });
      const idOf = (title: string) => String(items.find((t) => t.title === title)?.id ?? "");
      const fighter = idOf("Fighter"), zz = idOf("ZZ VAR A flat");
      const mk = () => crypto.randomUUID();
      const plan: Array<[string, string, number, { uid?: string; localId?: string; tplLocalId?: string }]> = [];
      if (fighter) {
        plan.push(["ZZ LF REF A plain", fighter, 16, {}]);
        const u = mk();
        plan.push(["ZZ LF REF B local ids", fighter, 17, { uid: u, localId: u, tplLocalId: "1791522934626649" }]);
      }
      if (zz) {
        const u = mk();
        plan.push(["ZZ LF REF C cloud template", zz, 18, { uid: u, localId: u, tplLocalId: String(Date.now() * 1000 + 123) }]);
      }
      for (const [title, tid, hour, style] of plan) {
        const r = await callZepp("PUT", cal, calendarEntry(title, "", tid, "2026-10-10", hour, 60, style));
        await record(`ref ${title}`, `PUT ${cal}`, r);
      }
      return json({ results }, 200);
    }

    if (body?.action === "push_templates") {
      // The app's block -> Zepp push. body.templates = [{ key, title, description, lifts: [{ movement, sets, reps,
      // kg, warmups: [[reps, kg], ...] }] }]; body.deleteIds = old template ids to remove (replaced or past).
      // Each template is created in the cloud (the phone pulls templates on its own, calendar entries it does not),
      // and the answer maps each key to the new id. Lifts with no Zepp exercise are skipped and reported.
      const created: Array<{ key: string; id: string }> = [];
      const unmapped: Array<{ key: string; movement: string }> = [];
      const failed: Array<{ key: string; status: number; text: string }> = [];
      for (const t of Array.isArray(body.templates) ? body.templates : []) {
        const children: Parameters<typeof strengthTemplate>[2] = [];
        for (const l of Array.isArray(t.lifts) ? t.lifts : []) {
          const ex = BY_MOVEMENT[String(l.movement)];
          if (!ex) { unmapped.push({ key: String(t.key), movement: String(l.movement) }); continue; }
          const warmups = (Array.isArray(l.warmups) ? l.warmups : []).map((w: number[]) => [Number(w[0]), Number(w[1])] as [number, number]);
          children.push(...liftBlock(ex, Number(l.sets) || 1, Number(l.reps) || 1, Number(l.kg) || 0, warmups, { flat: false }));
        }
        if (children.length === 0) continue;
        const r = await callZepp("PUT", "/users/training/templates", createBodyFor(strengthTemplate(String(t.title), String(t.description ?? ""), children), String(t.title)));
        let id = "";
        try { id = String(JSON.parse(r.text)?.id ?? ""); } catch { /* none */ }
        if (r.status === 200 && id) created.push({ key: String(t.key), id }); else failed.push({ key: String(t.key), status: r.status, text: r.text.slice(0, 200) });
      }
      let deleted = 0;
      for (const id of Array.isArray(body.deleteIds) ? body.deleteIds : []) {
        if (!/^\d{10,20}$/.test(String(id))) continue;
        if ((await callZepp("DELETE", `/users/training/templates/${id}`)).status === 200) deleted++;
      }
      await record("push templates", "push_templates", { status: failed.length ? 207 : 200, text: JSON.stringify({ created: created.length, deleted, unmapped, failed }) });
      return json({ created, unmapped, failed, deleted }, 200);
    }

    if (body?.action === "calendar_dump") {
      // Raw calendar entries for 2026-10-09 .. 2026-10-13, to compare entries made in the Zepp app with ours.
      const cal = `/users/${zeppUserId}/training/calendar`;
      const [from, to] = dayWindowMs("2026-10-09", "2026-10-13");
      const q = `${cal}?startTime=${from}&endTime=${to}&limit=100`;
      const l = await callZepp("GET", q);
      await record("calendar dump", q, l);
      return json({ results }, 200);
    }

    if (body?.action === "bulk_cleanup") {
      const cal = `/users/${zeppUserId}/training/calendar`;
      const [from, to] = dayWindowMs("2026-01-01", "2027-12-31");
      const found: string[] = [];
      let next = "";
      for (let page = 0; page < 10; page++) {
        const q = `${cal}?startTime=${from}&endTime=${to}&limit=100${next ? `&next=${next}` : ""}`;
        const l = await callZepp("GET", q);
        try {
          const j = JSON.parse(l.text);
          for (const e of j?.data?.items ?? []) if (/^ZZ (BULK|VAR|LF)/.test(e.title ?? "")) found.push(String(e.id));
          next = j?.data?.next ?? "";
        } catch { next = ""; }
        if (!next) break;
      }
      let deleted = 0;
      for (const id of found) {
        const d = await callZepp("DELETE", `${cal}/${id}`);
        if (d.status === 200) deleted++;
      }
      const tl = await callZepp("GET", "/users/training/templates");
      let tplDeleted = 0;
      try {
        for (const t of JSON.parse(tl.text)?.items ?? []) {
          if (/^ZZ (BULK|VAR|LF)/.test(String(t.title ?? "")) && (await callZepp("DELETE", `/users/training/templates/${t.id}`)).status === 200) tplDeleted++;
        }
      } catch { /* none */ }
      await record("bulk cleanup", "bulk_cleanup", { status: 200, text: JSON.stringify({ entriesFound: found.length, entriesDeleted: deleted, templatesDeleted: tplDeleted }) });
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
