// Analysis review phase G (P6, second half): a personal fitness-fatigue model. Erasable TypeScript, no I/O.
//
// The apps draw fitness and fatigue as exponentially weighted averages of daily training load with spans of 42 and 7 days
// (TrainingPeaks' convention, Banister's impulse-response model in its simplest form). Those spans are guesses for any one
// person. Here they are fitted to the person: tomorrow morning's ln HRV is forecast from today's ln HRV, the weekday, and
// fitness and fatigue at a pair of spans; the pair that fits the days before best is chosen. The fitted pair is kept only
// if it forecasts better than 42 and 7 on weeks the fit never saw (rolling origin, one week at a time, log score of a
// Gaussian forecast), with a Newey-West interval on the daily score difference: PREDICTIVE when the interval lies above
// zero, then the load chart uses the person's spans. Otherwise the standard ones stay.
import { invert } from './linalg.ts'
import { weekdayOf } from './stats.ts'
import type { Num } from './stats.ts'
import { spanEma } from './trainingLoad.ts'

export const STANDARD_SPANS = { ctl: 42, atl: 7 }
export const SPAN_GRID = { ctl: [14, 21, 28, 35, 42, 56, 70], atl: [3, 5, 7, 10, 14] }
export const FITNESS_FATIGUE = { warmupDays: 120, minHeldOut: 60, minLoadDays: 30, negligible: 0.02, minTrain: 60 }

export type FitnessFatigueState = 'predictive' | 'no_better' | 'unclear' | 'not_yet'

export interface FitnessFatigueResult {
  state: FitnessFatigueState
  standard: { ctl: number; atl: number }
  /** The spans a fit on the whole history chooses (null before there is enough to fit). */
  personal: { ctl: number; atl: number } | null
  /** True only when the personal spans forecast better on held-out weeks: the apps then draw load with them. */
  adopted: boolean
  heldOutDays: number
  loadDays: number
  /** Personal minus standard, mean daily log score of tomorrow's ln HRV on held-out days, with its 95% interval. */
  difference: number | null
  lo: number | null
  hi: number | null
  /** Whether load adds anything at all: the standard model minus the same model without fitness and fatigue. */
  loadAdds: { difference: number; lo: number; hi: number } | null
  summary: string
}

interface Pair {
  t: number
  y: number
  base: number[]
}

function solveOls(rows: number[][], ys: number[]): { beta: number[]; xtxInv: number[][]; s2: number } | null {
  const p = rows[0].length
  const n = rows.length
  if (n <= p + 2) return null
  const xtx = Array.from({ length: p }, () => new Array(p).fill(0))
  const xty = new Array(p).fill(0)
  for (let k = 0; k < n; k++) {
    const x = rows[k]
    for (let i = 0; i < p; i++) {
      xty[i] += x[i] * ys[k]
      for (let j = i; j < p; j++) xtx[i][j] += x[i] * x[j]
    }
  }
  for (let i = 0; i < p; i++) for (let j = 0; j < i; j++) xtx[i][j] = xtx[j][i]
  // A whisper of ridge keeps a constant column (a span with no load yet) from making the system singular.
  for (let i = 1; i < p; i++) xtx[i][i] += 1e-9
  const inv = invert(xtx)
  if (!inv) return null
  const beta = inv.map((r) => r.reduce((a, v, j) => a + v * xty[j], 0))
  let rss = 0
  for (let k = 0; k < n; k++) {
    const e = ys[k] - rows[k].reduce((a, v, j) => a + v * beta[j], 0)
    rss += e * e
  }
  return { beta, xtxInv: inv, s2: rss / (n - p) }
}

function logScore(fit: { beta: number[]; xtxInv: number[][]; s2: number }, x: number[], y: number): number {
  const mu = x.reduce((a, v, j) => a + v * fit.beta[j], 0)
  let lev = 0
  for (let i = 0; i < x.length; i++) for (let j = 0; j < x.length; j++) lev += x[i] * fit.xtxInv[i][j] * x[j]
  const v = fit.s2 * (1 + lev)
  return -0.5 * Math.log(2 * Math.PI * v) - ((y - mu) * (y - mu)) / (2 * v)
}

function rssOf(rows: number[][], ys: number[]): number {
  const fit = solveOls(rows, ys)
  return fit ? fit.s2 * (rows.length - rows[0].length) : Infinity
}

/** Mean of d with a Newey-West (Bartlett) 95% interval. */
export function hacMean(d: number[]): { mean: number; lo: number; hi: number } {
  const n = d.length
  const m = d.reduce((a, b) => a + b, 0) / n
  const e = d.map((v) => v - m)
  const lag = Math.floor(4 * Math.pow(n / 100, 2 / 9))
  let v = e.reduce((a, x) => a + x * x, 0) / n
  for (let l = 1; l <= lag; l++) {
    let g = 0
    for (let t = l; t < n; t++) g += e[t] * e[t - l]
    v += (2 * (1 - l / (lag + 1)) * g) / n
  }
  const se = Math.sqrt(Math.max(v, 0) / n)
  return { mean: m, lo: m - 1.96 * se, hi: m + 1.96 * se }
}

/**
 * dates: consecutive ISO days; hrv in ms (null when missing); load: the day's training load (null = no record, read as
 * rest once load has started).
 */
export function analyseFitnessFatigue(dates: string[], hrv: Num[], load: Num[]): FitnessFatigueResult {
  const n = dates.length
  const ln = hrv.map((v) => (v !== null && v > 0 ? Math.log(v) : null))
  const loadDays = load.filter((v) => v !== null && v > 0).length
  // Fitness and fatigue at every span on the grid, once.
  const spans = new Set([...SPAN_GRID.ctl, ...SPAN_GRID.atl, STANDARD_SPANS.ctl, STANDARD_SPANS.atl])
  const ema = new Map<number, (number | null)[]>([...spans].map((s) => [s, spanEma(load, s)]))
  // Pairs: today's values forecast tomorrow morning's ln HRV; the base regressors are an intercept, today's ln HRV and
  // tomorrow's weekday.
  const pairs: Pair[] = []
  for (let t = 0; t + 1 < n; t++) {
    const y0 = ln[t]
    const y1 = ln[t + 1]
    if (y0 === null || y1 === null || ema.get(STANDARD_SPANS.ctl)![t] === null) continue
    const wd = weekdayOf(dates[t + 1])
    pairs.push({ t, y: y1, base: [1, y0, ...[1, 2, 3, 4, 5, 6].map((k) => (wd === k ? 1 : 0))] })
  }
  const rowsFor = (ps: Pair[], ctl: number | null, atl: number | null) =>
    ps.map((p) => (ctl === null || atl === null ? p.base : [...p.base, ema.get(ctl)![p.t] as number, ema.get(atl)![p.t] as number]))
  const choose = (train: Pair[]) => {
    let best = { ctl: STANDARD_SPANS.ctl, atl: STANDARD_SPANS.atl, rss: Infinity }
    const ys = train.map((p) => p.y)
    for (const c of SPAN_GRID.ctl) {
      for (const a of SPAN_GRID.atl) {
        if (a >= c) continue
        const rss = rssOf(rowsFor(train, c, a), ys)
        if (rss < best.rss) best = { ctl: c, atl: a, rss }
      }
    }
    return { ctl: best.ctl, atl: best.atl }
  }

  const standard = { ...STANDARD_SPANS }
  const notYet = (have: string) => ({
    state: 'not_yet' as const, standard, personal: null, adopted: false, heldOutDays: 0, loadDays,
    difference: null, lo: null, hi: null, loadAdds: null,
    summary: `The time constants are fitted and tested once there are ${FITNESS_FATIGUE.warmupDays + FITNESS_FATIGUE.minHeldOut} mornings of HRV and ${FITNESS_FATIGUE.minLoadDays} training days (${have}); until then the standard 42 and 7 days stay.`,
  })
  if (pairs.length < FITNESS_FATIGUE.warmupDays + FITNESS_FATIGUE.minHeldOut || loadDays < FITNESS_FATIGUE.minLoadDays) {
    return notYet(`${pairs.length} mornings, ${loadDays} training days so far`)
  }

  // Rolling origin, one week at a time: everything known before the week trains, the week is scored.
  const personalScores: number[] = []
  const standardScores: number[] = []
  const noLoadScores: number[] = []
  for (let k = FITNESS_FATIGUE.warmupDays; k < pairs.length; k += 7) {
    const week = pairs.slice(k, k + 7)
    const firstT = week[0].t
    // A pair's outcome is the morning after t, known from t + 1: only pairs with t + 1 <= firstT may train.
    const train = pairs.filter((p) => p.t + 1 <= firstT)
    if (train.length < FITNESS_FATIGUE.minTrain) continue
    const ys = train.map((p) => p.y)
    const pick = choose(train)
    const fits = {
      personal: solveOls(rowsFor(train, pick.ctl, pick.atl), ys),
      standard: solveOls(rowsFor(train, STANDARD_SPANS.ctl, STANDARD_SPANS.atl), ys),
      noLoad: solveOls(rowsFor(train, null, null), ys),
    }
    if (!fits.personal || !fits.standard || !fits.noLoad) continue
    for (const p of week) {
      personalScores.push(logScore(fits.personal, rowsFor([p], pick.ctl, pick.atl)[0], p.y))
      standardScores.push(logScore(fits.standard, rowsFor([p], STANDARD_SPANS.ctl, STANDARD_SPANS.atl)[0], p.y))
      noLoadScores.push(logScore(fits.noLoad, p.base, p.y))
    }
  }
  const heldOutDays = personalScores.length
  const personal = choose(pairs)
  if (heldOutDays < FITNESS_FATIGUE.minHeldOut) return { ...notYet(`${heldOutDays} held-out mornings so far`), personal }
  const diff = hacMean(personalScores.map((s, i) => s - standardScores[i]))
  const adds = hacMean(standardScores.map((s, i) => s - noLoadScores[i]))
  const state: FitnessFatigueState = diff.lo > 0 ? 'predictive' : diff.hi < FITNESS_FATIGUE.negligible ? 'no_better' : 'unclear'
  const r4 = (v: number) => Math.round(v * 10000) / 10000
  const yours = `fitness ${personal.ctl} days, fatigue ${personal.atl} days`
  const loadLine = adds.lo > 0
    ? ' Training load itself improves the forecast over HRV alone.'
    : adds.hi < FITNESS_FATIGUE.negligible
      ? ' Training load adds little to the forecast over HRV alone.'
      : ''
  const summary = state === 'predictive'
    ? `Your own time constants (${yours}) forecast tomorrow's HRV better than the standard 42 and 7 on ${heldOutDays} mornings the fit never saw, so the load chart uses them.${loadLine}`
    : state === 'no_better'
      ? `Fitted to you, the time constants (${yours}) forecast tomorrow's HRV no better than the standard 42 and 7 on ${heldOutDays} mornings the fit never saw; the standard ones stay.${loadLine}`
      : `Fitted to you (${yours}), the time constants are not yet clearly better than the standard 42 and 7 at forecasting tomorrow's HRV (${heldOutDays} held-out mornings); the standard ones stay.${loadLine}`
  return {
    state, standard, personal, adopted: state === 'predictive', heldOutDays, loadDays,
    difference: r4(diff.mean), lo: r4(diff.lo), hi: r4(diff.hi),
    loadAdds: { difference: r4(adds.mean), lo: r4(adds.lo), hi: r4(adds.hi) },
    summary,
  }
}
