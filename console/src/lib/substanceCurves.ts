// How much of a substance is in the body through a day, estimated from when it was logged (Today's CAFFEINE and
// ALCOHOL curves). Pure functions: no Supabase, no clock. Times are minutes from the local midnight that starts the
// day shown; a dose the evening before is negative.
//
// Assumptions (the page repeats the short form; change them here, the tests pin them):
//
// CAFFEINE: one-compartment model, first-order absorption and elimination, fully absorbed.
//   amount(t) = dose * ka / (ka - ke) * (e^(-ke t) - e^(-ka t))
//   - elimination half-life 5 h (ke = ln 2 / 5 h): the textbook mean for healthy adults, range about 3 to 7 h; it is
//     shorter with smoking and longer with the pill, pregnancy and some liver conditions (Nehlig 2018, Institute of
//     Medicine 2001). It is the largest uncertainty, so the curve is a shape and an order of magnitude, not an assay.
//   - absorption ka = 4 per hour: the peak lands about 52 minutes after the dose, inside the usual 30 to 60 minutes.
//   - the bedtime marker is 50 mg, about half a cup of coffee, a working line rather than a measured one: 400 mg taken
//     6 h before bed cost about an hour of sleep in Drake 2013, and what is still in the body at lights out is what the
//     later studies relate to sleep latency and depth.
//
// ALCOHOL: grams of ethanol absorbed from the stomach (first order, ka = 2 per hour: about 50 minutes to the peak,
//   slower with a meal) and cleared at a constant rate, 7 g an hour (about one standard drink every 80 minutes,
//   0.015 % blood alcohol an hour in a 75 kg man: Jones 2010). The clearance is zero order, so the curve falls as a
//   straight line, not a half-life.

export const CAFFEINE_HALF_LIFE_H = 5
export const CAFFEINE_KA_PER_H = 4
export const CAFFEINE_BEDTIME_MG = 50
export const ALCOHOL_KA_PER_H = 2
export const ALCOHOL_CLEARANCE_G_PER_H = 7

export interface Dose { /** Minutes from the shown day's local midnight. */ at: number; amount: number }
export interface CurvePoint { t: number; v: number }

/** Caffeine (mg) in the body `t` minutes after midnight, from doses at or before `t`. */
export function caffeineAt(doses: Dose[], t: number): number {
  const ke = Math.LN2 / CAFFEINE_HALF_LIFE_H / 60, ka = CAFFEINE_KA_PER_H / 60
  let sum = 0
  for (const d of doses) {
    const dt = t - d.at
    if (dt <= 0 || !(d.amount > 0)) continue
    sum += d.amount * (ka / (ka - ke)) * (Math.exp(-ke * dt) - Math.exp(-ka * dt))
  }
  return sum
}

/** Minutes after a dose at which caffeine in the body peaks. */
export function caffeinePeakMin(): number {
  const ke = Math.LN2 / CAFFEINE_HALF_LIFE_H / 60, ka = CAFFEINE_KA_PER_H / 60
  return Math.log(ka / ke) / (ka - ke)
}

/** The first minute after `from` at which caffeine is at or below `mg` (never before the last dose has peaked), or null within a day. */
export function caffeineBelowAt(doses: Dose[], mg: number, from: number): number | null {
  const settled = Math.max(from, ...doses.map((d) => d.at + (d.amount > 0 ? caffeinePeakMin() : 0)))
  for (let t = settled; t <= settled + 48 * 60; t += 5) if (caffeineAt(doses, t) <= mg) return t
  return null
}

/** Alcohol (g) in the body from `start` to `end` every `step` minutes: absorption from the stomach, constant clearance. */
export function alcoholSeries(doses: Dose[], start: number, end: number, step = 5): CurvePoint[] {
  const ka = ALCOHOL_KA_PER_H / 60, clear = ALCOHOL_CLEARANCE_G_PER_H / 60
  const sorted = doses.filter((d) => d.amount > 0).sort((a, b) => a.at - b.at)
  const first = sorted.length ? Math.min(sorted[0].at, start) : start
  let gut = 0, body = 0, next = 0
  const out: CurvePoint[] = []
  for (let t = first; t <= end + 1e-9; t += 1) {
    while (next < sorted.length && sorted[next].at <= t) gut += sorted[next++].amount
    const absorbed = gut * (1 - Math.exp(-ka))
    gut -= absorbed
    body = Math.max(0, body + absorbed - clear)
    if (t >= start && Math.round(t - start) % step === 0) out.push({ t, v: body })
  }
  return out
}

export function caffeineSeries(doses: Dose[], start: number, end: number, step = 5): CurvePoint[] {
  const out: CurvePoint[] = []
  for (let t = start; t <= end + 1e-9; t += step) out.push({ t, v: caffeineAt(doses, t) })
  return out
}

/** "HH:MM" wall clock of a logged time ('2026-11-24 10:36:22+00': its digits are local) as minutes from that day's midnight, plus the day. */
export function wallParts(iso: string): { day: string; min: number } | null {
  const m = iso.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})/)
  return m ? { day: m[1], min: Number(m[2]) * 60 + Number(m[3]) } : null
}

/** Minutes from the local midnight of `day` for a logged wall-clock time on `day`, the day before (negative) or after. */
export function minutesFromDay(iso: string, day: string, dayDiff: (a: string, b: string) => number): number | null {
  const p = wallParts(iso)
  return p ? dayDiff(p.day, day) * 1440 + p.min : null
}

/**
 * The usual bedtime as minutes after midnight (24:00 to 30:00 for a bedtime after midnight), the median of recent
 * nights. `bedtimes` are real instants with the zone of the night (sleep_daily.bedtime, tz_offset_min).
 */
export function usualBedtimeMin(nights: { bedtime: string | null; tz_offset_min: number | null }[]): number | null {
  const mins: number[] = []
  for (const n of nights) {
    if (!n.bedtime) continue
    const ms = Date.parse(n.bedtime)
    if (Number.isNaN(ms)) continue
    const local = new Date(ms + (n.tz_offset_min ?? 0) * 60_000)
    let m = local.getUTCHours() * 60 + local.getUTCMinutes()
    if (m < 12 * 60) m += 1440 // 00:30 belongs to the evening before
    mins.push(m)
  }
  if (mins.length < 3) return null
  mins.sort((a, b) => a - b)
  const mid = mins.length >> 1
  return Math.round(mins.length % 2 ? mins[mid] : (mins[mid - 1] + mins[mid]) / 2)
}

export const clockLabel = (min: number): string => {
  const m = ((Math.round(min) % 1440) + 1440) % 1440
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
}
