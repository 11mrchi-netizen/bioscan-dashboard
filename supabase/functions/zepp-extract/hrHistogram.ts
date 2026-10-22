// A workout's heart rate as seconds per bpm (zepp_workout_detail.hr_histogram): { "148": 312, ... }. Small enough to read
// for weeks of workouts at once, and enough to place them in zones of any method and anchors later, without reloading
// the per-second series. Each sample's time runs to the next sample (as the session chart's time in zone counts it),
// capped at HR_GAP_CAP_SEC so a pause or a lost signal is not counted at the last heart rate before it. Only plausible
// heart rates are kept. Pure functions, no imports, so the same file runs in the edge function and under `node --test`.
// The migration 20261022_hr_histogram.sql backfills stored workouts with the same rules in SQL.

export interface HrSample { offsetSeconds: number; value: number; }

export const HR_GAP_CAP_SEC = 10;
export const HR_MIN_BPM = 30;
export const HR_MAX_BPM = 230;

/** Seconds per whole bpm, or null when no interval has a plausible heart rate. */
export function hrHistogram(points: HrSample[] | null | undefined): Record<string, number> | null {
  if (!Array.isArray(points) || points.length < 2) return null;
  const sorted = points
    .filter((p) => Number.isFinite(p?.offsetSeconds) && Number.isFinite(p?.value))
    .sort((a, b) => a.offsetSeconds - b.offsetSeconds);
  const out: Record<string, number> = {};
  let any = false;
  for (let i = 0; i + 1 < sorted.length; i++) {
    const dt = Math.min(sorted[i + 1].offsetSeconds - sorted[i].offsetSeconds, HR_GAP_CAP_SEC);
    const v = sorted[i].value;
    if (!(dt > 0) || v < HR_MIN_BPM || v > HR_MAX_BPM) continue;
    const bpm = String(Math.floor(v + 0.5));
    out[bpm] = (out[bpm] ?? 0) + dt;
    any = true;
  }
  return any ? out : null;
}
