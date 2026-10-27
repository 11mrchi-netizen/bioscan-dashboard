// Routine intelligence R5 (docs/routine-intelligence/01-research-and-plan.md, section 10): Taipei's MRT stations, MRT
// travel times between adjacent stations and YouBike docks with live bikes and free slots, from Taiwan's TDX platform, for
// the phone's commute alternatives (android domain/routine/CommuteAlternatives.kt).
//
// Privacy (plan section 8.3, rule 3): the request carries no location and the function reads no user data. It returns
// whole lists; the phone finds the stations near its own places.
//
// Credentials: TDX_CLIENT_ID and TDX_CLIENT_SECRET as Edge Function secrets (a free TDX account's API key). Without them
// the function answers 503 tdx_not_configured and the app says so.
//
// NOT VERIFIED against the live API: the session that wrote this could not reach tdx.transportdata.tw. The paths and
// field names follow TDX's v2 layout (the former PTX API): Rail/Metro/Station, Rail/Metro/S2STravelTime, Bike/Station and
// Bike/Availability, with an OIDC client-credentials token. Check one call of each before relying on the numbers.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const TOKEN_URL = "https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token";
const API = "https://tdx.transportdata.tw/api/basic/v2";
const METRO_OPERATOR = "TRTC";
const BIKE_CITIES = ["Taipei", "NewTaipei"];
const STATIC_TTL_MS = 24 * 60 * 60_000; // stations and travel times change rarely
const LIVE_TTL_MS = 60_000; // YouBike availability

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

let token: { value: string; expiresAt: number } | null = null;
const cache = new Map<string, { at: number; data: unknown }>();

async function accessToken(id: string, secret: string): Promise<string> {
  if (token && token.expiresAt > Date.now() + 60_000) return token.value;
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "client_credentials", client_id: id, client_secret: secret }),
  });
  if (!res.ok) throw new Error(`TDX token: ${res.status}`);
  const body = await res.json();
  token = { value: String(body.access_token), expiresAt: Date.now() + Number(body.expires_in ?? 3600) * 1000 };
  return token.value;
}

// deno-lint-ignore no-explicit-any
async function tdx(path: string, bearer: string, ttl: number): Promise<any[]> {
  const hit = cache.get(path);
  if (hit && Date.now() - hit.at < ttl) return hit.data as unknown[];
  const res = await fetch(`${API}/${path}${path.includes("?") ? "&" : "?"}$format=JSON`, {
    headers: { Authorization: `Bearer ${bearer}`, "Accept-Encoding": "gzip" },
  });
  if (!res.ok) throw new Error(`TDX ${path}: ${res.status}`);
  const data = await res.json();
  cache.set(path, { at: Date.now(), data });
  return data;
}

const num = (v: unknown): number | null => (v === null || v === undefined || !Number.isFinite(Number(v)) ? null : Number(v));

Deno.serve(async (req) => {
  if (req.method !== "POST" && req.method !== "GET") return json({ error: "method_not_allowed" }, 405);
  const id = Deno.env.get("TDX_CLIENT_ID");
  const secret = Deno.env.get("TDX_CLIENT_SECRET");
  if (!id || !secret) return json({ error: "tdx_not_configured" }, 503);
  try {
    const bearer = await accessToken(id, secret);
    const [stations, travel, ...bikes] = await Promise.all([
      tdx(`Rail/Metro/Station/${METRO_OPERATOR}`, bearer, STATIC_TTL_MS),
      tdx(`Rail/Metro/S2STravelTime/${METRO_OPERATOR}`, bearer, STATIC_TTL_MS),
      ...BIKE_CITIES.flatMap((c) => [tdx(`Bike/Station/City/${c}`, bearer, STATIC_TTL_MS), tdx(`Bike/Availability/City/${c}`, bearer, LIVE_TTL_MS)]),
    ]);

    const outStations = stations
      .map((s) => ({
        id: String(s.StationID ?? ""),
        name: String(s.StationName?.En ?? s.StationName?.Zh_tw ?? s.StationID ?? ""),
        lat: num(s.StationPosition?.PositionLat),
        lon: num(s.StationPosition?.PositionLon),
      }))
      .filter((s) => s.id && s.lat !== null && s.lon !== null);

    const hops = travel.flatMap((route) =>
      (route.TravelTimes ?? []).map((t: Record<string, unknown>) => ({
        fromId: String(t.FromStationID ?? ""),
        toId: String(t.ToStationID ?? ""),
        runSec: num(t.RunTime) ?? 0,
        stopSec: num(t.StopTime) ?? 0,
      }))
    ).filter((h) => h.fromId && h.toId && h.runSec > 0);

    const docks = [];
    for (let i = 0; i < bikes.length; i += 2) {
      const live = new Map(bikes[i + 1].map((a) => [String(a.StationUID), a]));
      for (const s of bikes[i]) {
        const a = live.get(String(s.StationUID));
        const lat = num(s.StationPosition?.PositionLat);
        const lon = num(s.StationPosition?.PositionLon);
        if (lat === null || lon === null) continue;
        docks.push({
          id: String(s.StationUID),
          lat,
          lon,
          rentable: num(a?.AvailableRentBikes),
          returnable: num(a?.AvailableReturnBikes),
        });
      }
    }
    return json({ source: "tdx", fetchedAt: new Date().toISOString(), stations: outStations, hops, docks });
  } catch (e) {
    return json({ error: "tdx_failed", detail: String(e instanceof Error ? e.message : e) }, 502);
  }
});
