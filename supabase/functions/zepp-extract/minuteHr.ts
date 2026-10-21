// The band's minute heart rate (band_data `data_hr`): base64, one byte per minute from the day's local midnight. The byte
// is the minute's heart rate in bpm, except for the band's "no reading" markers: 0 (no measurement), 254 and 255 (band
// off the wrist or the sensor gave up). Those used to be kept whenever they were above 0, so every minute the band was
// not worn was charted at 254 or 255 bpm. A minute is kept only inside the range a human heart rate can take
// (MIN_BPM..MAX_BPM); anything else is no reading, and the chart shows a gap there. Pure functions, no imports, so the
// same file runs in the edge function and under `node --test`.

export interface HrMinutePoint { offsetMinutes: number; bpm: number; }

/** Lowest and highest minute heart rate kept, in bpm. Outside this the byte is a marker or a sensor error. */
export const MIN_BPM = 30;
export const MAX_BPM = 220;

export function plausibleMinuteBpm(bpm: number): boolean {
  return Number.isFinite(bpm) && bpm >= MIN_BPM && bpm <= MAX_BPM;
}

/** The minutes with a heart rate, or null when the series is missing, not base64, or has no plausible minute. */
export function decodeMinuteHr(b64: unknown): HrMinutePoint[] | null {
  if (typeof b64 !== "string" || !b64) return null;
  let bytes: string;
  try {
    bytes = atob(b64);
  } catch {
    return null;
  }
  const pts: HrMinutePoint[] = [];
  for (let i = 0; i < bytes.length; i++) {
    const bpm = bytes.charCodeAt(i);
    if (plausibleMinuteBpm(bpm)) pts.push({ offsetMinutes: i, bpm });
  }
  return pts.length > 0 ? pts : null;
}
