// Analysis Layer 3 / Phase 4: "is today typical for you?" as a state, never a score. Erasable TypeScript, no I/O.
//
// Replaces fixed-threshold personal-normal bands (a competitor's version called 25 % of days unusual against 8.7 % expected).
// How it works:
//   * five daily signals: ln HRV, resting heart rate, sleep hours, deep + REM minutes, steps;
//   * the baseline is a window of past days that excludes illness days, starts at the last confirmed series break, and ends
//     BEFORE the days being judged, so an illness in progress cannot make itself look normal;
//   * each signal is standardised with a robust location and scale (median, MAD), then the joint distribution is described by
//     a mean and a Ledoit-Wolf shrunk covariance, so correlated signals (low HRV with high resting heart rate) are judged
//     together and a handful of days still gives an invertible matrix;
//   * a day's distance is the Mahalanobis distance over the signals it has (at least 3), divided by their number;
//   * thresholds are the person's own leave-one-out distance quantiles, so "elevated" and "unusual" mean "further out than
//     90 % / 97.5 % of your own typical days" by construction, not by a textbook chi-square that real data do not obey;
//   * a day is only called strain when resting heart rate is up (and HRV down), and two such days in a row with no illness
//     logged suggest logging one.
// The final calendar day's steps are always treated as missing: the engine runs before the day is over.
import { invert, quad, shrunkCovariance, sub } from './linalg.ts'
import { median, sd } from './stats.ts'
import type { Num } from './stats.ts'

export const SIGNALS = ['hrv', 'rhr', 'sleep', 'deep_rem', 'steps'] as const
export type SignalName = (typeof SIGNALS)[number]

export const EVAL_DAYS = 14
export const BASELINE_DAYS = 120
export const MIN_COMPLETE = 45
export const MIN_DIMS = 3
export const WINSOR = 3.5
export const Q_ELEVATED = 0.9
export const Q_UNUSUAL = 0.975

export interface TypicalityInput {
  dates: string[]
  /** Raw HRV in ms (the engine takes the logarithm). */
  hrv: Num[]
  rhr: Num[]
  sleepHours: Num[]
  deepRemMin: Num[]
  steps: Num[]
  /** Illness days (and the day after): left out of the baseline, shown but not prompted on. */
  excluded: boolean[]
  /** Index of the first day of the current regime (last confirmed series break), 0 if none. */
  restartIndex?: number
  /** Signals to leave out (for example a device-sensitive one whose regime is unsettled). Default: none. */
  skip?: SignalName[]
}

export type DayState = 'typical' | 'elevated' | 'unusual' | 'insufficient'

export interface Driver {
  signal: SignalName
  /** Standardised deviation from your typical day (robust scale units). */
  z: number
  direction: 'up' | 'down'
}

export interface DayResult {
  date: string
  state: DayState
  /** Mahalanobis distance squared divided by the number of signals used. */
  d2k: number | null
  signalsUsed: number
  drivers: Driver[]
  strain: boolean
  excluded: boolean
}

export interface Lookalike {
  date: string
  illness: boolean
}

export interface TypicalityResult {
  asOf: string
  state: 'building' | 'typical' | 'elevated' | 'unusual' | 'insufficient'
  baselineDays: number
  baselineNeed: number
  baselineStart: string | null
  baselineEnd: string | null
  thresholds: { elevated: number; unusual: number } | null
  /** Share of the baseline's own days that its leave-one-out distances put above the 'unusual' threshold (about 2.5 %). */
  looUnusualShare: number | null
  shrinkage: number | null
  today: DayResult | null
  recent: DayResult[]
  strain: boolean
  suggestIllnessLog: boolean
  lookalikes: Lookalike[]
  /** Signals left out of this result (device-sensitive series whose regime changed inside the window). */
  skipped: SignalName[]
}

const r = (v: number, d = 3) => Math.round(v * 10 ** d) / 10 ** d

export function quantile(xs: number[], q: number): number {
  const s = [...xs].sort((a, b) => a - b)
  const pos = (s.length - 1) * q
  const lo = Math.floor(pos)
  const hi = Math.ceil(pos)
  return s[lo] + (s[hi] - s[lo]) * (pos - lo)
}

function robust(xs: number[]): { center: number; scale: number } {
  const center = median(xs)
  const mad = 1.4826 * median(xs.map((x) => Math.abs(x - center)))
  const spread = sd(xs)
  return { center, scale: mad > 1e-9 ? mad : spread > 1e-9 ? spread : 1 }
}

const empty = (asOf: string, state: TypicalityResult['state'], have: number, skipped: SignalName[] = []): TypicalityResult => ({
  asOf, state, baselineDays: have, baselineNeed: MIN_COMPLETE, baselineStart: null, baselineEnd: null, thresholds: null, looUnusualShare: null,
  shrinkage: null, today: null, recent: [], strain: false, suggestIllnessLog: false, lookalikes: [], skipped,
})

export function analyseTypicality(input: TypicalityInput): TypicalityResult {
  const n = input.dates.length
  const asOf = n ? input.dates[n - 1] : ''
  const skipped = (input.skip ?? []).filter((s, i, a) => a.indexOf(s) === i)
  if (n === 0) return empty(asOf, 'building', 0, skipped)
  const all: Record<SignalName, Num[]> = {
    hrv: input.hrv.map((v) => (v !== null && v > 0 ? Math.log(v) : null)),
    rhr: input.rhr,
    sleep: input.sleepHours,
    deep_rem: input.deepRemMin,
    steps: input.steps.map((v, i) => (i === n - 1 ? null : v)), // today is not over: its step count is partial
  }
  const names = SIGNALS.filter((s) => !(input.skip ?? []).includes(s))
  const sig: Num[][] = names.map((s) => all[s])
  const p = names.length
  const evalStart = Math.max(0, n - EVAL_DAYS)
  const lo = Math.max(input.restartIndex ?? 0, evalStart - BASELINE_DAYS, 0)
  const complete = (i: number) => sig.every((s) => s[i] !== null && Number.isFinite(s[i] as number))
  const baseIdx: number[] = []
  for (let i = lo; i < evalStart; i++) if (!input.excluded[i] && complete(i)) baseIdx.push(i)
  if (baseIdx.length < MIN_COMPLETE) return empty(asOf, 'building', baseIdx.length, skipped)

  const loc = names.map((_, j) => robust(baseIdx.map((i) => sig[j][i] as number)))
  const z = (i: number, j: number) => ((sig[j][i] as number) - loc[j].center) / loc[j].scale
  const fitRows = baseIdx.map((i) => names.map((_, j) => Math.max(-WINSOR, Math.min(WINSOR, z(i, j)))))
  const fit = shrunkCovariance(fitRows)
  const full = invert(fit.cov)
  if (!full) return empty(asOf, 'insufficient', baseIdx.length, skipped)

  // The person's own distribution of distances: each baseline day against a fit that has never seen it.
  const loo: number[] = []
  for (let k = 0; k < fitRows.length; k++) {
    const rest = fitRows.filter((_, m) => m !== k)
    const f = shrunkCovariance(rest)
    const inv = invert(f.cov)
    if (inv) loo.push(quad(inv, fitRows[k].map((v, j) => v - f.mean[j])) / p)
  }
  const thresholds = { elevated: quantile(loo, Q_ELEVATED), unusual: quantile(loo, Q_UNUSUAL) }

  const distance = (zc: number[], dims: number[]): number | null => {
    const inv = dims.length === p ? full : invert(sub(fit.cov, dims))
    return inv ? quad(inv, zc) / dims.length : null
  }

  const recent: DayResult[] = []
  for (let i = evalStart; i < n; i++) {
    const dims = names.map((_, j) => j).filter((j) => sig[j][i] !== null && Number.isFinite(sig[j][i] as number))
    if (dims.length < MIN_DIMS) {
      recent.push({ date: input.dates[i], state: 'insufficient', d2k: null, signalsUsed: dims.length, drivers: [], strain: false, excluded: input.excluded[i] === true })
      continue
    }
    const zc = dims.map((j) => z(i, j) - fit.mean[j])
    const d = distance(zc, dims)
    const state: DayState = d === null ? 'insufficient' : d > thresholds.unusual ? 'unusual' : d > thresholds.elevated ? 'elevated' : 'typical'
    const byName = (name: SignalName) => {
      const k = dims.indexOf(names.indexOf(name))
      return k < 0 ? null : zc[k]
    }
    const rhrZ = byName('rhr')
    const hrvZ = byName('hrv')
    const strain = (state === 'elevated' || state === 'unusual') && rhrZ !== null && (rhrZ >= 1.5 || (rhrZ >= 1 && hrvZ !== null && hrvZ <= -1))
    const drivers: Driver[] = dims
      .map((j, k) => ({ signal: names[j], z: zc[k] }))
      .filter((x) => Math.abs(x.z) >= 1)
      .sort((a, b) => Math.abs(b.z) - Math.abs(a.z))
      .slice(0, 3)
      .map((x) => ({ signal: x.signal, z: r(x.z, 2), direction: x.z > 0 ? ('up' as const) : ('down' as const) }))
    recent.push({ date: input.dates[i], state, d2k: d === null ? null : r(d), signalsUsed: dims.length, drivers, strain, excluded: input.excluded[i] === true })
  }

  const scored = recent.filter((d) => d.state !== 'insufficient')
  const today = scored.length ? scored[scored.length - 1] : null
  if (!today) return { ...empty(asOf, 'insufficient', baseIdx.length, skipped), baselineStart: input.dates[baseIdx[0]], baselineEnd: input.dates[baseIdx[baseIdx.length - 1]], recent }

  // Two strain days in a row with no illness logged around them: suggest logging one.
  const lastTwo = recent.slice(-2)
  const lastThree = recent.slice(-3)
  const suggestIllnessLog = lastTwo.length === 2 && lastTwo.every((d) => d.strain) && !lastThree.some((d) => d.excluded)

  // Past days that look like today, illness days included (so "this looks like the week you were ill" can be said).
  let lookalikes: Lookalike[] = []
  if (today.state === 'elevated' || today.state === 'unusual') {
    const i = input.dates.indexOf(today.date)
    const dims = names.map((_, j) => j).filter((j) => sig[j][i] !== null)
    const inv = invert(sub(fit.cov, dims))
    if (inv) {
      const cands: { date: string; d: number; illness: boolean }[] = []
      for (let k = Math.max(input.restartIndex ?? 0, 0); k < evalStart; k++) {
        if (!dims.every((j) => sig[j][k] !== null)) continue
        cands.push({ date: input.dates[k], d: quad(inv, dims.map((j) => z(i, j) - z(k, j))), illness: input.excluded[k] === true })
      }
      lookalikes = cands.sort((a, b) => a.d - b.d).slice(0, 3).map((c) => ({ date: c.date, illness: c.illness }))
    }
  }

  return {
    asOf,
    state: today.state === 'insufficient' ? 'insufficient' : today.state,
    baselineDays: baseIdx.length,
    baselineNeed: MIN_COMPLETE,
    baselineStart: input.dates[baseIdx[0]],
    baselineEnd: input.dates[baseIdx[baseIdx.length - 1]],
    thresholds: { elevated: r(thresholds.elevated), unusual: r(thresholds.unusual) },
    looUnusualShare: r(loo.filter((d) => d > thresholds.unusual).length / loo.length, 3),
    shrinkage: r(fit.delta, 3),
    today,
    recent,
    strain: today.strain,
    suggestIllnessLog,
    lookalikes,
    skipped,
  }
}
