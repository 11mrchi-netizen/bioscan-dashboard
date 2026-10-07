// zepp-extract — DAV-112
//
// Supabase Edge Function that extracts data from the Zepp mobile API
// and stores raw responses in zepp_raw_extracts for inspection/decoding.
// All Zepp credentials stay server-side (env vars); the caller only needs
// a valid Supabase JWT and a date range.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { computeEffort } from "./effort.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

const EXTRACTOR_VERSION = "10"; // 4: GAP/EF/decoupling (DAV-272); 5: altitude is cm, drop no-fix samples;
// 6: write Zepp's own decoded distance back onto exercise_sessions.distance_km, and merge
// orphaned zepp-sourced placeholder rows created by the Zepp/Health-Connect sync race (DAV-274)
// 7: fix the "stress" metric's endpoint (was 404ing every attempt -- see METRIC_DEFS) (DAV-246)
// 8: the v7 endpoint fix moved stress from 404 to a real Huami-side 500
// ("code":-50000, "Failed to process the request") -- fetchAndStore was only
// ever sending `apptoken`, never the app-identity headers Huami's newer
// /users/{id}/events endpoint family validates (legacy /v1//v2/ endpoints
// tolerate their absence, this one doesn't). Real values confirmed against
// zepp-health-cli's own _headers() (github.com/m4ary/zepp-health-cli,
// literal defaults baked into the library, not placeholders) -- same
// reverse-engineering source that found the endpoint shape itself.
// 9: band_data endpoints (heart_rate/hrv/sleep/spo2) were all 404ing because
// we used /v2/ path, query_type=summary, single `date` param, and a
// data_type filter -- none of which match the real mobile API. Corrected to
// match ZeppBridge's working approach: /v1/ path, query_type=detail,
// from_date/to_date range params, byteLength=8, no data_type filter.
// 10: parse band_data response -- decode the base64 summary JSON to extract
// sleep stages, resting HR, sleep score, and steps, then upsert into
// sleep_daily and wearable_daily. Stage mode mapping confirmed against a
// real response: 4=light, 5=deep, 7=awake, 8=REM.


// DAV-115/123: decode detail.json's per-second fields into plain TimePoint-
// shaped series the Android app already knows how to render (see
// domain/SessionDetail.kt). Format confirmed 2026-09-25 against a real GPS
// run, cross-checked against that run's own summary numbers -- see
// docs/zepp-integration/03-workout-detail-field-decode.md. Same decoder for
// every sport_type: a real 30-day backfill across run/strength/trail-run/
// cycling confirmed the encoding is identical, just with different fields
// left empty (e.g. strength has no GPS/pace/speed) -- no per-type branching
// needed, only per-field presence checks.
interface DecodedPoint {
  offsetSeconds: number;
  value: number;
}

interface WorkoutSummaryFields {
  // DAV-272, computed here from the per-second series (see effort.ts); only
  // set for running sport types with usable altitude + distance.
  gapMinPerKm?: number | null;
  efficiencyFactor?: number | null;
  hrDecouplingPct?: number | null;
  smoothedAscentM?: number | null;
  avgCadenceSpm: number | null;
  maxCadenceSpm: number | null;
  avgStrideLengthCm: number | null;
  avgGroundContactMs: number | null;
  avgVerticalStrideRatioPct: number | null;
  lactateThresholdHrBpm: number | null;
  lactateThresholdPaceSecPerKm: number | null;
}

interface DecodedSeries {
  heartRate: DecodedPoint[];
  speedKmh: DecodedPoint[];
  altitudeM: DecodedPoint[];
  distanceKm: DecodedPoint[];
  cadenceSpm: DecodedPoint[];
  verticalStrideRatioPct: DecodedPoint[];
  summary: WorkoutSummaryFields;
}

function splitEntries(raw: unknown): string[] {
  if (typeof raw !== "string" || raw.length === 0) return [];
  return raw.replace(/;$/, "").split(";").filter((e) => e.length > 0);
}

// heart_rate: first entry is the absolute starting bpm; every entry after
// that is a signed delta to add to the running total. The flag before the
// comma (seen: empty, 0, 2, 7 on entry 1 only) doesn't gate the decode.
function decodeHeartRate(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  let running = 0;
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const v = Number(parts[1] ?? parts[0]);
    if (!Number.isFinite(v)) return;
    running = i === 0 ? v : running + v;
    out.push({ offsetSeconds: i, value: running });
  });
  return out;
}

// speed: "<flag>,<m/s>" per second, no delta encoding -- convert straight to km/h.
function decodeSpeedKmh(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const ms = Number(parts[1] ?? parts[0]);
    if (!Number.isFinite(ms)) return;
    out.push({ offsetSeconds: i, value: ms * 3.6 });
  });
  return out;
}

// altitude: plain per-second value, no delta encoding, in CENTIMETRES (checked
// 2026-09-25: a run with ~20 m of real ascent spans 922..2054; a trail run
// tops out at 30716 = 307 m) -- converted to metres here. Zepp writes about
// -2,000,000 when it has no fix; those samples are dropped, not decoded.
const ALTITUDE_INVALID_BELOW_CM = -1_000_000;
function decodeAltitude(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  entries.forEach((entry, i) => {
    const v = Number(entry);
    if (!Number.isFinite(v) || v < ALTITUDE_INVALID_BELOW_CM) return;
    out.push({ offsetSeconds: i, value: v / 100 });
  });
  return out;
}

// gait: "<f1>,<f2>,<vertRatio_x10>,<cadence_spm>" per second -- f1/f2 look
// like run-state flags (0 before the run starts, settling to 1/2 once
// steady), not decoded since neither has a summary field to check against.
// Confirmed 2026-09-25: avg cadence (f4) = 175.5 vs summary avg_frequency
// 173.0; max f4 = 200 vs summary max_frequency 201; avg f3/10 = 8.89% vs
// summary avgVertStrideRatio/10 = 8.6% -- both close enough (same
// time-vs-distance-weighting gap as pace/speed) to trust. No ground-contact-
// time or stride-length per-second series found in this or any other field;
// those exist only as this workout's summary averages (see WorkoutSummaryFields).
function decodeCadenceAndVerticalRatio(raw: unknown): { cadenceSpm: DecodedPoint[]; verticalStrideRatioPct: DecodedPoint[] } {
  const entries = splitEntries(raw);
  const cadenceSpm: DecodedPoint[] = [];
  const verticalStrideRatioPct: DecodedPoint[] = [];
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const vertRatio = Number(parts[2]);
    const cadence = Number(parts[3]);
    if (Number.isFinite(cadence) && cadence > 0) cadenceSpm.push({ offsetSeconds: i, value: cadence });
    if (Number.isFinite(vertRatio) && cadence > 0) verticalStrideRatioPct.push({ offsetSeconds: i, value: vertRatio / 10 });
  });
  return { cadenceSpm, verticalStrideRatioPct };
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.asin(Math.sqrt(a));
}

// longitude_latitude: first pair is absolute (lat*1e8, lon*1e8); every pair
// after that is (dlat*1e8, dlon*1e8) to cumulatively sum. Produces cumulative
// distance (km), not the raw lat/lon track -- that's all SessionDetail needs
// today; the reconstructed positions themselves aren't kept.
function decodeDistanceKm(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  let lat = 0, lon = 0, cumMeters = 0;
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const a = Number(parts[0]);
    const b = Number(parts[1]);
    if (!Number.isFinite(a) || !Number.isFinite(b)) return;
    if (i === 0) {
      lat = a / 1e8;
      lon = b / 1e8;
    } else {
      const newLat = lat + a / 1e8;
      const newLon = lon + b / 1e8;
      cumMeters += haversineMeters(lat, lon, newLat, newLon);
      lat = newLat;
      lon = newLon;
    }
    out.push({ offsetSeconds: i, value: cumMeters / 1000 });
  });
  return out;
}

// Minetti's cost curve is a running model -- cycling/strength get no GAP/EF.
const RUN_SPORT_TYPES = new Set(["1", "7"]);

function decodeWorkoutDetail(detailRawBody: unknown, summary: WorkoutSummaryFields, sportType: string): DecodedSeries | null {
  if (!detailRawBody || typeof detailRawBody !== "object") return null;
  const data = (detailRawBody as Record<string, unknown>).data;
  if (!data || typeof data !== "object") return null;
  const d = data as Record<string, unknown>;
  const { cadenceSpm, verticalStrideRatioPct } = decodeCadenceAndVerticalRatio(d.gait);
  const series = {
    heartRate: decodeHeartRate(d.heart_rate),
    speedKmh: decodeSpeedKmh(d.speed),
    altitudeM: decodeAltitude(d.altitude),
    distanceKm: decodeDistanceKm(d.longitude_latitude),
    cadenceSpm,
    verticalStrideRatioPct,
  };
  const effort = RUN_SPORT_TYPES.has(sportType)
    ? computeEffort({ ...series, lactateThresholdHrBpm: summary.lactateThresholdHrBpm })
    : null;
  return { ...series, summary: { ...summary, ...(effort ?? {}) } };
}

// DAV-112/123: a workout's real per-point pace/power/GPS lives behind a *second*
// call, not the list endpoint below -- confirmed independently in two reference
// implementations (zepp-health-cli, ZeppBridge's zepp.rs). See
// docs/zepp-integration/02-token-capture-and-data-extraction.md §2.
const WORKOUT_DETAIL_METRIC = "sport_history_detail";

interface WorkoutRef {
  trackId: string;
  source: string;
  // ISO timestamps, derived from the list entry's own end_time/run_time --
  // empty when those fields are missing/unparseable, in which case the
  // caller stores the raw detail payload but skips reconciliation (no
  // reliable time to match or insert with).
  startTimeIso: string;
  endTimeIso: string;
  sportType: string;
  // Workout-level averages -- these live in the *summary* entry (this list
  // response), not detail.json, so they're carried through here rather than
  // decoded from the detail payload. null when the summary entry doesn't
  // have them (e.g. a non-running sport, or a device that doesn't report
  // dynamics). See docs/zepp-integration/03-workout-detail-field-decode.md.
  avgCadenceSpm: number | null;
  maxCadenceSpm: number | null;
  avgStrideLengthCm: number | null;
  avgGroundContactMs: number | null;
  avgVerticalStrideRatioPct: number | null;
  // Zepp recomputes this per qualifying run, not from one dedicated test --
  // "current" is whichever run was synced most recently, not a fixed event.
  lactateThresholdHrBpm: number | null;
  lactateThresholdPaceSecPerKm: number | null;
}

function numOrNull(v: unknown): number | null {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

// DAV-115 real bug, found 2026-09-25: exercise_sessions.start_time for
// Health-Connect-sourced rows is the device's *local wall-clock* reading
// written with a UTC-looking suffix, not a real UTC instant (see
// HealthConnectExerciseSyncRepository's own comment on this convention,
// and SessionDetailScreen.kt's "reconstruct the real instant via the
// device's zone" reversal of it). Storing a genuine UTC instant for Zepp
// rows in that same column broke every match by exactly the local UTC
// offset (8h for this Taiwan account) -- confirmed against a real pair:
// a Health-Connect ride at "2026-09-05 08:54:24+00" and the same workout's
// Zepp entry landing at "2026-09-05 00:54:24+00", 8h apart, both really the
// same moment. Fixed by reproducing the same "local time, UTC-labeled"
// value Zepp's own summary already tells us the local zone for
// (`syncedTimezone`, e.g. "Asia/Taipei") -- Taipei as the fallback since
// this is a single-user, Taiwan-based account, not a guess made blind.
const FALLBACK_TIMEZONE = "Asia/Taipei";

// Confirmed codes only: 1=run, 7=trail run, 9=outdoor cycling, 52=strength.
// Anything else is 'other' until DAV-268's full mapping lands.
function exerciseTypeForZeppSport(sportType: string): string {
  switch (sportType) {
    case "1":
    case "7":
      return "run";
    case "9":
      return "ride";
    case "52":
      return "strength";
    default:
      return "other";
  }
}

function toLocalLabeledUtcIso(realUtcMs: number, timeZone: string): string {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
  const parts = dtf.formatToParts(new Date(realUtcMs));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "00";
  const hour = get("hour") === "24" ? "00" : get("hour");
  return `${get("year")}-${get("month")}-${get("day")}T${hour}:${get("minute")}:${get("second")}.000Z`;
}

// Confirmed 2026-09-25 against a real account (curl, not guessed): the envelope
// is `{code, message, data: {next, summary: [...]}}`. Each `summary` entry has
// numeric `trackid` and a string `source` (e.g. "run.10289411.huami.com") --
// exactly the pair `detail.json` needs -- plus `end_time`/`run_time` (both unix
// seconds, as strings), used below to derive each workout's real start/end for
// reconciliation. Kept the bare-array/`items` fallbacks too since they're free
// and harmless if a different sport/region ever wraps it differently; `[]` on a
// wrong guess still can't take down the extraction run.
function extractWorkoutRefs(historyResponse: unknown): WorkoutRef[] {
  if (!historyResponse || typeof historyResponse !== "object") return [];
  const obj = historyResponse as Record<string, unknown>;
  const data = obj.data as Record<string, unknown> | undefined;

  const entries: unknown[] = Array.isArray(historyResponse)
    ? historyResponse
    : Array.isArray(obj.items)
    ? obj.items as unknown[]
    : Array.isArray(data?.summary)
    ? data!.summary as unknown[]
    : Array.isArray(obj.data)
    ? obj.data as unknown[]
    : Array.isArray(data?.items)
    ? data!.items as unknown[]
    : [];

  const refs: WorkoutRef[] = [];
  for (const entry of entries) {
    if (!entry || typeof entry !== "object") continue;
    const e = entry as Record<string, unknown>;
    const rawId = e.trackid ?? e.trackId ?? e.track_id;
    if (rawId === undefined || rawId === null || rawId === "") continue;

    const endSec = Number(e.end_time);
    const runSec = Number(e.run_time);
    const hasEnd = Number.isFinite(endSec) && endSec > 0;
    const hasRun = Number.isFinite(runSec) && runSec >= 0;
    const tz = typeof e.syncedTimezone === "string" && e.syncedTimezone ? e.syncedTimezone : FALLBACK_TIMEZONE;
    const endTimeIso = hasEnd ? toLocalLabeledUtcIso(endSec * 1000, tz) : "";
    const startTimeIso = hasEnd && hasRun ? toLocalLabeledUtcIso((endSec - runSec) * 1000, tz) : "";

    refs.push({
      trackId: String(rawId),
      source: String(e.source ?? ""),
      startTimeIso,
      endTimeIso,
      sportType: String(e.type ?? ""),
      avgCadenceSpm: numOrNull(e.avg_frequency),
      maxCadenceSpm: numOrNull(e.max_frequency),
      avgStrideLengthCm: numOrNull(e.avg_stride_length),
      avgGroundContactMs: numOrNull(e.averageGct),
      avgVerticalStrideRatioPct: numOrNull(e.avgVertStrideRatio) !== null
        ? (e.avgVertStrideRatio as number) / 10
        : null,
      lactateThresholdHrBpm: numOrNull(e.lactateThresholdHr),
      lactateThresholdPaceSecPerKm: numOrNull(e.lactateThresholdPace),
    });
  }
  return refs;
}

// Metric definitions: which Zepp mobile API endpoints to hit and how to
// build the URL for a given date. The user_id placeholder is filled at
// runtime from ZEPP_USER_ID.
interface MetricDef {
  metric: string;
  endpoint: (userId: string, date: string) => string;
}

const METRIC_DEFS: MetricDef[] = [
  {
    // v9: band_data is now a single "detail" query per day (matching
    // ZeppBridge's fetch_band_data), returning heart_rate + hrv + sleep +
    // spo2 in one response. The v1 endpoint with query_type=detail,
    // from_date/to_date, and byteLength=8 is the shape the mobile app
    // actually uses -- /v2/ with query_type=summary was never real.
    metric: "band_data",
    endpoint: (uid, date) =>
      `/v1/data/band_data.json?query_type=detail&device_type=0&userid=${uid}&from_date=${date}&to_date=${date}&byteLength=8`,
  },
  {
    // Stress: a user-events timeline, not a band_data field. `from`/`to`
    // are epoch-ms bounds; this metric's fetchAndStore call already runs
    // once per requested day (the loop below), so each call's window is
    // just that one UTC day.
    metric: "stress",
    endpoint: (uid, date) => {
      const startMs = new Date(`${date}T00:00:00Z`).getTime();
      const endMs = startMs + 86400000 - 1;
      return `/users/${uid}/events?eventType=all_day_stress&from=${startMs}&to=${endMs}&limit=2000&reverse=0&userId=${uid}`;
    },
  },
  {
    metric: "training_load",
    endpoint: (uid, _date) =>
      `/v2/watch/users/${uid}/WatchSportStatistics/SPORT_LOAD`,
  },
  {
    metric: "vo2max",
    endpoint: (uid, _date) =>
      `/v2/watch/users/${uid}/WatchSportStatistics/VO2_MAX`,
  },
  {
    metric: "sport_history",
    // Confirmed real param shape (zepp-health-cli, ZeppBridge): userid +
    // startTrackId/stopTrackId cursor bounds, not a `date` filter -- the
    // original guess here was never tested against a live token. Use the
    // requested day's UTC boundaries as the cursor window, matching
    // zepp-health-cli's own single-day usage.
    endpoint: (uid, date) => {
      const startOfDay = Math.floor(new Date(`${date}T00:00:00Z`).getTime() / 1000);
      const startOfNextDay = startOfDay + 86400;
      return `/v1/sport/run/history.json?userid=${uid}&startTrackId=${startOfDay}` +
        `&stopTrackId=${startOfNextDay}&need_sub_data=1&type=`;
    },
  },
];

// v10: sleep stage modes from the Zepp band_data summary.
// Confirmed against a real 2026-10-01 response and ZeppBridge's conventions:
// 4=light, 5=deep, 7=awake, 8=REM. Only AWAKE is used in the decoder
// (to subtract awake time from total time-in-bed); the summary's own
// dp/lt/dt fields give deep/light/REM minutes pre-aggregated.
const SLEEP_MODE_AWAKE = 7;

interface BandDataDecoded {
  sleepHours: number | null;
  sleepScore: number | null;
  bedtimeIso: string | null;
  wakeTimeIso: string | null;
  deepMin: number | null;
  remMin: number | null;
  lightMin: number | null;
  rhr: number | null;
  steps: number | null;
}

function decodeBandDataSummary(rawBody: unknown): BandDataDecoded | null {
  if (!rawBody || typeof rawBody !== "object") return null;
  const body = rawBody as Record<string, unknown>;
  if (body.code !== 1) return null;
  const dataArr = body.data;
  if (!Array.isArray(dataArr) || dataArr.length === 0) return null;
  const entry = dataArr[0] as Record<string, unknown>;
  const summaryB64 = entry.summary;
  if (typeof summaryB64 !== "string" || !summaryB64) return null;

  let summary: Record<string, unknown>;
  try {
    const decoded = atob(summaryB64);
    summary = JSON.parse(decoded);
  } catch {
    return null;
  }

  const slp = summary.slp as Record<string, unknown> | undefined;
  let sleepHours: number | null = null;
  let sleepScore: number | null = null;
  let bedtimeIso: string | null = null;
  let wakeTimeIso: string | null = null;
  let deepMin: number | null = null;
  let remMin: number | null = null;
  let lightMin: number | null = null;

  if (slp) {
    const st = Number(slp.st);
    const ed = Number(slp.ed);
    if (Number.isFinite(st) && Number.isFinite(ed) && ed > st) {
      const totalSeconds = ed - st;
      // Subtract awake stage minutes from total time-in-bed, matching
      // HealthConnectDailySyncRepository's own awake subtraction.
      const stages = slp.stage as Array<{ start: number; stop: number; mode: number }> | undefined;
      let awakeMin = 0;
      if (Array.isArray(stages)) {
        for (const s of stages) {
          if (s.mode === SLEEP_MODE_AWAKE) awakeMin += s.stop - s.start;
        }
      }
      sleepHours = (totalSeconds / 3600) - (awakeMin / 60);
      if (sleepHours < 0) sleepHours = 0;
      bedtimeIso = new Date(st * 1000).toISOString();
      wakeTimeIso = new Date(ed * 1000).toISOString();
    }
    const dp = Number(slp.dp);
    if (Number.isFinite(dp) && dp >= 0) deepMin = dp;
    const dt = Number(slp.dt);
    if (Number.isFinite(dt) && dt >= 0) remMin = dt;
    const lt = Number(slp.lt);
    if (Number.isFinite(lt) && lt >= 0) lightMin = lt;
    const ss = Number(slp.ss);
    if (Number.isFinite(ss) && ss > 0) sleepScore = ss;
  }

  const rhrVal = Number(summary.slp && (summary.slp as Record<string, unknown>).rhr);
  const rhr = Number.isFinite(rhrVal) && rhrVal > 0 ? rhrVal : null;

  const stp = summary.stp as Record<string, unknown> | undefined;
  const stepsVal = stp ? Number(stp.ttl) : NaN;
  const steps = Number.isFinite(stepsVal) && stepsVal >= 0 ? stepsVal : null;

  return { sleepHours, sleepScore, bedtimeIso, wakeTimeIso, deepMin, remMin, lightMin, rhr, steps };
}

function parseDateRange(
  from: string,
  to: string,
): { dates: string[]; error?: string } {
  const start = new Date(from + "T00:00:00Z");
  const end = new Date(to + "T00:00:00Z");
  if (isNaN(start.getTime()) || isNaN(end.getTime())) {
    return { dates: [], error: "Invalid date format. Use YYYY-MM-DD." };
  }
  if (end < start) {
    return { dates: [], error: "'to' must be >= 'from'." };
  }
  const diffDays =
    (end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24) + 1;
  if (diffDays > 31) {
    return { dates: [], error: "Max 31 days per extraction." };
  }
  const dates: string[] = [];
  const cursor = new Date(start);
  while (cursor <= end) {
    dates.push(cursor.toISOString().slice(0, 10));
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return { dates };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // --- Auth ---
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return json({ error: "missing_auth" }, 401);
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_ANON_KEY")!,
      { global: { headers: { Authorization: authHeader } } },
    );

    const token = authHeader.replace("Bearer ", "");
    const {
      data: { user },
      error: userError,
    } = await supabase.auth.getUser(token);
    if (userError || !user) {
      return json({ error: "unauthorized" }, 401);
    }

    // --- Zepp config ---
    const zeppToken = Deno.env.get("ZEPP_APP_TOKEN");
    const zeppUserId = Deno.env.get("ZEPP_USER_ID");
    const zeppHost = Deno.env.get("ZEPP_API_HOST");
    if (!zeppToken || !zeppUserId || !zeppHost) {
      return json({
        error: "not_configured",
        message:
          "ZEPP_APP_TOKEN, ZEPP_USER_ID, and ZEPP_API_HOST must be set as Edge Function secrets.",
      }, 500);
    }

    // --- Parse request ---
    // Accepts params either as a query string (manual/curl testing) or a JSON
    // body (the Android client's supabase-kt functions.invoke, which posts a
    // body rather than building a query string) -- body wins if both present.
    const url = new URL(req.url);
    let bodyParams: Record<string, string> = {};
    try {
      const bodyText = await req.text();
      if (bodyText) {
        const parsed = JSON.parse(bodyText);
        if (parsed && typeof parsed === "object") {
          for (const k of ["from", "to", "metrics"]) {
            if (typeof parsed[k] === "string") bodyParams[k] = parsed[k];
          }
        }
      }
    } catch {
      // no body, or not JSON -- query params still work below
    }

    const from = bodyParams.from ?? url.searchParams.get("from");
    const to = bodyParams.to ?? url.searchParams.get("to") ?? from;
    const metricsParam = bodyParams.metrics ?? url.searchParams.get("metrics");

    if (!from) {
      return json({
        error: "missing_params",
        message: "Required: ?from=YYYY-MM-DD (optional: &to=YYYY-MM-DD&metrics=hrv,sleep,...)",
      }, 400);
    }

    const { dates, error: dateError } = parseDateRange(from, to!);
    if (dateError) {
      return json({ error: "invalid_params", message: dateError }, 400);
    }

    // v9: the old per-type band_data metrics (heart_rate, hrv, sleep, spo2)
    // are now a single "band_data" metric. Accept the old names as aliases
    // so existing callers don't break.
    const BAND_DATA_ALIASES = new Set(["heart_rate", "hrv", "sleep", "spo2"]);
    let requestedMetrics = metricsParam
      ? metricsParam.split(",").map((m) => m.trim())
      : METRIC_DEFS.map((d) => d.metric);
    if (requestedMetrics.some((m) => BAND_DATA_ALIASES.has(m))) {
      requestedMetrics = [
        ...requestedMetrics.filter((m) => !BAND_DATA_ALIASES.has(m)),
        "band_data",
      ];
    }

    const defs = METRIC_DEFS.filter((d) =>
      requestedMetrics.includes(d.metric)
    );
    if (defs.length === 0) {
      return json({
        error: "invalid_params",
        message: `Unknown metrics. Available: ${METRIC_DEFS.map((d) => d.metric).join(", ")}`,
      }, 400);
    }

    // --- Extract ---
    const results: Array<{
      metric: string;
      date: string;
      status: number;
      stored: boolean;
      error?: string;
    }> = [];

    // track_id defaults to '' (not null) for every non-detail metric -- a nullable
    // track_id would break upsert idempotency, since Postgres unique constraints
    // never treat two NULLs as a conflict (see the schema comment/migration).
    async function fetchAndStore(
      endpoint: string,
      metric: string,
      date: string,
      trackId: string,
    ): Promise<{ statusCode: number; rawBody: unknown; stored: boolean; error?: string }> {
      // v8: append the same "r" cache-buster every zepp-health-cli request
      // carries (a random UUID, not a validated value -- just present).
      const sep = endpoint.includes("?") ? "&" : "?";
      const fetchUrl = `https://${zeppHost}${endpoint}${sep}r=${crypto.randomUUID().toUpperCase()}`;
      let statusCode: number;
      let rawBody: unknown = null;

      try {
        const res = await fetch(fetchUrl, {
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
        });
        statusCode = res.status;
        const text = await res.text();
        try {
          rawBody = JSON.parse(text);
        } catch {
          rawBody = { _raw_text: text };
        }
      } catch (e) {
        statusCode = 0;
        rawBody = { _fetch_error: String(e) };
      }

      const { error: upsertError } = await supabase
        .from("zepp_raw_extracts")
        .upsert(
          {
            user_id: user.id,
            metric,
            query_date: date,
            track_id: trackId,
            api_host: zeppHost,
            endpoint,
            status_code: statusCode,
            raw_body: rawBody,
            fetched_at: new Date().toISOString(),
            extractor_version: EXTRACTOR_VERSION,
          },
          { onConflict: "user_id,metric,query_date,track_id" },
        );

      return { statusCode, rawBody, stored: !upsertError, error: upsertError?.message };
    }

    // DAV-115: match a Zepp workout to an existing Health-Connect-sourced
    // exercise_sessions row by start_time proximity rather than duplicating it;
    // insert a new source='zepp' row only when nothing matches. Also tracks the
    // highest track_id seen, for zepp_sync_state below.
    const RECONCILE_TOLERANCE_MS = 2 * 60 * 1000;
    let maxTrackIdSeen = 0;

    async function reconcileWorkout(
      ref: WorkoutRef,
      detailRawBody: unknown,
    ): Promise<void> {
      if (!ref.startTimeIso || !ref.endTimeIso) return; // no reliable time to reconcile with

      const startMs = new Date(ref.startTimeIso).getTime();
      const { data: matches } = await supabase
        .from("exercise_sessions")
        .select("id")
        .eq("user_id", user.id)
        .gte("start_time", new Date(startMs - RECONCILE_TOLERANCE_MS).toISOString())
        .lte("start_time", new Date(startMs + RECONCILE_TOLERANCE_MS).toISOString())
        .limit(1);

      let exerciseSessionId: number | null = matches?.[0]?.id ?? null;

      if (exerciseSessionId === null) {
        // Unmatched: this workout isn't in Health Connect yet. Insert minimally
        // -- aggregate fields (distance/calories/hr) are left for the decode
        // pass once detail.json's real field semantics are confirmed, rather
        // than guessing units now. Type comes from Zepp's sport code (DAV-268
        // has the full mapping; unknown codes land as 'other', never 'run').
        const { data: inserted, error: insertError } = await supabase
          .from("exercise_sessions")
          .insert({
            user_id: user.id,
            type: exerciseTypeForZeppSport(ref.sportType),
            source: "zepp",
            start_time: ref.startTimeIso,
            end_time: ref.endTimeIso,
            duration_min: Math.round((new Date(ref.endTimeIso).getTime() - startMs) / 60000),
          })
          .select("id")
          .single();
        // Surfacing this insert's error mattered in practice: it silently
        // failed once already (a since-fixed CHECK constraint didn't allow
        // source='zepp') and the swallowed error hid it -- DAV-115's own
        // acceptance criterion is "no silent data loss."
        if (insertError) throw new Error(`exercise_sessions insert failed: ${insertError.message}`);
        exerciseSessionId = inserted?.id ?? null;
      }

      const decoded = decodeWorkoutDetail(detailRawBody, {
        avgCadenceSpm: ref.avgCadenceSpm,
        maxCadenceSpm: ref.maxCadenceSpm,
        avgStrideLengthCm: ref.avgStrideLengthCm,
        avgGroundContactMs: ref.avgGroundContactMs,
        avgVerticalStrideRatioPct: ref.avgVerticalStrideRatioPct,
        lactateThresholdHrBpm: ref.lactateThresholdHrBpm,
        lactateThresholdPaceSecPerKm: ref.lactateThresholdPaceSecPerKm,
      }, ref.sportType);

      const { error: detailUpsertError } = await supabase.from("zepp_workout_detail").upsert(
        {
          user_id: user.id,
          exercise_session_id: exerciseSessionId,
          zepp_track_id: ref.trackId,
          zepp_source: ref.source,
          start_time: ref.startTimeIso,
          end_time: ref.endTimeIso,
          sport_type: ref.sportType,
          raw: detailRawBody,
          decoded,
          fetched_at: new Date().toISOString(),
        },
        { onConflict: "user_id,zepp_track_id" },
      );
      if (detailUpsertError) throw new Error(`zepp_workout_detail upsert failed: ${detailUpsertError.message}`);

      // DAV-274 real bug: exercise_sessions.distance_km (Health Connect's own
      // multi-source-summed figure, sometimes wrong by 2-3x -- see
      // domain/Training.kt's reconcileDistanceWithSpeed on the Android side)
      // is never corrected once a real Zepp GPS track exists for the same
      // session, matched or not. Zepp's decoded per-second distance is real
      // GPS ground truth (repeatedly cross-checked this session) -- write it
      // back outright, same "Zepp wins when present" principle
      // mergePreferZepp() already applies to the per-second series client-side.
      const zeppDistanceKm = decoded?.distanceKm?.at(-1)?.value;
      if (exerciseSessionId !== null && zeppDistanceKm && zeppDistanceKm > 0) {
        const { error: distanceUpdateError } = await supabase
          .from("exercise_sessions")
          .update({ distance_km: zeppDistanceKm })
          .eq("id", exerciseSessionId);
        if (distanceUpdateError) throw new Error(`exercise_sessions distance update failed: ${distanceUpdateError.message}`);
      }

      const n = Number(ref.trackId);
      if (Number.isFinite(n) && n > maxTrackIdSeen) maxTrackIdSeen = n;
    }

    // DAV-274: the Zepp sync and the Health Connect sync both run on app open
    // and can race -- if Zepp's reconcile query above runs before Health
    // Connect's own sync has inserted its row for the same real session, the
    // match finds nothing and a second source='zepp' placeholder gets
    // inserted (confirmed against real created_at timestamps 12s apart for
    // the account's 2026-09-26 run). zepp_workout_detail then stays linked to
    // that orphan forever, so neither the corrected distance above nor the
    // real per-second series (splits, charts) ever reaches the row the app
    // actually displays. Self-heals here: by the *next* sync, Health Connect
    // has normally caught up, so re-run the same match this time and merge.
    async function mergeOrphanedZeppSessions(): Promise<number> {
      const { data: orphans } = await supabase
        .from("exercise_sessions")
        .select("id,start_time")
        .eq("user_id", user.id)
        .eq("source", "zepp");
      if (!orphans || orphans.length === 0) return 0;

      let merged = 0;
      for (const orphan of orphans) {
        const orphanMs = new Date(orphan.start_time).getTime();
        const { data: matches } = await supabase
          .from("exercise_sessions")
          .select("id")
          .eq("user_id", user.id)
          .neq("id", orphan.id)
          .neq("source", "zepp")
          .gte("start_time", new Date(orphanMs - RECONCILE_TOLERANCE_MS).toISOString())
          .lte("start_time", new Date(orphanMs + RECONCILE_TOLERANCE_MS).toISOString())
          .limit(1);
        const realId = matches?.[0]?.id;
        if (realId === undefined) continue;

        // Repoint both real FKs to exercise_sessions before deleting the orphan.
        const { error: repointDetailError } = await supabase
          .from("zepp_workout_detail")
          .update({ exercise_session_id: realId })
          .eq("exercise_session_id", orphan.id);
        if (repointDetailError) throw new Error(`zepp_workout_detail repoint failed: ${repointDetailError.message}`);
        const { error: repointRouteError } = await supabase
          .from("planned_routes")
          .update({ exercise_session_id: realId })
          .eq("exercise_session_id", orphan.id);
        if (repointRouteError) throw new Error(`planned_routes repoint failed: ${repointRouteError.message}`);

        const { error: deleteError } = await supabase.from("exercise_sessions").delete().eq("id", orphan.id);
        if (deleteError) throw new Error(`orphaned exercise_sessions delete failed: ${deleteError.message}`);
        merged++;
      }
      return merged;
    }

    try {
      const merged = await mergeOrphanedZeppSessions();
      if (merged > 0) results.push({ metric: "zepp_orphan_merge", date: "", status: 200, stored: true, error: `merged ${merged}` });
    } catch (e) {
      results.push({ metric: "zepp_orphan_merge", date: "", status: 0, stored: false, error: String(e) });
    }

    for (const date of dates) {
      for (const def of defs) {
        const endpoint = def.endpoint(zeppUserId, date);
        const { statusCode, rawBody, stored, error } = await fetchAndStore(endpoint, def.metric, date, "");
        results.push({ metric: def.metric, date, status: statusCode, stored, ...(error ? { error } : {}) });

        // v10: decode band_data and upsert sleep/wearable daily rows.
        if (def.metric === "band_data" && statusCode >= 200 && statusCode < 300) {
          try {
            const decoded = decodeBandDataSummary(rawBody);
            if (decoded) {
              if (decoded.sleepHours !== null && decoded.bedtimeIso && decoded.wakeTimeIso) {
                const sleepRow: Record<string, unknown> = {
                  user_id: user.id,
                  date,
                  hours: decoded.sleepHours,
                  bedtime: decoded.bedtimeIso,
                  wake_time: decoded.wakeTimeIso,
                };
                if (decoded.sleepScore !== null) sleepRow.score = decoded.sleepScore;
                if (decoded.deepMin !== null) sleepRow.deep_min = decoded.deepMin;
                if (decoded.remMin !== null) sleepRow.rem_min = decoded.remMin;
                if (decoded.lightMin !== null) sleepRow.light_min = decoded.lightMin;
                const { error: sleepErr } = await supabase.from("sleep_daily")
                  .upsert(sleepRow, { onConflict: "user_id,date" });
                if (sleepErr) throw new Error(`sleep_daily upsert: ${sleepErr.message}`);
              }
              const wearableUpdates: Record<string, unknown> = { user_id: user.id, date };
              let hasWearable = false;
              if (decoded.rhr !== null) { wearableUpdates.rhr = decoded.rhr; hasWearable = true; }
              if (decoded.steps !== null) { wearableUpdates.steps = decoded.steps; hasWearable = true; }
              if (hasWearable) {
                const { error: wearErr } = await supabase.from("wearable_daily")
                  .upsert(wearableUpdates, { onConflict: "user_id,date" });
                if (wearErr) throw new Error(`wearable_daily upsert: ${wearErr.message}`);
              }
              results.push({ metric: "band_data_decode", date, status: 200, stored: true });
            }
          } catch (e) {
            results.push({ metric: "band_data_decode", date, status: 0, stored: false, error: String(e) });
          }
        }

        // DAV-112/123: sport_history is the list; follow up with the real
        // per-point detail payload for each workout it references.
        if (def.metric === "sport_history" && statusCode >= 200 && statusCode < 300) {
          let refs: WorkoutRef[] = [];
          try {
            refs = extractWorkoutRefs(rawBody);
          } catch (e) {
            results.push({
              metric: WORKOUT_DETAIL_METRIC,
              date,
              status: 0,
              stored: false,
              error: `extractWorkoutRefs failed: ${String(e)}`,
            });
          }

          for (const ref of refs) {
            const detailEndpoint =
              `/v1/sport/run/detail.json?trackid=${encodeURIComponent(ref.trackId)}` +
              `&source=${encodeURIComponent(ref.source)}`;
            const detail = await fetchAndStore(detailEndpoint, WORKOUT_DETAIL_METRIC, date, ref.trackId);
            results.push({
              metric: WORKOUT_DETAIL_METRIC,
              date,
              status: detail.statusCode,
              stored: detail.stored,
              ...(detail.error ? { error: detail.error } : {}),
            });

            if (detail.stored && detail.statusCode >= 200 && detail.statusCode < 300) {
              try {
                await reconcileWorkout(ref, detail.rawBody);
              } catch (e) {
                results.push({
                  metric: "zepp_reconcile",
                  date,
                  status: 0,
                  stored: false,
                  error: String(e),
                });
              }
            }
          }
        }
      }
    }

    // Surface auth failures (expired ~30-day apptoken) distinctly from a
    // successful-but-empty run, so Settings can tell "re-auth needed" apart
    // from "nothing new today" (DAV-118).
    const authFailed = results.some((r) => r.status === 401 || r.status === 403);
    if (authFailed) {
      await supabase.from("zepp_sync_state").upsert(
        {
          user_id: user.id,
          last_error: "Zepp API returned 401/403 -- apptoken likely expired, re-capture needed.",
          last_error_at: new Date().toISOString(),
        },
        { onConflict: "user_id" },
      );
    } else {
      const update: Record<string, unknown> = { user_id: user.id, last_error: null, last_error_at: null };
      if (maxTrackIdSeen > 0) {
        update.last_synced_track_id = String(maxTrackIdSeen);
        update.last_synced_at = new Date().toISOString();
      }
      await supabase.from("zepp_sync_state").upsert(update, { onConflict: "user_id" });
    }

    const succeeded = results.filter((r) => r.stored && r.status >= 200 && r.status < 300).length;
    const failed = results.length - succeeded;

    return json({
      summary: { total: results.length, succeeded, failed },
      results,
    }, 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});
