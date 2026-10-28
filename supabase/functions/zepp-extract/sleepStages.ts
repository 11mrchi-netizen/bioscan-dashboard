// The night's timed sleep stages from the band_data summary (slp.stage): [{start, stop, mode}], start and stop in minutes from
// the day's local midnight, both inclusive (a stage 1534..1591 lasts 58 minutes). Stored compact as
// [offsetMin, durationMin, mode] with offsets from the first stage (the sleep onset), so the stored form has no timezone in
// it. Modes: 4 light, 5 deep, 7 awake, 8 REM. Pure, no imports, so it runs in the edge function and under `node --test`.

export type StageInterval = [number, number, number];

const MODES = new Set([4, 5, 7, 8]);

export function compactSleepStages(stage: unknown): StageInterval[] | null {
  if (!Array.isArray(stage)) return null;
  const valid = stage
    .map((s) => s as { start?: unknown; stop?: unknown; mode?: unknown })
    .filter((s) => Number.isInteger(s.start) && Number.isInteger(s.stop) && Number.isInteger(s.mode) && MODES.has(s.mode as number) && (s.stop as number) >= (s.start as number))
    .map((s) => ({ start: s.start as number, stop: s.stop as number, mode: s.mode as number }))
    .sort((a, b) => a.start - b.start);
  if (valid.length === 0) return null;
  const first = valid[0].start;
  return valid.map((s) => [s.start - first, s.stop - s.start + 1, s.mode]);
}

/** The band's UTC offset in minutes (summary.tz is seconds, as a string); null when absent or not a number. */
export function tzOffsetMinutes(tz: unknown): number | null {
  const n = Number(tz);
  return tz !== null && tz !== "" && Number.isFinite(n) ? Math.round(n / 60) : null;
}
