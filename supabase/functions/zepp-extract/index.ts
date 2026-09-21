// zepp-extract — DAV-112
//
// Supabase Edge Function that extracts data from the Zepp mobile API
// and stores raw responses in zepp_raw_extracts for inspection/decoding.
// All Zepp credentials stay server-side (env vars); the caller only needs
// a valid Supabase JWT and a date range.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

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

const EXTRACTOR_VERSION = "1";

// Metric definitions: which Zepp mobile API endpoints to hit and how to
// build the URL for a given date. The user_id placeholder is filled at
// runtime from ZEPP_USER_ID.
interface MetricDef {
  metric: string;
  endpoint: (userId: string, date: string) => string;
}

const METRIC_DEFS: MetricDef[] = [
  {
    metric: "heart_rate",
    endpoint: (uid, date) =>
      `/v2/data/band_data.json?query_type=summary&device_type=0&userid=${uid}&date=${date}`,
  },
  {
    metric: "hrv",
    endpoint: (uid, date) =>
      `/v2/data/band_data.json?query_type=summary&device_type=0&userid=${uid}&date=${date}&data_type=hrv`,
  },
  {
    metric: "sleep",
    endpoint: (uid, date) =>
      `/v2/data/band_data.json?query_type=summary&device_type=0&userid=${uid}&date=${date}&data_type=sleep`,
  },
  {
    metric: "spo2",
    endpoint: (uid, date) =>
      `/v2/data/band_data.json?query_type=summary&device_type=0&userid=${uid}&date=${date}&data_type=spo2`,
  },
  {
    metric: "stress",
    endpoint: (uid, date) =>
      `/v2/data/band_data.json?query_type=summary&device_type=0&userid=${uid}&date=${date}&data_type=stress`,
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
    endpoint: (_uid, date) =>
      `/v1/sport/run/history.json?date=${date}`,
  },
];

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
    const url = new URL(req.url);
    const from = url.searchParams.get("from");
    const to = url.searchParams.get("to") || from;
    const metricsParam = url.searchParams.get("metrics");

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

    const requestedMetrics = metricsParam
      ? metricsParam.split(",").map((m) => m.trim())
      : METRIC_DEFS.map((d) => d.metric);

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

    for (const date of dates) {
      for (const def of defs) {
        const endpoint = def.endpoint(zeppUserId, date);
        const fetchUrl = `https://${zeppHost}${endpoint}`;

        let statusCode: number;
        let rawBody: unknown = null;

        try {
          const res = await fetch(fetchUrl, {
            headers: {
              apptoken: zeppToken,
              "Content-Type": "application/json",
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

        // Upsert into zepp_raw_extracts (idempotent on user_id+metric+date)
        const { error: upsertError } = await supabase
          .from("zepp_raw_extracts")
          .upsert(
            {
              user_id: user.id,
              metric: def.metric,
              query_date: date,
              api_host: zeppHost,
              endpoint,
              status_code: statusCode,
              raw_body: rawBody,
              fetched_at: new Date().toISOString(),
              extractor_version: EXTRACTOR_VERSION,
            },
            { onConflict: "user_id,metric,query_date" },
          );

        results.push({
          metric: def.metric,
          date,
          status: statusCode,
          stored: !upsertError,
          ...(upsertError ? { error: upsertError.message } : {}),
        });
      }
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
