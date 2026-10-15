// Analysis review phase D (P4 to P6): three ways to look at what goes with what, each answering a different question.
// Erasable TypeScript, no I/O. All of them work on what the expected-band model (expectedBand.ts) did not expect, or
// adjust for weekday and trend themselves, so a weekly routine or a slow drift does not read as an effect.
//
//   Event curves      "What happens in the days after X?" Each occurrence (the first evening of a run) is lined up with
//                     the days around it; each day's distance from what the model expected (forecast from four days
//                     before, so the event cannot move its own baseline) is averaged and compared with the same days
//                     around evenings on the same weekday without it (the expected level already carries the average
//                     effect of a frequent exposure, so "distance from expected" alone would understate it). The grey
//                     chance band shuffles which days count as events within each weekday; it is simultaneous over the
//                     days shown (a max-statistic envelope), so with no effect the whole curve stays inside it 95% of the
//                     time instead of one day in five poking out somewhere. The days before are a built-in check.
//   Case-crossover    "What tends to come before a low morning?" Each morning outside the 80% band on the unfavourable
//                     side is compared with reference days on the same weekday in the same calendar month
//                     (time-stratified; Maclure 1991, Janes et al. 2005). Mantel-Haenszel odds ratio with the
//                     Robins-Breslow-Greenland variance. Exploratory: a row is a hint of what to test next.
//   Lag curves        "How long does it last, and does the amount matter?" A distributed-lag regression over seven days,
//                     weekday and trend adjusted, Newey-West errors. The lags are left free: a smoothness constraint
//                     (Almon polynomials of degree 2 to 4) was tried and spread a one-day effect over the week (37% of
//                     intervals covered a planted lag-1 effect at degree 2) while narrowing the intervals only slightly
//                     at a year of data. For alcohol, a dose curve: next-morning distance from expected by drinks.
// Words follow the language contract: "followed", "came before", "went with"; never "lowers" or "causes".
import { BAND_SPECS, fitWeights, q80, runFilter } from './expectedBand.ts'
import type { BandSpec } from './expectedBand.ts'
import { fnv1a } from './hash.ts'
import { coefficient, olsHac, trendColumns, weekdayColumns } from './regression.ts'
import type { Frame } from './relationship.ts'
import { mean, mulberry32, sd, tCritical, weekdayOf } from './stats.ts'
import type { Num } from './stats.ts'

const round = (v: number, d = 2) => (Number.isFinite(v) ? Math.round(v * 10 ** d) / 10 ** d : NaN)

// ---- what the expected-band model did not expect ----

export interface BandModel {
  spec: BandSpec
  /** Model-space values (log for HRV). */
  values: Num[]
  excluded: boolean[]
  states: ({ level: number; season: number[] } | null)[]
  /** One-step z and its df per day (null without a band). */
  z: (number | null)[]
  df: number[]
  restarts: number[]
}

export function bandModel(frame: Frame, metric: string, restartDates: string[] = []): BandModel | null {
  const spec = BAND_SPECS.find((s) => s.metric === metric)
  if (!spec) return null
  const ln = spec.transform === 'ln'
  const raw = frame.series[metric] ?? frame.dates.map(() => null)
  const values: Num[] = raw.map((v) => (v === null || !Number.isFinite(v) ? null : ln ? (v > 0 ? Math.log(v) : null) : v))
  const excluded = frame.excluded.map((x) => x === true)
  const restarts = restartDates.map((d) => frame.dates.findIndex((x) => x >= d)).filter((i) => i > 0)
  const w = fitWeights(frame.dates, values, excluded, restarts)
  const run = runFilter(frame.dates, values, excluded, restarts, w.alpha, w.gamma, true)
  return {
    spec, values, excluded, restarts,
    states: run.states ?? [],
    z: run.steps.map((st) => (st.scored ? st.z : null)),
    df: run.steps.map((st) => st.df),
  }
}

/** Distance of day t from the forecast made from the state before day `origin`, in display units (%, bpm, minutes). */
function deviation(m: BandModel, dates: string[], t: number, origin: number): number | null {
  const y = m.values[t]
  const st = m.states[origin]
  if (y === null || m.excluded[t] || !st) return null
  const f = st.level + st.season[weekdayOf(dates[t])]
  if (m.spec.transform === 'ln') return (Math.exp(y - f) - 1) * 100
  return m.spec.metric === 'sleep_hours' ? (y - f) * 60 : y - f
}

const displayUnit = (m: BandModel) => (m.spec.transform === 'ln' ? '%' : m.spec.metric === 'sleep_hours' ? 'min' : m.spec.unit)

// ---- event curves ----

export interface EventCurveSpec {
  id: string
  exposure: string
  outcome: 'hrv' | 'rhr' | 'sleep_hours'
  label: string
  /** "evenings with alcohol" */
  eventText: string
  /** "HRV" */
  outcomeText: string
}

export const EVENT_CURVES: EventCurveSpec[] = [
  { id: 'alcohol_hrv', exposure: 'alcohol_any', outcome: 'hrv', label: 'HRV around evenings with alcohol', eventText: 'evenings with alcohol', outcomeText: 'HRV' },
  { id: 'alcohol_rhr', exposure: 'alcohol_any', outcome: 'rhr', label: 'Resting heart rate around evenings with alcohol', eventText: 'evenings with alcohol', outcomeText: 'resting heart rate' },
  { id: 'alcohol_sleep', exposure: 'alcohol_any', outcome: 'sleep_hours', label: 'Sleep around evenings with alcohol', eventText: 'evenings with alcohol', outcomeText: 'sleep' },
  { id: 'late_meal_sleep', exposure: 'late_meal', outcome: 'sleep_hours', label: 'Sleep around meals after 21:00', eventText: 'meals after 21:00', outcomeText: 'sleep' },
  { id: 'late_meal_hrv', exposure: 'late_meal', outcome: 'hrv', label: 'HRV around meals after 21:00', eventText: 'meals after 21:00', outcomeText: 'HRV' },
  { id: 'caffeine_pm_sleep', exposure: 'caffeine_pm_any', outcome: 'sleep_hours', label: 'Sleep around afternoon caffeine', eventText: 'days with caffeine after 14:00', outcomeText: 'sleep' },
  { id: 'stress_sleep', exposure: 'stress_high', outcome: 'sleep_hours', label: 'Sleep around high-stress days', eventText: 'high-stress days', outcomeText: 'sleep' },
  { id: 'evening_training_sleep', exposure: 'evening_training', outcome: 'sleep_hours', label: 'Sleep around training after 19:00', eventText: 'training after 19:00', outcomeText: 'sleep' },
  { id: 'training_hrv', exposure: 'training_day', outcome: 'hrv', label: 'HRV around training days', eventText: 'training days', outcomeText: 'HRV' },
  { id: 'recovery_hrv', exposure: 'recovery_any', outcome: 'hrv', label: 'HRV around recovery sessions', eventText: 'recovery sessions', outcomeText: 'HRV' },
]

export const EVENT_WINDOW = { before: 3, after: 5, minEvents: 8, pseudoSets: 400 }

export interface EventCurveDay {
  k: number
  /** Average distance from expected after the event, minus the same after comparable days without it (display units). */
  mean: number | null
  /** 95% interval of that average. */
  lo: number | null
  hi: number | null
  /** 95% chance band from pseudo-events, simultaneous over the days shown. */
  chanceLo: number | null
  chanceHi: number | null
  n: number
}

export interface EventCurveResult {
  id: string
  label: string
  exposure: string
  outcome: string
  unit: string
  state: 'ready' | 'not_yet'
  events: number
  need: number
  days: EventCurveDay[]
  /** The day after the event with the largest distance outside the chance band, if any. */
  peak: { k: number; value: number } | null
  /** First day after the peak that is back inside the chance band (null: not within the window, or no peak). */
  backByDay: number | null
  /** A day before the event already sat outside the chance band: something else may drive both. */
  differentBefore: boolean
  summary: string
}

function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return NaN
  const pos = p * (sorted.length - 1)
  const i = Math.floor(pos)
  return i + 1 < sorted.length ? sorted[i] + (pos - i) * (sorted[i + 1] - sorted[i]) : sorted[i]
}

export function analyseEventCurve(spec: EventCurveSpec, frame: Frame, m: BandModel): EventCurveResult {
  const { before, after, minEvents, pseudoSets } = EVENT_WINDOW
  const x = frame.series[spec.exposure] ?? frame.dates.map(() => null)
  const n = frame.dates.length
  const unit = displayUnit(m)
  const crossesRegime = (e: number) => m.restarts.some((r) => r > e - before - 1 && r <= e + after)
  const usable = (e: number) => e - before - 1 >= 0 && m.states[e - before] !== null && !crossesRegime(e) && e + 1 < n
  // The first evening of each run of exposure days; the comparison days are the other days whose evening (and the one
  // before) had a known none.
  const events: number[] = []
  const others: number[] = []
  for (let e = 0; e < n; e++) {
    if (!usable(e)) continue
    if (x[e] === 1 && x[e - 1] !== 1) events.push(e)
    else if (x[e] === 0 && x[e - 1] === 0) others.push(e)
  }
  const ks = Array.from({ length: before + after + 1 }, (_, i) => i - before)
  const base = { id: spec.id, label: spec.label, exposure: spec.exposure, outcome: spec.outcome, unit, events: events.length, need: minEvents }
  if (events.length < minEvents || others.length < minEvents) {
    return { ...base, state: 'not_yet', days: [], peak: null, backByDay: null, differentBefore: false, summary: `Not yet: ${events.length} of ${minEvents} ${spec.eventText} needed to draw this curve.` }
  }
  // Distance from expected for every origin day and offset, forecast from the state before the window opened.
  const dev = new Map<number, (number | null)[]>()
  for (const d of [...events, ...others]) dev.set(d, ks.map((k) => (d + k < n ? deviation(m, frame.dates, d + k, d - before) : null)))
  // Difference between event days and comparison days on the same weekday, averaged over weekdays with each weekday
  // weighted by its events: the forecast's weekly pattern already expects whatever usually happens on that weekday.
  const weekday = new Map<number, number>()
  for (const d of [...events, ...others]) weekday.set(d, weekdayOf(frame.dates[d]))
  const curve = (group: Set<number>, all: number[]): { diff: number | null; n: number }[] =>
    ks.map((_, i) => {
      const a = new Map<number, number[]>()
      const b = new Map<number, number[]>()
      for (const d of all) {
        const v = (dev.get(d) as (number | null)[])[i]
        if (v === null) continue
        const into = group.has(d) ? a : b
        const w = weekday.get(d) as number
        const list = into.get(w)
        if (list) list.push(v)
        else into.set(w, [v])
      }
      let num = 0
      let den = 0
      let n = 0
      for (const [w, vals] of a) {
        const ref = b.get(w)
        if (!ref || ref.length === 0) continue
        num += vals.length * (mean(vals) - mean(ref))
        den += vals.length
        n += vals.length
      }
      return { diff: den > 0 ? num / den : null, n }
    })
  const pool = [...events, ...others]
  const observed = curve(new Set(events), pool)

  // Permutation within weekdays: under no effect, event days are exchangeable with comparison days on the same weekday.
  const strata = new Map<number, number[]>()
  for (const d of pool) {
    const w = weekdayOf(frame.dates[d])
    const list = strata.get(w)
    if (list) list.push(d)
    else strata.set(w, [d])
  }
  const eventsPerStratum = new Map<number, number>()
  for (const e of events) eventsPerStratum.set(weekdayOf(frame.dates[e]), (eventsPerStratum.get(weekdayOf(frame.dates[e])) ?? 0) + 1)
  const rng = mulberry32(parseInt(fnv1a(`event:${spec.id}:${events.join(',')}`), 16))
  const diffs: number[][] = ks.map(() => [])
  for (let s = 0; s < pseudoSets; s++) {
    const pseudo = new Set<number>()
    for (const [w, days] of strata) {
      const take = eventsPerStratum.get(w) ?? 0
      const shuffled = [...days]
      for (let i = shuffled.length - 1; i > 0; i--) {
        const j = Math.floor(rng() * (i + 1))
        ;[shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]]
      }
      shuffled.slice(0, take).forEach((d) => pseudo.add(d))
    }
    curve(pseudo, pool).forEach(({ diff }, i) => {
      if (diff !== null) diffs[i].push(diff)
    })
  }
  // Simultaneous envelope: centre and spread of the permuted differences per day, then the 95th percentile over
  // permutations of the largest standardised distance across all days shown.
  const centre = diffs.map((d) => (d.length > 0 ? mean(d) : NaN))
  const spread = diffs.map((d) => (d.length > 1 ? sd(d) : NaN))
  const runs = Math.min(...diffs.map((d) => d.length))
  const maxima: number[] = []
  for (let r = 0; r < runs; r++) {
    maxima.push(Math.max(...diffs.map((d, i) => (spread[i] > 0 ? Math.abs(d[r] - centre[i]) / spread[i] : 0))))
  }
  maxima.sort((a, b) => a - b)
  const crit = runs >= 50 ? percentile(maxima, 0.95) : NaN

  const days: EventCurveDay[] = ks.map((k, i) => {
    const { diff, n: count } = observed[i]
    // The day's own 95% interval uses the permutation spread as its standard error (the spread of the difference when
    // event days are reassigned at random within weekdays).
    const band = Number.isFinite(crit) && spread[i] > 0
    const half = spread[i] > 0 ? 1.96 * spread[i] : null
    return {
      k,
      mean: diff === null ? null : round(diff),
      lo: diff === null || half === null ? null : round(diff - half),
      hi: diff === null || half === null ? null : round(diff + half),
      chanceLo: band ? round(centre[i] - crit * spread[i]) : null,
      chanceHi: band ? round(centre[i] + crit * spread[i]) : null,
      n: count,
    }
  })
  const outside = (d: EventCurveDay) => d.mean !== null && d.chanceLo !== null && d.chanceHi !== null && (d.mean < d.chanceLo || d.mean > d.chanceHi)
  const afterDays = days.filter((d) => d.k >= 1 && outside(d))
  const peakDay = afterDays.length > 0 ? afterDays.reduce((p, q) => (Math.abs(q.mean as number) > Math.abs(p.mean as number) ? q : p)) : null
  const backByDay = peakDay ? (days.find((d) => d.k > peakDay.k && d.mean !== null && !outside(d))?.k ?? null) : null
  const differentBefore = days.some((d) => d.k < 0 && outside(d))

  const fmt = (v: number) => (unit === '%' ? `${Math.abs(v).toFixed(0)}%` : unit === 'min' ? `${Math.abs(v).toFixed(0)} min` : `${Math.abs(v).toFixed(1)} ${unit}`)
  let summary: string
  if (!peakDay) {
    summary = `After ${spec.eventText}, ${spec.outcomeText} stayed within the chance band on every day shown (${events.length} events).`
  } else {
    const low = (peakDay.mean as number) < 0
    const dir = spec.outcome === 'sleep_hours' ? (low ? 'shorter' : 'longer') : low ? 'lower' : 'higher'
    const when = peakDay.k === 1 ? 'the next morning' : `on day ${peakDay.k}`
    const back = backByDay !== null ? `, back within the chance band by day ${backByDay}` : ', still outside the chance band at the end of the window'
    summary = `After ${spec.eventText}, ${spec.outcomeText} ran ${fmt(peakDay.mean as number)} ${dir} than after comparable days without ${when}${back} (${events.length} events).`
  }
  if (differentBefore) summary += ' It already differed before the event, so something else may drive both.'
  return { ...base, state: 'ready', days, peak: peakDay ? { k: peakDay.k, value: peakDay.mean as number } : null, backByDay, differentBefore, summary }
}

// ---- case-crossover ----

export interface CaseCrossoverExposure {
  exposure: string
  label: string
}

export const CROSSOVER_EXPOSURES: CaseCrossoverExposure[] = [
  { exposure: 'alcohol_any', label: 'Alcohol' },
  { exposure: 'caffeine_pm_any', label: 'Caffeine after 14:00' },
  { exposure: 'late_meal', label: 'A meal after 21:00' },
  { exposure: 'stress_high', label: 'A high-stress day' },
  { exposure: 'evening_training', label: 'Training after 19:00' },
  { exposure: 'training_day', label: 'A training day' },
]

export const CROSSOVER_OUTCOMES: { outcome: 'hrv' | 'rhr' | 'sleep_hours'; side: 'low' | 'high'; text: string }[] = [
  { outcome: 'hrv', side: 'low', text: 'Mornings with HRV below its expected range' },
  { outcome: 'rhr', side: 'high', text: 'Mornings with resting heart rate above its expected range' },
  { outcome: 'sleep_hours', side: 'low', text: 'Nights shorter than their expected range' },
]

export const MIN_CROSSOVER_CASES = 15

export interface CaseCrossoverRow {
  exposure: string
  label: string
  /** Case days whose evening before has a known exposure and at least one usable reference day. */
  cases: number
  need: number
  or: number | null
  lo: number | null
  hi: number | null
  verdict: 'more_often' | 'less_often' | 'no_clear_difference' | 'not_yet'
}

export interface CaseCrossoverResult {
  outcome: string
  side: 'low' | 'high'
  text: string
  /** Mornings outside the 80% band on the unfavourable side (in the frame). */
  caseDays: number
  rows: CaseCrossoverRow[]
  exploratory: true
}

/** One matched set: was the exposure there before the case day, and before each reference day (1 / 0). */
export interface MatchedSet {
  caseExposed: 0 | 1
  refs: number[]
}

/** Mantel-Haenszel odds ratio over matched sets with the Robins-Breslow-Greenland 95% interval; null when not estimable. */
export function mantelHaenszel(sets: MatchedSet[]): { or: number; lo: number; hi: number } | null {
  let sumR = 0
  let sumS = 0
  let sumPR = 0
  let sumPSQR = 0
  let sumQS = 0
  for (const set of sets) {
    const k = set.refs.length
    if (k === 0) continue
    const N = k + 1
    // 2 x 2 table per set: A exposed case, B exposed references, C unexposed case, D unexposed references.
    const A = set.caseExposed
    const B = set.refs.filter((v) => v === 1).length
    const C = 1 - A
    const D = k - B
    const R = (A * D) / N
    const S = (B * C) / N
    const P = (A + D) / N
    const Q = (B + C) / N
    sumR += R
    sumS += S
    sumPR += P * R
    sumPSQR += P * S + Q * R
    sumQS += Q * S
  }
  if (sumR === 0 || sumS === 0) return null
  const or = sumR / sumS
  const v = sumPR / (2 * sumR ** 2) + sumPSQR / (2 * sumR * sumS) + sumQS / (2 * sumS ** 2)
  return { or, lo: Math.exp(Math.log(or) - 1.96 * Math.sqrt(v)), hi: Math.exp(Math.log(or) + 1.96 * Math.sqrt(v)) }
}

export function analyseCaseCrossover(frame: Frame, m: BandModel, side: 'low' | 'high', text: string): CaseCrossoverResult {
  const n = frame.dates.length
  const caseDays: number[] = []
  for (let t = 1; t < n; t++) {
    const z = m.z[t]
    if (z === null) continue
    const q = q80(m.df[t])
    if ((side === 'low' && z < -q) || (side === 'high' && z > q)) caseDays.push(t)
  }
  const monthOf = (d: string) => d.slice(0, 7)
  const rows = CROSSOVER_EXPOSURES.map((ex): CaseCrossoverRow => {
    const x = frame.series[ex.exposure] ?? frame.dates.map(() => null)
    const sets: MatchedSet[] = []
    for (const t of caseDays) {
      const a = x[t - 1]
      if (a !== 0 && a !== 1) continue
      // Reference days: same weekday, same calendar month, not the case day; exposure the evening before each.
      const refs: number[] = []
      for (let r = 1; r < n; r++) {
        if (r === t || weekdayOf(frame.dates[r]) !== weekdayOf(frame.dates[t]) || monthOf(frame.dates[r]) !== monthOf(frame.dates[t])) continue
        const xr = x[r - 1]
        if (xr === 0 || xr === 1) refs.push(xr)
      }
      if (refs.length > 0) sets.push({ caseExposed: a, refs })
    }
    const base = { exposure: ex.exposure, label: ex.label, cases: sets.length, need: MIN_CROSSOVER_CASES }
    const mh = sets.length >= MIN_CROSSOVER_CASES ? mantelHaenszel(sets) : null
    if (!mh) return { ...base, or: null, lo: null, hi: null, verdict: 'not_yet' }
    const verdict = mh.lo > 1 ? 'more_often' : mh.hi < 1 ? 'less_often' : 'no_clear_difference'
    return { ...base, or: round(mh.or), lo: round(mh.lo), hi: round(mh.hi), verdict }
  })
  return { outcome: m.spec.metric, side, text, caseDays: caseDays.length, rows, exploratory: true }
}

// ---- lag curves ----

export interface LagCurveSpec {
  id: string
  exposure: string
  outcome: 'hrv' | 'rhr' | 'sleep_hours'
  label: string
  /** The dose the effect is stated per, in exposure units (60 = per hour of training). */
  per: number
  perText: string
}

export const LAG_CURVES: LagCurveSpec[] = [
  { id: 'training_hrv', exposure: 'training_min', outcome: 'hrv', label: 'HRV in the week after training', per: 60, perText: 'per hour of training' },
  { id: 'steps_hrv', exposure: 'steps', outcome: 'hrv', label: 'HRV in the week after active days', per: 5000, perText: 'per 5,000 steps' },
  { id: 'alcohol_hrv', exposure: 'alcohol_drinks', outcome: 'hrv', label: 'HRV in the week after drinking', per: 1, perText: 'per drink' },
]

export const LAG = { maxLag: 6, minRows: 120, minExposed: 20 }

export interface LagCurveResult {
  id: string
  label: string
  exposure: string
  outcome: string
  unit: string
  perText: string
  state: 'ready' | 'not_yet'
  rows: number
  exposedDays: number
  need: { rows: number; exposedDays: number }
  /** Effect on the outcome `lag` days after the exposure day, per dose. Lag 0 is the morning of the exposure day. */
  lags: { lag: number; value: number; lo: number; hi: number }[]
  cumulative: { value: number; lo: number; hi: number } | null
  /** Lags 1 to 6 whose interval excludes zero. */
  clearLags: number[]
  /** End of the first unbroken run of clear lags after the exposure day: how long the effect lasts (null: none). A lone
   *  clear lag later in the week is reported in clearLags but not read as the effect lasting that long. */
  lastClearLag: number | null
  /** Alcohol only: next-morning distance from expected by number of drinks. */
  dose: { group: string; n: number; mean: number; lo: number; hi: number }[] | null
  summary: string
}

export function analyseLagCurve(spec: LagCurveSpec, frame: Frame, m: BandModel): LagCurveResult {
  const { maxLag, minRows, minExposed } = LAG
  const x = frame.series[spec.exposure] ?? frame.dates.map(() => null)
  const unit = displayUnit(m)
  const n = frame.dates.length
  const rowsRaw: { t: number; z: number[]; y: number }[] = []
  for (let t = maxLag; t < n; t++) {
    const y = m.values[t]
    if (y === null || m.excluded[t]) continue
    const lagged: number[] = []
    for (let l = 0; l <= maxLag; l++) {
      const v = x[t - l]
      if (v === null || m.excluded[t - l]) break
      lagged.push(v / spec.per)
    }
    if (lagged.length !== maxLag + 1) continue
    rowsRaw.push({ t, z: lagged, y })
  }
  const exposedDays = new Set(rowsRaw.map((r) => r.t).filter((t) => (x[t] ?? 0) > 0)).size
  const base = {
    id: spec.id, label: spec.label, exposure: spec.exposure, outcome: spec.outcome, unit, perText: spec.perText,
    rows: rowsRaw.length, exposedDays, need: { rows: minRows, exposedDays: minExposed },
  }
  const dose = spec.exposure === 'alcohol_drinks' ? doseCurve(frame, m) : null
  const notYet = (why: string): LagCurveResult => ({ ...base, state: 'not_yet', lags: [], cumulative: null, clearLags: [], lastClearLag: null, dose, summary: `Not yet: ${why}.` })
  if (rowsRaw.length < minRows) return notYet(`${rowsRaw.length} of ${minRows} days with a full week of exposure behind them`)
  if (exposedDays < minExposed) return notYet(`${exposedDays} of ${minExposed} days with the exposure`)
  const first = rowsRaw[0].t
  const last = rowsRaw[rowsRaw.length - 1].t
  const X = rowsRaw.map((r) => [1, ...r.z, ...weekdayColumns(frame.dates[r.t]), ...trendColumns(r.t, first, last)])
  const fit = olsHac(X, rowsRaw.map((r) => r.y), rowsRaw.map((r) => r.t))
  if (!fit) return notYet('the exposure does not vary enough across the week')
  const p = maxLag + 1
  const cov = Array.from({ length: p }, (_, i) => Array.from({ length: p }, (_, j) => fit.cov[1 + i][1 + j]))
  const c = fit.beta.slice(1, 1 + p)
  const tc = fit.critical
  const toDisplay = (b: number) => (m.spec.transform === 'ln' ? (Math.exp(b) - 1) * 100 : m.spec.metric === 'sleep_hours' ? b * 60 : b)
  const combo = (w: number[]) => {
    const est = w.reduce((s, wi, i) => s + wi * c[i], 0)
    let v = 0
    for (let i = 0; i < p; i++) for (let j = 0; j < p; j++) v += w[i] * w[j] * cov[i][j]
    const se = Math.sqrt(Math.max(0, v))
    return { value: round(toDisplay(est)), lo: round(toDisplay(est - tc * se)), hi: round(toDisplay(est + tc * se)) }
  }
  const lags = Array.from({ length: p }, (_, l) => ({ lag: l, ...combo(Array.from({ length: p }, (_, i) => (i === l ? 1 : 0))) }))
  // Over the week after the exposure day (lags 1 to 6; lag 0 is the morning before an evening exposure).
  const cumulative = combo(Array.from({ length: p }, (_, i) => (i === 0 ? 0 : 1)))
  const clearLags = lags.filter((l) => l.lag >= 1 && (l.lo > 0 || l.hi < 0)).map((l) => l.lag)
  let lastClearLag: number | null = null
  if (clearLags.length > 0) {
    lastClearLag = clearLags[0]
    while (clearLags.includes(lastClearLag + 1)) lastClearLag++
  }
  const fmt = (v: number) => (unit === '%' ? `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(1)}%` : `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(1)} ${unit}`)
  const onset = clearLags.length > 0 ? lags[clearLags[0]] : null
  const week = `Over the week after: ${fmt(cumulative.value)} (95% interval ${fmt(cumulative.lo)} to ${fmt(cumulative.hi)}).`
  const summary = onset === null || lastClearLag === null
    ? `No day in the week after showed a difference clear of zero ${spec.perText} (${rowsRaw.length} days). ${week}`
    : `${onset.lag === 1 ? 'The next morning' : `${onset.lag} days later`} ${m.spec.metric === 'hrv' ? 'HRV' : m.spec.metric === 'rhr' ? 'resting heart rate' : 'sleep'} differed by ${fmt(onset.value)} ${spec.perText}, and the difference stayed clear of zero ${lastClearLag === onset.lag ? 'for that day only' : `until day ${lastClearLag}`}. ${week}`
  return { ...base, state: 'ready', lags, cumulative, clearLags, lastClearLag, dose, summary }
}

// Next-morning distance from expected (one-step forecast) by drinks the evening before: 0, 1-2, 3 or more.
function doseCurve(frame: Frame, m: BandModel): LagCurveResult['dose'] {
  const drinks = frame.series.alcohol_drinks ?? frame.dates.map(() => null)
  const groups: { group: string; test: (d: number) => boolean; vals: number[] }[] = [
    { group: '0', test: (d) => d === 0, vals: [] },
    { group: '1-2', test: (d) => d > 0 && d < 2.5, vals: [] },
    { group: '3+', test: (d) => d >= 2.5, vals: [] },
  ]
  for (let t = 1; t < frame.dates.length; t++) {
    const d = drinks[t - 1]
    if (d === null) continue
    const v = deviation(m, frame.dates, t, t)
    if (v === null) continue
    groups.find((g) => g.test(d))?.vals.push(v)
  }
  const out = groups
    .filter((g) => g.vals.length >= 5)
    .map((g) => {
      const mu = mean(g.vals)
      const half = tCritical(g.vals.length - 1) * (sd(g.vals) / Math.sqrt(g.vals.length))
      return { group: g.group, n: g.vals.length, mean: round(mu), lo: round(mu - half), hi: round(mu + half) }
    })
  return out.length >= 2 ? out : null
}
