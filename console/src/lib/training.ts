// Training load and VO2max helpers for the Training workspace. Analysis review A5: these now match the Android app's
// load model (domain/EnduranceLoad.kt, TrainingLoadEvaluation.kt) so the two apps show the same fitness numbers.

export interface SessionLike {
  duration_minutes: number | null
  rpe?: number | null
  avg_hr?: number | null
  max_hr?: number | null
}

// Banister TRIMP constants, as on Android (EnduranceLoad.kt).
const TRIMP_COEFFICIENT = 0.64
const TRIMP_EXPONENT = 1.92

/** The person's anchors on one date. */
export interface LoadAnchors {
  restingHr: number | null
  maxHr: number | null
}

/**
 * One session's load: session RPE x minutes when an RPE above 0 was logged, otherwise Banister's TRIMP against the
 * person's maximum and resting heart rate as of the session's date (loadAnchorsByDate), otherwise null (the session is
 * left out, never guessed). The session's own peak is not the maximum: dividing by it made easy sessions score about
 * twice what they should. The old helper scored by activity name with keys (interval, tempo...) that stored types (run,
 * ride) never contain, so every session counted as 100 per hour.
 */
export function sessionLoad(s: SessionLike, anchors: LoadAnchors): number | null {
  const duration = s.duration_minutes
  if (duration == null || !(duration > 0)) return null
  if (s.rpe != null && s.rpe > 0) return s.rpe * duration
  const avg = s.avg_hr
  const { maxHr: max, restingHr: rest } = anchors
  if (avg == null || max == null || rest == null || max <= rest) return null
  const ratio = Math.min((avg - rest) / (max - rest), 1)
  if (ratio <= 0) return null
  return duration * ratio * TRIMP_COEFFICIENT * Math.exp(TRIMP_EXPONENT * ratio)
}

type DatedValue = [string, number]

const addDays = (date: string, days: number): string => {
  const t = Date.parse(`${date}T00:00:00Z`) + days * 86400000
  return new Date(t).toISOString().slice(0, 10)
}

/** 24 calendar months earlier (29 February falls back to the 28th, as java.time does). */
const twoYearsBefore = (date: string): string => {
  const y = Number(date.slice(0, 4)) - 2
  const md = date.slice(5) === '02-29' ? '02-28' : date.slice(5)
  return `${String(y).padStart(4, '0')}-${md}`
}

/** The second-highest plausible (120-230) session maximum of the 24 months up to the date; the highest if only one. */
export function robustMaxHr(maxima: DatedValue[], asOf: string): number | null {
  const from = twoYearsBefore(asOf)
  const values = maxima.filter(([d, v]) => d <= asOf && d >= from && v >= 120 && v <= 230).map(([, v]) => v).sort((x, y) => y - x)
  return values[1] ?? values[0] ?? null
}

/** The median plausible (25-120) resting heart rate of the 30 days up to the latest day with one on or before the date. */
export function restingHrAsOf(daily: DatedValue[], asOf: string): number | null {
  const upTo = daily.filter(([d, v]) => d <= asOf && v >= 25 && v <= 120)
  if (upTo.length === 0) return null
  const last = upTo.reduce((m, [d]) => (d > m ? d : m), upTo[0][0])
  const from = addDays(last, -29)
  const window = upTo.filter(([d]) => d >= from).map(([, v]) => v).sort((x, y) => x - y)
  const m = Math.floor(window.length / 2)
  return window.length % 2 === 1 ? window[m] : (window[m - 1] + window[m]) / 2
}

/**
 * The anchors for any date (Android's TrimpAnchors, the server's loadAnchorsByDate): session maxima for the robust
 * maximum and daily resting heart rates. A date before the first resting reading uses the median of the first 30 days
 * that have one. Each date is worked out once.
 */
export function loadAnchorsByDate(maxima: DatedValue[], resting: DatedValue[]): (date: string) => LoadAnchors {
  const plausible = resting.filter(([, v]) => v >= 25 && v <= 120)
  const first = plausible.length ? plausible.reduce((m, [d]) => (d < m ? d : m), plausible[0][0]) : null
  const firstResting = first === null ? null : restingHrAsOf(plausible.filter(([d]) => d <= addDays(first, 29)), addDays(first, 29))
  const cache = new Map<string, LoadAnchors>()
  return (date) => {
    let a = cache.get(date)
    if (!a) {
      a = { maxHr: robustMaxHr(maxima, date), restingHr: restingHrAsOf(resting, date) ?? firstResting }
      cache.set(date, a)
    }
    return a
  }
}

/**
 * VO2max estimate from one effort (Daniels & Gilbert): the oxygen cost of running at v metres per minute, divided by the
 * share of VO2max that can be held for t minutes. The old helper passed km/h into a formula built for metres per minute
 * (12 km/h came out near -2.4) and skipped the duration term. Meaningful only for hard, steady efforts of roughly 4 to
 * 120 minutes; easy runs underestimate.
 */
export function vo2maxFromPace(distKm: number, durMin: number): number | null {
  if (!distKm || !durMin || distKm <= 0 || durMin < 4 || durMin > 240) return null
  const v = (distKm * 1000) / durMin
  const cost = -4.6 + 0.182258 * v + 0.000104 * v * v
  const fraction = 0.8 + 0.1894393 * Math.exp(-0.012778 * durMin) + 0.2989558 * Math.exp(-0.1932605 * durMin)
  const est = cost / fraction
  return Number.isFinite(est) && est > 0 ? Math.round(est * 10) / 10 : null
}

/**
 * Exponentially weighted average with an N-day span, alpha = 2 / (N + 1): the convention Android's dualEwma uses for
 * CTL (42) and ATL (7). The old helper treated N as a half-life, so a "42-day" CTL here averaged over about 60 days.
 */
export function ema(values: number[], spanDays: number): number[] {
  const alpha = 2 / (spanDays + 1)
  let acc = 0
  return values.map((v) => {
    acc = acc + alpha * (v - acc)
    return Math.round(acc * 10) / 10
  })
}
