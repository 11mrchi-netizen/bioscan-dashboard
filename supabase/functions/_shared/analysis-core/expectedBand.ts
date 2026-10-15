// Analysis review phase C (docs/analysis-review/README.md): the expected band. Erasable TypeScript, no I/O.
//
// Each morning is judged against a forecast made before the reading arrived, instead of against a 60-day mean:
//   * a slowly moving level plus a weekly pattern (exponential smoothing, ETS(A,N,A); Hyndman et al. 2008), fitted per
//     person and metric by a small grid search on one-step errors;
//   * the band's width is the spread of recent one-step errors, widened for gaps (the h-step forecast variance) and for
//     few errors (Student t), so an 80% band holds about 8 mornings in 10. The payload says how often it actually did;
//   * one morning outside the 80% band is ordinary and is not flagged. A flag needs a morning beyond the 99% band, or a
//     run of mornings on one side that trips an EWMA chart on the standardised errors (lambda 0.3, limit 2.4;
//     Lucas & Saccucci 1990). On simulated data with no change, low-side flags come about once every two months;
//   * a confirmed series break (a new device) starts a new regime: the level relearns from the new readings, the weekly
//     pattern carries over, and the band is built from new-regime errors only, so it is wide at first;
//   * illness days are shown against the band but neither move the model nor count.
// HRV is modelled in logs (its spread grows with its level). Every number in the payload is in natural units except
// `mu` and `sigma`, which are in model space so the phone can place a reading that arrives after the nightly run.
import { tCritical, weekdayOf } from './stats.ts'
import type { Num } from './stats.ts'

export type Better = 'higher' | 'lower' | 'range'

export interface BandSpec {
  /** Frame series name and analysis_results key. */
  metric: string
  unit: string
  transform: 'ln' | 'none'
  /** Which side is favorable; 'range' metrics (sleep duration) have no better side, only a healthy range. */
  better: Better
}

export const BAND_SPECS: BandSpec[] = [
  { metric: 'hrv', unit: 'ms', transform: 'ln', better: 'higher' },
  { metric: 'rhr', unit: 'bpm', transform: 'none', better: 'lower' },
  { metric: 'sleep_hours', unit: 'h', transform: 'none', better: 'range' },
]

export const BAND = {
  alphas: [0.05, 0.1, 0.15, 0.2, 0.3, 0.4],
  gammas: [0, 0.05, 0.1],
  /** Days at the start of the first regime used to set the level and weekly pattern; their errors do not count. */
  warmDays: 28,
  /** Readings averaged for the first level. */
  initObs: 14,
  /** Errors in the current regime before any band is drawn (wide: Student t on so few). */
  minBandErrors: 7,
  /** Errors before the band is ready and runs can be flagged. */
  minResiduals: 21,
  /** Days of errors behind the band's width. */
  residualWindow: 120,
  /** Model updates use errors clipped at this many band widths, so one faulty reading cannot drag the level. */
  clip: 3,
  lambda: 0.3,
  limit: 2.4,
  recentDays: 28,
  /** Beyond this many days since the last reading no band is drawn. */
  maxHorizon: 14,
}

export type BandFlag = 'extreme' | 'run' | null
export type BandSide = 'low' | 'high' | null
export type BandState = 'building' | 'provisional' | 'ready'

export interface BandPoint {
  date: string
  /** The reading, natural units; null when there was none. */
  value: number | null
  /** Centre of the band (for HRV the geometric centre), natural units. */
  expected: number | null
  lo80: number | null
  hi80: number | null
  lo99: number | null
  hi99: number | null
  /** Model-space forecast and band scale (log scale for HRV). */
  mu: number | null
  sigma: number | null
  /** Standardised error; null without a reading or a band. */
  z: number | null
  flag: BandFlag
  side: BandSide
  /** Logged illness: shown, but not used and never flagged. */
  excluded: boolean
}

export interface ExpectedBandResult {
  metric: string
  unit: string
  transform: 'ln' | 'none'
  better: Better
  state: BandState
  asOf: string
  /** First day of the current regime: the first reading, or the last confirmed series break. */
  regimeStart: string | null
  lastObserved: string | null
  /** One-step errors in the current regime, against the number needed for a ready band. */
  have: number
  need: number
  params: { alpha: number; gamma: number; sigma: number | null; df: number; q80: number | null; q99: number | null }
  /** How often the published 80% and 99% bands held over the last 120 days (each band was set before its morning). */
  calibration: { days: number; inside80: number | null; inside99: number | null }
  recent: BandPoint[]
  /** The band for the day after asOf, for a reading that arrives before the next run. */
  next: BandPoint | null
  /** The run chart: the phone steps it once with a new reading (value = lambda * clipped z + (1 - lambda) * value). */
  ewma: { lambda: number; limit: number; value: number; date: string | null; runsActive: boolean }
}

export interface BandInput {
  spec: BandSpec
  /** Consecutive ISO dates ending at asOf. */
  dates: string[]
  /** Natural units; null = no reading. */
  values: Num[]
  excluded: boolean[]
  /** Confirmed series breaks of this metric: each starts a new regime. */
  restartDates: string[]
}

// ---- the filter ----

export interface FilterStep {
  mu: number | null
  sigma: number | null
  /** Errors behind sigma (the Student t degrees of freedom). */
  df: number
  z: number | null
  /** z counts toward calibration and the run chart. */
  scored: boolean
  ewma: number
  flag: BandFlag
  side: BandSide
}

export interface FilterRun {
  steps: FilterStep[]
  /** Mean squared one-step error over every counted error (the fitting criterion). */
  meanSquare: number
  counted: number
  /** Errors and first index of the regime in force at the last step. */
  regimeErrors: number
  regimeStart: number
  lastUpdate: number
}

const WEEK = 7
const EWMA_LIMIT = BAND.limit * Math.sqrt(BAND.lambda / (2 - BAND.lambda))

const quantiles = new Map<string, number>()
/** Two-sided Student t critical value, cached (df is a small integer). */
export function tq(df: number, alpha: number): number {
  const key = `${df}:${alpha}`
  let q = quantiles.get(key)
  if (q === undefined) {
    q = tCritical(df, alpha)
    quantiles.set(key, q)
  }
  return q
}
export const q80 = (df: number) => tq(df, 0.2)
export const q99 = (df: number) => tq(df, 0.01)

/** h-step forecast variance of ETS(A,N,A) relative to one step (Hyndman et al. 2008, class 1). */
export function horizonVariance(h: number, alpha: number, gamma: number): number {
  const k = Math.floor((h - 1) / WEEK)
  return 1 + (h - 1) * alpha * alpha + gamma * (2 * alpha + gamma) * k
}

const clamp = (v: number, lim: number) => Math.max(-lim, Math.min(lim, v))

function initialState(dates: string[], values: Num[], usable: (t: number) => boolean, first: number) {
  const obs: number[] = []
  for (let t = first; t < values.length && obs.length < BAND.initObs; t++) if (usable(t)) obs.push(values[t] as number)
  const level = obs.reduce((a, b) => a + b, 0) / obs.length
  const sums = [0, 0, 0, 0, 0, 0, 0]
  const counts = [0, 0, 0, 0, 0, 0, 0]
  let total = 0
  let m = 0
  for (let t = first; t < Math.min(values.length, first + BAND.warmDays); t++) {
    if (!usable(t)) continue
    const w = weekdayOf(dates[t])
    sums[w] += values[t] as number
    counts[w]++
    total += values[t] as number
    m++
  }
  const raw = sums.map((s, w) => (counts[w] > 0 ? s / counts[w] - total / m : 0))
  const centre = raw.reduce((a, b) => a + b, 0) / WEEK
  return { level, season: raw.map((s) => s - centre) }
}

/** Runs the filter over model-space values with fixed smoothing weights. `restarts` are indices that open a new regime. */
export function runFilter(dates: string[], values: Num[], excluded: boolean[], restarts: number[], alpha: number, gamma: number): FilterRun {
  const n = dates.length
  const usable = (t: number) => values[t] !== null && Number.isFinite(values[t] as number) && excluded[t] !== true
  const blank: FilterStep = { mu: null, sigma: null, df: 0, z: null, scored: false, ewma: 0, flag: null, side: null }
  let first = -1
  for (let t = 0; t < n; t++) if (usable(t)) { first = t; break }
  if (first < 0) return { steps: dates.map(() => ({ ...blank })), meanSquare: Infinity, counted: 0, regimeErrors: 0, regimeStart: -1, lastUpdate: -1 }

  const starts = [...new Set(restarts)].filter((r) => r > first).sort((a, b) => a - b)
  const init = initialState(dates, values, usable, first)
  let level = init.level
  const season = init.season
  let regimeStart = first
  let restarted = false
  let seen = 0 // readings in a restarted regime
  let errs: { t: number; u: number }[] = []
  let lo = 0 // first error inside the width window
  let winSq = 0 // sum of squared errors from lo on
  let ewma = 0
  let lastUpdate = -1
  let sumSq = 0
  let counted = 0
  let nextStart = 0
  const steps: FilterStep[] = []

  for (let t = 0; t < n; t++) {
    while (nextStart < starts.length && starts[nextStart] <= t) {
      regimeStart = starts[nextStart++]
      restarted = true
      seen = 0
      errs = []
      lo = 0
      winSq = 0
      ewma = 0
    }
    if (t < first) {
      steps.push({ ...blank })
      continue
    }
    const w = weekdayOf(dates[t])
    const mu = level + season[w]
    const h = lastUpdate < 0 ? 1 : t - lastUpdate
    const v = horizonVariance(h, alpha, gamma)

    // Width from the regime's recent one-step errors: the last residualWindow days, or the last minResiduals errors.
    while (lo < errs.length && errs[lo].t < t - BAND.residualWindow) {
      winSq -= errs[lo].u * errs[lo].u
      lo++
    }
    let df = errs.length - lo
    let ss = Math.max(0, winSq)
    if (df < BAND.minResiduals) {
      const from = Math.max(0, errs.length - BAND.minResiduals)
      ss = 0
      for (let i = from; i < errs.length; i++) ss += errs[i].u * errs[i].u
      df = errs.length - from
    }
    const s = df > 0 ? Math.sqrt(ss / df) : NaN
    const sigma = df >= BAND.minBandErrors && h <= BAND.maxHorizon && s > 0 ? s * Math.sqrt(v) : null

    const step: FilterStep = { mu, sigma, df, z: null, scored: false, ewma, flag: null, side: null }
    const y = values[t]
    if (y !== null && Number.isFinite(y)) {
      const e = y - mu
      if (sigma !== null) {
        step.z = e / sigma
        if (usable(t)) {
          step.scored = true
          const extreme = q99(df)
          ewma = BAND.lambda * clamp(step.z, extreme) + (1 - BAND.lambda) * ewma
          step.ewma = ewma
          if (Math.abs(step.z) > extreme) {
            step.flag = 'extreme'
            step.side = step.z < 0 ? 'low' : 'high'
          } else if (df >= BAND.minResiduals && Math.abs(ewma) > EWMA_LIMIT) {
            step.flag = 'run'
            step.side = ewma < 0 ? 'low' : 'high'
          }
        }
      }
      if (usable(t)) {
        if (restarted) seen++
        // Errors count after the first regime's opening weeks, or from the second reading of a restarted regime.
        const counts = restarted ? seen >= 2 : t >= first + BAND.warmDays
        if (counts) {
          const u = e / Math.sqrt(v)
          errs.push({ t, u })
          winSq += u * u
          sumSq += u * u
          counted++
        }
        // A restarted regime relearns its level as a running mean until the smoothing weight takes over; the weekly
        // pattern carries over and is left alone meanwhile, so the device's offset does not leak into it.
        const gain = restarted ? Math.max(alpha, 1 / seen) : alpha
        const ec = sigma !== null && gain <= alpha ? clamp(e, BAND.clip * sigma) : e
        level += gain * ec
        if (gain <= alpha) season[w] += gamma * ec
        lastUpdate = t
      }
    }
    steps.push(step)
  }
  return { steps, meanSquare: counted > 0 ? sumSq / counted : Infinity, counted, regimeErrors: errs.length, regimeStart, lastUpdate }
}

/** Picks the smoothing weights with the smallest mean squared one-step error. Ties keep the smoother setting. */
export function fitWeights(dates: string[], values: Num[], excluded: boolean[], restarts: number[]): { alpha: number; gamma: number } {
  let best = { alpha: BAND.alphas[1], gamma: 0, ms: Infinity }
  for (const alpha of BAND.alphas) {
    for (const gamma of BAND.gammas) {
      const run = runFilter(dates, values, excluded, restarts, alpha, gamma)
      if (run.counted >= BAND.minBandErrors && run.meanSquare < best.ms - 1e-12) best = { alpha, gamma, ms: run.meanSquare }
    }
  }
  return { alpha: best.alpha, gamma: best.gamma }
}

// ---- the published result ----

const nextDay = (d: string) => new Date(Date.parse(d + 'T00:00:00Z') + 86400000).toISOString().slice(0, 10)
const r = (v: number, d: number) => Math.round(v * 10 ** d) / 10 ** d

export function analyseExpectedBand(input: BandInput): ExpectedBandResult {
  const { spec } = input
  const ln = spec.transform === 'ln'
  const toModel = (v: Num): Num => (v === null || !Number.isFinite(v) ? null : ln ? (v > 0 ? Math.log(v) : null) : v)
  const fromModel = (v: number) => (ln ? Math.exp(v) : v)
  const asOf = input.dates[input.dates.length - 1]
  // One extra day: the band for tomorrow morning.
  const dates = [...input.dates, nextDay(asOf)]
  const values = [...input.values.map(toModel), null]
  const excluded = [...input.excluded.map((x) => x === true), false]
  const restarts = input.restartDates.map((d) => dates.findIndex((x) => x >= d)).filter((i) => i > 0)

  const { alpha, gamma } = fitWeights(dates, values, excluded, restarts)
  const run = runFilter(dates, values, excluded, restarts, alpha, gamma)
  const last = dates.length - 1

  const point = (t: number): BandPoint => {
    const st = run.steps[t]
    const natural = input.values[t] ?? null
    const banded = st.mu !== null && st.sigma !== null
    const edge = (q: number) => (banded ? r(fromModel((st.mu as number) + q * (st.sigma as number)), 2) : null)
    return {
      date: dates[t],
      value: natural === null || !Number.isFinite(natural) ? null : r(natural, 2),
      expected: banded ? r(fromModel(st.mu as number), 2) : null,
      lo80: edge(-q80(st.df)),
      hi80: edge(q80(st.df)),
      lo99: edge(-q99(st.df)),
      hi99: edge(q99(st.df)),
      mu: banded ? r(st.mu as number, 5) : null,
      sigma: banded ? r(st.sigma as number, 5) : null,
      z: st.z === null ? null : r(st.z, 2),
      flag: st.flag,
      side: st.side,
      excluded: excluded[t],
    }
  }

  // Calibration over the last residualWindow days: every scored morning, judged by the band set before it.
  let days = 0
  let in80 = 0
  let in99 = 0
  for (let t = Math.max(0, last - BAND.residualWindow); t < last; t++) {
    const st = run.steps[t]
    if (!st.scored || st.z === null) continue
    days++
    if (Math.abs(st.z) <= q80(st.df)) in80++
    if (Math.abs(st.z) <= q99(st.df)) in99++
  }

  const have = run.regimeErrors
  const state: BandState = have < BAND.minBandErrors ? 'building' : have < BAND.minResiduals ? 'provisional' : 'ready'
  let lastScored = -1
  for (let t = last - 1; t >= Math.max(0, run.regimeStart); t--) if (run.steps[t].scored) { lastScored = t; break }
  const tomorrow = run.steps[last]

  return {
    metric: spec.metric,
    unit: spec.unit,
    transform: spec.transform,
    better: spec.better,
    state,
    asOf,
    regimeStart: run.regimeStart >= 0 ? dates[run.regimeStart] : null,
    lastObserved: run.lastUpdate >= 0 ? dates[run.lastUpdate] : null,
    have,
    need: BAND.minResiduals,
    params: {
      alpha,
      gamma,
      sigma: tomorrow.sigma === null ? null : r(tomorrow.sigma, 5),
      df: tomorrow.df,
      q80: tomorrow.sigma === null ? null : r(q80(tomorrow.df), 4),
      q99: tomorrow.sigma === null ? null : r(q99(tomorrow.df), 4),
    },
    calibration: { days, inside80: days > 0 ? r(in80 / days, 3) : null, inside99: days > 0 ? r(in99 / days, 3) : null },
    recent: Array.from({ length: Math.min(BAND.recentDays, last) }, (_, i) => point(last - Math.min(BAND.recentDays, last) + i)),
    next: tomorrow.sigma === null ? null : point(last),
    ewma: {
      lambda: BAND.lambda,
      limit: r(EWMA_LIMIT, 4),
      value: lastScored >= 0 ? r(run.steps[lastScored].ewma, 4) : 0,
      date: lastScored >= 0 ? dates[lastScored] : null,
      runsActive: have >= BAND.minResiduals,
    },
  }
}
