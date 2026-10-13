// Intervention & Protocol Intelligence (08.6): observational response analysis, for any intervention -- not only a
// pre-registered experiment (08.5, experiment.ts). Erasable TypeScript, no I/O.
//
// Unlike analyseExperiment, there is no pre-registered design here: this compares a metric's baseline window
// (before the intervention started) against its active window (since it started), on an intervention that was
// simply begun, not set up as an AB/ABAB test. That is weaker evidence -- no washout, no ABAB replication check,
// no locked protocol guarding against re-choosing the comparison after seeing the data, and (because interventions
// commonly run indefinitely, unlike a fixed-length experiment) no final "complete" stage either: this is always a
// rolling, interim read of whatever baseline/active data exists as of `today`. Verdicts and summaries are worded
// as an "observed signal during intervention", never a detected effect, matching DAV-336's instruction to use
// language like "data are consistent with ..." rather than asserting causation.
//
// Small helpers below (lagOneRho/effN/groupStats/signedText) intentionally mirror experiment.ts's private ones
// rather than importing them -- same repo convention as relationship.ts's own local `signed`/`toNatural`/`round`:
// each engine file owns its small helpers rather than sharing a single generic version.
import { autocorr1, benjaminiHochberg, mean, pFromT, sd, tCritical, weekdayDemean } from './stats.ts'
import type { Num } from './stats.ts'
import type { Frame } from './relationship.ts'
import { METRIC_INFO } from './experiment.ts'
import type { MetricId } from './experiment.ts'

export const MIN_WINDOW_DAYS = 14

export type ResponseVerdict = 'signal_observed' | 'no_signal' | 'insufficient'

export interface InterventionResponseWindows {
  baselineStart: string
  baselineEnd: string
  activeStart: string
  /** null while the intervention has no ended_at yet -- analysis still runs, using data up to `today`. */
  activeEnd: string | null
}

export interface MetricResponse {
  metric: MetricId
  label: string
  verdict: ResponseVerdict
  n0: number
  n1: number
  mean0: number | null
  mean1: number | null
  diff: { value: number; lo: number; hi: number; unit: string } | null
  mde: number | null
  p: number | null
  pAdjusted: number | null
  summary: string
  notes: string[]
}

export interface InterventionResponseResult {
  interventionId: string
  asOf: string
  metrics: MetricResponse[]
}

const r = (v: number, d = 3) => Math.round(v * 10 ** d) / 10 ** d

function toNatural(ln: boolean, v: number): number {
  return ln ? (Math.exp(v) - 1) * 100 : v
}

function signedText(v: number, unit: string): string {
  const d = unit === '%' || unit === 'min' ? 1 : 2
  return `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(d)}${unit === '%' ? '%' : ' ' + unit}`
}

const present = (xs: Num[]) => xs.filter((v): v is number => v !== null)

// Lag-1 autocorrelation with the small-sample bias of the estimator removed -- same correction experiment.ts uses,
// so a short window doesn't look falsely independent and the test isn't too eager.
function lagOneRho(values: Num[]): number {
  const n = values.filter((v) => v !== null).length
  const r1 = autocorr1(values)
  return Math.max(0, Math.min(0.9, n > 4 ? r1 + (1 + 3 * r1) / n : r1))
}

const effN = (n: number, rho: number) => Math.max(2, Math.min(n, (n * (1 - rho)) / (1 + rho)))

function groupStats(a: Num[], b: Num[]) {
  const x0 = present(a)
  const x1 = present(b)
  const n0e = effN(x0.length, lagOneRho(a))
  const n1e = effN(x1.length, lagOneRho(b))
  const v0 = x0.length > 1 ? sd(x0) ** 2 : NaN
  const v1 = x1.length > 1 ? sd(x1) ** 2 : NaN
  const se = Math.sqrt(v0 / n0e + v1 / n1e)
  return { x0, x1, n0e, n1e, se, diff: mean(x1) - mean(x0) }
}

/** The window label of each axis day, or null outside both windows. */
function windowLabels(dates: string[], w: InterventionResponseWindows, today: string): (0 | 1 | null)[] {
  return dates.map((d) => {
    if (d >= w.baselineStart && d <= w.baselineEnd) return 0
    const activeEnd = w.activeEnd ?? today
    if (d >= w.activeStart && d <= activeEnd) return 1
    return null
  })
}

function prepareSeries(frame: Frame, labels: (0 | 1 | null)[], info: { series: string; ln: boolean }): { a: Num[]; b: Num[]; rawA: Num[]; rawB: Num[] } {
  const raw = frame.series[info.series] ?? []
  const used: Num[] = frame.dates.map((_, i) => {
    const v = raw[i]
    if (labels[i] === null || frame.excluded[i] === true || v === null || v === undefined || !Number.isFinite(v)) return null
    if (info.ln) return v > 0 ? Math.log(v) : null
    return v
  })
  const all = weekdayDemean(frame.dates, used)
  return {
    a: all.map((v, i) => (labels[i] === 0 ? v : null)),
    b: all.map((v, i) => (labels[i] === 1 ? v : null)),
    rawA: used.map((v, i) => (labels[i] === 0 ? v : null)),
    rawB: used.map((v, i) => (labels[i] === 1 ? v : null)),
  }
}

/**
 * Observational baseline-vs-active comparison for one intervention, across every metric asked for. No metric is
 * "primary" here (there is no pre-registration to name one), so every p-value shares one Benjamini-Hochberg
 * correction across the metrics actually analysed (the ones with enough data on both sides).
 */
export function analyseInterventionResponse(interventionId: string, metrics: MetricId[], windows: InterventionResponseWindows, frame: Frame, today: string): InterventionResponseResult {
  const labels = windowLabels(frame.dates, windows, today)
  const out: MetricResponse[] = []

  for (const id of metrics) {
    const info = METRIC_INFO[id]
    const prep = prepareSeries(frame, labels, info)
    const g = groupStats(prep.a, prep.b)
    const n0 = g.x0.length
    const n1 = g.x1.length
    const notes: string[] = []
    const base: MetricResponse = {
      metric: id, label: info.label, verdict: 'insufficient', n0, n1, mean0: null, mean1: null, diff: null, mde: null, p: null, pAdjusted: null, summary: '', notes,
    }
    if (n0 < MIN_WINDOW_DAYS || n1 < MIN_WINDOW_DAYS || !Number.isFinite(g.se) || g.se === 0) {
      base.summary = `Not enough usable days yet: ${n0} baseline and ${n1} since starting, at least ${MIN_WINDOW_DAYS} of each are needed.`
      out.push(base)
      continue
    }
    const df = Math.max(2, g.n0e + g.n1e - 2)
    const t = g.diff / g.se
    const p = pFromT(t, df)
    const half = tCritical(df) * g.se
    const mdeRaw = (tCritical(df) + 0.85) * g.se
    const phaseMean = (xs: Num[]) => (info.ln ? Math.exp(mean(present(xs))) : mean(present(xs)))
    base.mean0 = r(phaseMean(prep.rawA), 2)
    base.mean1 = r(phaseMean(prep.rawB), 2)
    base.diff = { value: r(toNatural(info.ln, g.diff)), lo: r(toNatural(info.ln, g.diff - half)), hi: r(toNatural(info.ln, g.diff + half)), unit: info.unit }
    base.mde = r(Math.abs(toNatural(info.ln, mdeRaw)))
    base.p = r(p, 5)
    base.verdict = p < 0.05 ? 'signal_observed' : 'no_signal'
    base.summary = base.verdict === 'signal_observed'
      ? `${info.label} ${signedText(base.diff.value, info.unit)} since starting (95% interval ${signedText(base.diff.lo, info.unit)} to ${signedText(base.diff.hi, info.unit)}), an observed signal during intervention -- not a controlled comparison, so other things that changed over the same period could explain part or all of it.`
      : `No detectable signal on ${info.label.toLowerCase()}: the estimate was ${signedText(base.diff.value, info.unit)} (95% interval ${signedText(base.diff.lo, info.unit)} to ${signedText(base.diff.hi, info.unit)}), and this comparison could only have seen a difference larger than ${base.mde.toFixed(info.unit === '%' ? 1 : 2)} ${info.unit === '%' ? '%' : info.unit}.`
    out.push(base)
  }

  const testable = out.filter((m) => m.p !== null)
  if (testable.length > 0) {
    const adj = benjaminiHochberg(testable.map((m) => m.p as number))
    testable.forEach((m, i) => {
      m.pAdjusted = r(adj[i], 5)
      if (m.verdict === 'signal_observed' && adj[i] >= 0.05) {
        m.verdict = 'no_signal'
        m.notes.push('Not distinguishable from noise after correcting across the metrics analysed together.')
        m.summary = `No detectable signal on ${m.label.toLowerCase()} after correcting across the metrics analysed together (estimate ${signedText(m.diff!.value, m.diff!.unit)}).`
      }
    })
  }

  return { interventionId, asOf: today, metrics: out }
}
