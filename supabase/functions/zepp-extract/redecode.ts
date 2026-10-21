// Re-decoding stored workouts with the timed decoder (decodeSeries.ts). Pure, no imports, so it runs under `node --test`.
// For one workout it compares the old and new decode and says what changes: where heart rate and the GPS track end,
// the pauses, and -- for a paused workout -- its real start. A paused workout's start was stored as end minus moving
// time, late by its pauses; the new start is end minus elapsed time (the last offset any series reaches).

export interface SeriesPoint { offsetSeconds: number; }
export interface DecodedLike {
  heartRate?: SeriesPoint[];
  speedKmh?: SeriesPoint[];
  altitudeM?: SeriesPoint[];
  routePoints?: SeriesPoint[];
  distanceKm?: SeriesPoint[];
  pauses?: { startOffset: number; durationSec: number }[];
}

export interface RedecodeReport {
  hrEndOld: number | null;
  hrEndNew: number | null;
  gpsEndNew: number | null;
  pauses: number;
  pausedSec: number;
  elapsedSec: number | null;
  movingSec: number | null;
  // The corrected start (same "local time, UTC-labeled" ISO form as stored), or null when the start stays.
  newStartIso: string | null;
}

// A start moves only when elapsed and moving time differ by more than this (a real pause, not rounding).
export const START_SHIFT_MIN_SEC = 60;

const lastOffset = (s?: SeriesPoint[]) => (s && s.length ? s[s.length - 1].offsetSeconds : null);

export function redecodeReport(oldDecoded: DecodedLike | null, next: DecodedLike, startIso: string | null, endIso: string | null): RedecodeReport {
  const ends = [next.heartRate, next.speedKmh, next.altitudeM, next.routePoints, next.distanceKm].map(lastOffset).filter((v): v is number => v !== null);
  const elapsedSec = ends.length ? Math.max(...ends) : null;
  const startMs = startIso ? Date.parse(startIso) : NaN;
  const endMs = endIso ? Date.parse(endIso) : NaN;
  const movingSec = Number.isFinite(startMs) && Number.isFinite(endMs) ? Math.round((endMs - startMs) / 1000) : null;
  const pauses = next.pauses ?? [];
  const shift = elapsedSec !== null && movingSec !== null ? elapsedSec - movingSec : 0;
  const newStartIso = pauses.length > 0 && shift > START_SHIFT_MIN_SEC && Number.isFinite(endMs)
    ? new Date(endMs - (elapsedSec as number) * 1000).toISOString()
    : null;
  return {
    hrEndOld: lastOffset(oldDecoded?.heartRate),
    hrEndNew: lastOffset(next.heartRate),
    gpsEndNew: lastOffset(next.routePoints) ?? lastOffset(next.distanceKm),
    pauses: pauses.length,
    pausedSec: pauses.reduce((a, p) => a + p.durationSec, 0),
    elapsedSec,
    movingSec,
    newStartIso,
  };
}

/** Whether a session's stored start is the workout's old (late) start, so moving it is safe: within a minute of it. */
export function sessionStartMatches(sessionStartIso: string | null, detailStartIso: string | null): boolean {
  if (!sessionStartIso || !detailStartIso) return false;
  return Math.abs(Date.parse(sessionStartIso) - Date.parse(detailStartIso)) <= 60_000;
}
