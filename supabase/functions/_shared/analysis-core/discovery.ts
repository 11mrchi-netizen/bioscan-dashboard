// Analysis hub phase C2 (docs/analysis-console/PLAN.md; docs/analysis-review/README.md, "Revisit"): the corrected discovery
// screen that replaces the retired exploratory lag scan. The old scan ran raw Pearson correlations over +/-14 lags with no
// adjustment, so about one in twenty of its bars stood out by chance. This one:
//   - scans only questions that can be registered (the six registrable exposures x HRV, resting HR and sleep, lags 0-3),
//     so every hint has a way to be confirmed;
//   - runs on the OLDER two-thirds of the history only; the newest third is held out, and a registered question is then
//     tested only on days after its registration (evidence ledger), never on the days that suggested it;
//   - removes the weekday pattern and the slow trend from both series, then prewhitens each (AR(1) residuals), so shared
//     trends and day-to-day carry-over do not pass for a link (Box-Jenkins: the exposure's AR(1) filter on both);
//   - judges each correlation on its effective sample size (Bartlett, lags 1-30) and corrects for the number of looks with Benjamini-Hochberg
//     (false-discovery rate 10%); each cell carries its 95% chance band, +/-1.96/sqrt(n_eff);
//   - says "too few" below MIN_PAIRS pairs or MIN_EXPOSED exposed days.
// A flagged cell is a hint, not a finding: the console labels it exploratory and offers to register it as a question.
// Erasable TypeScript, no I/O.
import { HYPOTHESES, REGISTRABLE_EXPOSURES } from './hypotheses.ts'
import type { Frame } from './relationship.ts'
import { autocorr1, benjaminiHochberg, pairedLag, pearson, pFromR, weekdayDemean, type Num } from './stats.ts'

export const DISCOVERY_VERSION = '1.0.0'
export const DISCOVERY = { maxLag: 3, minPairs: 60, minExposed: 12, fdr: 0.1, share: 2 / 3, trendHalfWidth: 14, bartlettLags: 30 } as const

export const DISCOVERY_OUTCOMES = ['hrv', 'rhr', 'sleep_hours'] as const
export type DiscoveryOutcome = (typeof DISCOVERY_OUTCOMES)[number]

/** The default no-effect zone a registered question gets, in its outcome's unit (% for HRV, bpm, minutes of sleep). */
export const DEFAULT_NO_EFFECT: Record<DiscoveryOutcome, number> = { hrv: 2, rhr: 1, sleep_hours: 10 }

export type CellStatus = 'flagged' | 'chance' | 'too_few'

export interface DiscoveryCell {
  exposure: string
  outcome: DiscoveryOutcome
  lag: number
  /** Correlation of the prewhitened series; null when too few pairs. */
  r: number | null
  n: number
  nEff: number
  /** Days with the exposure among the pairs. */
  exposed: number
  /** Half-width of the 95% chance band for r. */
  band: number | null
  p: number | null
  /** Benjamini-Hochberg adjusted p across every testable cell. */
  q: number | null
  status: CellStatus
  /** Already a question of the fixed catalogue (it has its own verdict on the evidence board). */
  catalogued: boolean
}

export interface DiscoveryResult {
  version: string
  /** The days scanned (older part) and the days held out (newest third). */
  window: { start: string; end: string }
  heldOut: { start: string; end: string } | null
  exposures: string[]
  outcomes: DiscoveryOutcome[]
  cells: DiscoveryCell[]
  tests: number
  flagged: number
}

/** Subtracts a centred moving average (the slow trend); a day keeps a value only where the window has enough data. */
export function detrend(values: Num[], half: number = DISCOVERY.trendHalfWidth): Num[] {
  return values.map((v, i) => {
    if (v === null) return null
    let s = 0
    let n = 0
    for (let k = Math.max(0, i - half); k <= Math.min(values.length - 1, i + half); k++) {
      const x = values[k]
      if (x !== null) { s += x; n++ }
    }
    return n >= 7 ? v - s / n : null
  })
}

/**
 * AR(1) filtering: e_t = x_t - rho * x_{t-1}, null where either day is missing. Box-Jenkins prewhitening applies the
 * EXPOSURE's filter to both series, so a lag-1 effect stays at lag 1 (filtering the outcome with its own coefficient
 * smeared it into an opposite echo at lag 2); the outcome's remaining autocorrelation is handled by the effective n.
 */
export function prewhiten(values: Num[], rho: number = autocorr1(values)): Num[] {
  return values.map((v, i) => {
    const prev = i > 0 ? values[i - 1] : null
    return v === null || prev === null ? null : v - rho * prev
  })
}

/** Autocorrelation at lag k over the pairs present at t and t+k. */
export function autocorrAt(xs: Num[], k: number): number {
  const a: number[] = []
  const b: number[] = []
  for (let i = 0; i + k < xs.length; i++) {
    const p = xs[i]
    const q = xs[i + k]
    if (p !== null && q !== null) { a.push(p); b.push(q) }
  }
  return a.length < 10 ? 0 : pearson(a, b)
}

/**
 * Bartlett's effective sample size for a correlation between two autocorrelated series: n / (1 + 2 sum_k rx(k) ry(k)),
 * k = 1..K. Detrending leaves weak autocorrelation at many lags in both series; a lag-1 correction alone missed it and
 * let about 6% of null tests through at p < 0.05 (12-16% of null histories flagged something). Never above n.
 */
export function bartlettN(n: number, acfX: number[], acfY: number[]): number {
  let s = 0
  for (let k = 0; k < Math.min(acfX.length, acfY.length); k++) s += acfX[k] * acfY[k]
  return Math.max(3, Math.min(n, n / (1 + 2 * s)))
}

function acf(xs: Num[]): number[] {
  return Array.from({ length: DISCOVERY.bartlettLags }, (_, i) => autocorrAt(xs, i + 1))
}

/** Weekday pattern and slow trend removed (the log first for HRV). */
function adjust(raw: Num[], dates: string[], excluded: boolean[], log: boolean): Num[] {
  const v = raw.map((x, i) => (x === null || excluded[i] || (log && !(x > 0)) ? null : log ? Math.log(x) : x))
  return detrend(weekdayDemean(dates, v))
}

export function discover(frame: Frame, exposures: string[] = Object.keys(REGISTRABLE_EXPOSURES), outcomes: DiscoveryOutcome[] = [...DISCOVERY_OUTCOMES]): DiscoveryResult {
  const total = frame.dates.length
  const cut = Math.floor(total * DISCOVERY.share)
  const dates = frame.dates.slice(0, cut)
  const excluded = frame.excluded.slice(0, cut)
  const window = { start: dates[0] ?? '', end: dates[dates.length - 1] ?? '' }
  const heldOut = cut < total ? { start: frame.dates[cut], end: frame.dates[total - 1] } : null
  const exs = exposures.filter((e) => frame.series[e])
  const outs = outcomes.filter((o) => frame.series[o])

  const cells: DiscoveryCell[] = []
  for (const ex of exs) {
    const rawX = frame.series[ex].slice(0, cut)
    const xAdj = adjust(rawX, dates, excluded, false)
    const rhoX = autocorr1(xAdj)
    const x = prewhiten(xAdj, rhoX)
    const acfX = acf(x)
    for (const out of outs) {
      const y = prewhiten(adjust(frame.series[out].slice(0, cut), dates, excluded, out === 'hrv'), rhoX)
      const acfY = acf(y)
      for (let lag = 0; lag <= DISCOVERY.maxLag; lag++) {
        const pairs = pairedLag(x, y, lag)
        const n = pairs.x.length
        const exposed = pairs.idx.filter((t) => rawX[t] === 1).length // idx is the exposure day
        const catalogued = HYPOTHESES.some((h) => h.cause === ex && h.target === out && h.lag === lag)
        const base = { exposure: ex, outcome: out, lag, n, exposed, catalogued }
        if (n < DISCOVERY.minPairs || exposed < DISCOVERY.minExposed || n - exposed < DISCOVERY.minExposed) {
          cells.push({ ...base, r: null, nEff: n, band: null, p: null, q: null, status: 'too_few' })
          continue
        }
        const r = pearson(pairs.x, pairs.y)
        const nEff = bartlettN(n, acfX, acfY)
        const ok = Number.isFinite(r)
        cells.push({ ...base, r: ok ? r : null, nEff, band: 1.96 / Math.sqrt(nEff), p: ok ? pFromR(r, nEff) : null, q: null, status: ok ? 'chance' : 'too_few' })
      }
    }
  }
  const testable = cells.filter((c) => c.p !== null)
  const qs = benjaminiHochberg(testable.map((c) => c.p as number))
  testable.forEach((c, i) => {
    c.q = qs[i]
    if (qs[i] < DISCOVERY.fdr) c.status = 'flagged'
  })
  return { version: DISCOVERY_VERSION, window, heldOut, exposures: exs, outcomes: outs, cells, tests: testable.length, flagged: testable.filter((c) => c.status === 'flagged').length }
}
