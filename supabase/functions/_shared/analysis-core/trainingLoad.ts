// The apps' session load, for the analysis frame (analysis review phase G). The same formula as Android
// (data/AnalysisRepository.kt buildTrainingSessionLoads, domain/EnduranceLoad.kt) and the console (src/lib/training.ts):
// session RPE x minutes when an RPE above 0 was logged, otherwise Banister's TRIMP, otherwise nothing (the session is left
// out, never guessed). TRIMP's heart-rate-reserve fraction is (average - resting) / (maximum - resting) with the person's
// maximum and resting heart rate as they were on the session's date (loadAnchorsByDate), not the session's own peak,
// which made easy sessions score about twice what they should. Erasable TypeScript, no I/O.

export interface SessionLoadInput {
  duration_min: number | null
  rpe?: number | null
  avg_hr?: number | null
  max_hr?: number | null
}

/** The person's anchors on one date. */
export interface LoadAnchors {
  restingHr: number | null
  maxHr: number | null
}

const TRIMP_COEFFICIENT = 0.64
const TRIMP_EXPONENT = 1.92

export function sessionLoad(s: SessionLoadInput, anchors: LoadAnchors): number | null {
  const duration = s.duration_min
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
 * The anchors for any date, from rows the caller already has (Android's TrimpAnchors): session maxima for the robust
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
 * Exponentially weighted average with an N-day span (alpha = 2 / (N + 1)), the convention the apps use for fitness (42)
 * and fatigue (7), seeded with the first day that has a load, as Android's dualEwma is. From then on a missing day counts
 * as rest (zero); before it there is no value.
 */
export function spanEma(load: (number | null)[], span: number): (number | null)[] {
  const alpha = 2 / (span + 1)
  const start = load.findIndex((v) => v !== null && v !== undefined)
  if (start < 0) return load.map(() => null)
  let acc = load[start] as number
  return load.map((v, i) => {
    if (i < start) return null
    if (i > start) acc += alpha * ((v ?? 0) - acc)
    return acc
  })
}
