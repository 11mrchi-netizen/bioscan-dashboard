// Zepp detail.json's per-second series, decoded against their own timing. Pure functions, no imports, so the same file
// runs in the edge function and under `node --test`.
//
// Every series carries its own time step (checked on stored raw workouts, Oct 2026):
// - heart_rate: "Δt,Δbpm" -- the first entry's bpm is absolute, the rest are deltas; an empty Δt means 1 s.
// - speed: "Δt,m/s".
// - gait: "Δt,<state>,<vertical ratio x10>,<cadence>".
// - longitude_latitude and altitude: one entry per GPS point, timed by the separate `time` field (one Δt per point).
// - pause: "<unix start>,<seconds>,…" per pause, `;`-separated; `trackid` is the workout's unix start.
// The steps are mostly 1 s, with gaps of 2-3 s and one long step across each pause; on a paused 5 h run every series'
// steps add up to the same elapsed time. The first decoder ignored the Δt and took entry n as second n, so a series with
// gaps (heart rate) ran ahead of one without, by up to the length of a pause. Offsets here are seconds from the
// workout's start, the same axis for every series.

export interface DecodedPoint { offsetSeconds: number; value: number; }
export interface RoutePoint { offsetSeconds: number; lat: number; lon: number; }
export interface Pause { startOffset: number; durationSec: number; }

export function splitEntries(raw: unknown): string[] {
  if (typeof raw !== "string" || raw.length === 0) return [];
  return raw.replace(/;$/, "").split(";").filter((e) => e.length > 0);
}

// A leading Δt: empty means 1 s; the first entry of a series sits at its own Δt from the start (usually 0).
function stepOf(field: string | undefined, index: number): number {
  if (field === undefined) return index === 0 ? 0 : 1;
  if (field === "") return 1;
  const n = Number(field);
  return Number.isFinite(n) && n >= 0 ? n : 1;
}

export function decodeHeartRate(raw: unknown): DecodedPoint[] {
  const out: DecodedPoint[] = [];
  let running = 0;
  let t = 0;
  splitEntries(raw).forEach((entry, i) => {
    const parts = entry.split(",");
    const hasStep = parts.length > 1;
    t += stepOf(hasStep ? parts[0] : undefined, i);
    const v = Number(hasStep ? parts[1] : parts[0]);
    if (!Number.isFinite(v)) return;
    running = i === 0 ? v : running + v;
    out.push({ offsetSeconds: t, value: running });
  });
  return out;
}

export function decodeSpeedKmh(raw: unknown): DecodedPoint[] {
  const out: DecodedPoint[] = [];
  let t = 0;
  splitEntries(raw).forEach((entry, i) => {
    const parts = entry.split(",");
    const hasStep = parts.length > 1;
    t += stepOf(hasStep ? parts[0] : undefined, i);
    const ms = Number(hasStep ? parts[1] : parts[0]);
    if (!Number.isFinite(ms)) return;
    out.push({ offsetSeconds: t, value: ms * 3.6 });
  });
  return out;
}

// gait's second field is a run-state flag; the third is the vertical ratio x10, the fourth cadence (spm).
export function decodeCadenceAndVerticalRatio(raw: unknown): { cadenceSpm: DecodedPoint[]; verticalStrideRatioPct: DecodedPoint[] } {
  const cadenceSpm: DecodedPoint[] = [];
  const verticalStrideRatioPct: DecodedPoint[] = [];
  let t = 0;
  splitEntries(raw).forEach((entry, i) => {
    const parts = entry.split(",");
    t += stepOf(parts.length >= 4 ? parts[0] : undefined, i);
    const vertRatio = Number(parts[2]);
    const cadence = Number(parts[3]);
    if (Number.isFinite(cadence) && cadence > 0) cadenceSpm.push({ offsetSeconds: t, value: cadence });
    if (Number.isFinite(vertRatio) && cadence > 0) verticalStrideRatioPct.push({ offsetSeconds: t, value: vertRatio / 10 });
  });
  return { cadenceSpm, verticalStrideRatioPct };
}

/** The offset of each GPS point from the `time` field; past its end (or without it) one second per point. */
export function gpsOffsets(timeRaw: unknown, count: number): number[] {
  const steps = splitEntries(timeRaw).map((e) => Number(e));
  const out: number[] = [];
  let t = 0;
  for (let i = 0; i < count; i++) {
    const s = steps[i];
    t += i < steps.length && Number.isFinite(s) && s >= 0 ? s : (i === 0 ? 0 : 1);
    out.push(t);
  }
  return out;
}

// altitude: centimetres, one entry per GPS point; about -2,000,000 when there is no fix (dropped).
const ALTITUDE_INVALID_BELOW_CM = -1_000_000;
export function decodeAltitude(raw: unknown, offsets: number[]): DecodedPoint[] {
  const out: DecodedPoint[] = [];
  splitEntries(raw).forEach((entry, i) => {
    const v = Number(entry);
    if (!Number.isFinite(v) || v < ALTITUDE_INVALID_BELOW_CM) return;
    out.push({ offsetSeconds: offsets[i] ?? (offsets.length ? offsets[offsets.length - 1] + i - offsets.length + 1 : i), value: v / 100 });
  });
  return out;
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.asin(Math.sqrt(a));
}

// longitude_latitude: the first pair absolute (degrees x1e8), every later pair a delta to add.
export function decodeTrack(raw: unknown, offsets: number[]): { routePoints: RoutePoint[]; distanceKm: DecodedPoint[] } {
  const routePoints: RoutePoint[] = [];
  const distanceKm: DecodedPoint[] = [];
  let lat = 0, lon = 0, cumMeters = 0, started = false;
  splitEntries(raw).forEach((entry, i) => {
    const parts = entry.split(",");
    const a = Number(parts[0]), b = Number(parts[1]);
    if (!Number.isFinite(a) || !Number.isFinite(b)) return;
    if (!started) {
      lat = a / 1e8;
      lon = b / 1e8;
      started = true;
    } else {
      const newLat = lat + a / 1e8, newLon = lon + b / 1e8;
      cumMeters += haversineMeters(lat, lon, newLat, newLon);
      lat = newLat;
      lon = newLon;
    }
    const t = offsets[i] ?? i;
    routePoints.push({ offsetSeconds: t, lat, lon });
    distanceKm.push({ offsetSeconds: t, value: cumMeters / 1000 });
  });
  return { routePoints, distanceKm };
}

/** The workout's pauses as offsets from its start (trackid, unix seconds); a pause without a usable start is dropped. */
export function decodePauses(raw: unknown, trackStartSec: number | null): Pause[] {
  if (trackStartSec === null) return [];
  return splitEntries(raw).flatMap((entry) => {
    const [start, dur] = entry.split(",").map(Number);
    if (!Number.isFinite(start) || !Number.isFinite(dur) || dur <= 0) return [];
    const startOffset = start - trackStartSec;
    return startOffset >= 0 ? [{ startOffset, durationSec: dur }] : [];
  });
}

/**
 * The workout's real start, unix seconds. The list entry's run_time is moving time, so end - run_time starts a paused
 * workout late by its pauses; the trackid is the start itself. It is used when it is a plausible start (no later than
 * end - run_time, and within a day of the end), else end - run_time as before.
 */
export function workoutStartSec(trackId: unknown, endSec: number, runSec: number): number {
  const fallback = endSec - runSec;
  const t = Number(trackId);
  if (!Number.isFinite(t)) return fallback;
  return t <= fallback + 5 && endSec - t <= 86_400 && t > 0 ? t : fallback;
}
