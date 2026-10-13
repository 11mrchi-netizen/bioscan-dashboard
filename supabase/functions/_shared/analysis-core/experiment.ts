// Analysis Layer 3 / Phase 5: supplement N-of-1 experiments. Erasable TypeScript, no I/O.
//
// An experiment is registered BEFORE it starts: which supplement, which outcome is primary (at most three secondary), and the
// phases (A = without, B = with; AB or ABAB) with dates. The database refuses later changes to that protocol, and this module
// analyses exactly what was registered:
//   * the comparison is B days against A days, by phase membership (intention to treat), not by days the supplement happened
//     to be logged; adherence is reported separately from the real log and nothing is assumed from a schedule;
//   * days with illness (and the day after) are left out, so are alcohol days and the morning after when the protocol says so,
//     and the first `washoutDays` of each B phase;
//   * the outcome is adjusted for day of week, the difference of means is reported in natural units with an interval that uses
//     the effective sample size implied by day-to-day autocorrelation (a Student t with that many degrees of freedom);
//   * the smallest difference the registered design could detect (80 % power) is reported next to every estimate, so
//     "no detectable effect" says what size of effect was ruled out of sight;
//   * a pre-existing trend in the A phase is checked, because a step in a drifting series looks like an effect;
//   * while the experiment is still running the numbers are labelled interim and no verdict is given.
import { autocorr1, benjaminiHochberg, mean, pFromT, sd, tCritical, weekdayDemean } from './stats.ts'
import type { Num } from './stats.ts'
import type { Frame } from './relationship.ts'

export type MetricId = 'hrv' | 'rhr' | 'sleep_hours' | 'deep_rem_min' | 'energy' | 'mood' | 'stress' | 'soreness'

interface MetricInfo {
  label: string
  series: string
  ln: boolean
  unit: string
  /** series_break metric keys that describe this metric's regime. */
  breakKeys: string[]
}

export const METRIC_INFO: Record<MetricId, MetricInfo> = {
  hrv: { label: 'HRV', series: 'hrv', ln: true, unit: '%', breakKeys: ['hrv'] },
  rhr: { label: 'Resting heart rate', series: 'rhr', ln: false, unit: 'bpm', breakKeys: ['rhr'] },
  sleep_hours: { label: 'Sleep duration', series: 'sleep_hours', ln: false, unit: 'h', breakKeys: ['sleep_hours'] },
  deep_rem_min: { label: 'Deep + REM sleep', series: 'deep_rem_min', ln: false, unit: 'min', breakKeys: ['deep_min', 'rem_min'] },
  energy: { label: 'Energy', series: 'energy', ln: false, unit: 'points', breakKeys: [] },
  mood: { label: 'Mood', series: 'mood', ln: false, unit: 'points', breakKeys: [] },
  stress: { label: 'Stress', series: 'stress', ln: false, unit: 'points', breakKeys: [] },
  soreness: { label: 'Soreness', series: 'soreness', ln: false, unit: 'points', breakKeys: [] },
}

export const MIN_PHASE_DAYS = 14

export interface Phase {
  label: 'A' | 'B'
  start: string
  end: string
}

export interface Protocol {
  design: 'AB' | 'ABAB'
  phases: Phase[]
  washoutDays: number
  /** Share of B-phase days with a supplement log below which the result carries a low-adherence note. */
  minAdherence: number
  excludeAlcoholDays: boolean
}

export interface ExperimentSpec {
  id: string
  name: string
  primary: MetricId
  secondary: MetricId[]
  protocol: Protocol
}

export type Verdict = 'effect_detected' | 'no_detectable_effect' | 'insufficient' | 'running'

export interface MetricResult {
  metric: MetricId
  label: string
  role: 'primary' | 'secondary'
  verdict: Verdict
  n0: number
  n1: number
  /** Means of the two phases in natural units (ms for HRV via back-transform of the log mean, otherwise as recorded). */
  mean0: number | null
  mean1: number | null
  diff: { value: number; lo: number; hi: number; unit: string } | null
  /** Smallest difference this design could detect, same unit as diff. */
  mde: number | null
  p: number | null
  pAdjusted: number | null
  /** Slope of the A phase per week, same unit as diff. */
  preTrendPerWeek: number | null
  trendWarning: boolean
  /** ABAB only: the B-minus-A difference had the same sign in both cycles. */
  replicated: boolean | null
  summary: string
  notes: string[]
}

export interface ExperimentResult {
  id: string
  name: string
  asOf: string
  stage: 'before' | 'running' | 'complete'
  adherence: { interventionDays: number; interventionLogged: number; rate: number | null; baselineLogged: number; ok: boolean | null }
  metrics: MetricResult[]
}

const r = (v: number, d = 3) => Math.round(v * 10 ** d) / 10 ** d
const dayMs = 86400000
const dayNo = (d: string) => Math.round(Date.parse(d + 'T00:00:00Z') / dayMs)

function toNatural(info: MetricInfo, v: number): number {
  return info.ln ? (Math.exp(v) - 1) * 100 : v
}

/** Phase label of each axis day (null outside the phases or inside a B-phase washout). */
export function dayLabels(dates: string[], protocol: Protocol): ('A' | 'B' | null)[] {
  return dates.map((d) => {
    for (const ph of protocol.phases) {
      if (d >= ph.start && d <= ph.end) {
        if (ph.label === 'B' && dayNo(d) - dayNo(ph.start) < protocol.washoutDays) return null
        return ph.label
      }
    }
    return null
  })
}

/** Days dropped for the alcohol rule: the day itself and the next morning. */
function alcoholMask(frame: Frame): boolean[] {
  const a = frame.series.alcohol_any ?? []
  return frame.dates.map((_, i) => a[i] === 1 || (i > 0 && a[i - 1] === 1))
}

// Lag-1 autocorrelation with the small-sample bias of the estimator removed (E[r1] is about rho - (1 + 3 rho) / n for an AR(1)
// series); left uncorrected, short phases look less dependent than they are and the test is too eager.
function lagOneRho(values: Num[]): number {
  const n = values.filter((v) => v !== null).length
  const r1 = autocorr1(values)
  return Math.max(0, Math.min(0.9, n > 4 ? r1 + (1 + 3 * r1) / n : r1))
}

const effN = (n: number, rho: number) => Math.max(2, Math.min(n, (n * (1 - rho)) / (1 + rho)))

interface Prepared {
  a: Num[]
  b: Num[]
  all: Num[]
  /** The same days before the weekday adjustment (log scale for HRV), for reporting plain phase means. */
  rawA: Num[]
  rawB: Num[]
}

/** Values of a metric split into phase A and B on the shared axis (null elsewhere), weekday-adjusted over the used days. */
function prepare(frame: Frame, labels: ('A' | 'B' | null)[], info: MetricInfo, excludeAlcohol: boolean): Prepared {
  const raw = frame.series[info.series] ?? []
  const alcohol = excludeAlcohol ? alcoholMask(frame) : frame.dates.map(() => false)
  const used: Num[] = frame.dates.map((_, i) => {
    const v = raw[i]
    if (labels[i] === null || frame.excluded[i] === true || alcohol[i] || v === null || v === undefined || !Number.isFinite(v)) return null
    if (info.ln) return v > 0 ? Math.log(v) : null
    return v
  })
  const all = weekdayDemean(frame.dates, used)
  return {
    all,
    a: all.map((v, i) => (labels[i] === 'A' ? v : null)),
    b: all.map((v, i) => (labels[i] === 'B' ? v : null)),
    rawA: used.map((v, i) => (labels[i] === 'A' ? v : null)),
    rawB: used.map((v, i) => (labels[i] === 'B' ? v : null)),
  }
}

const present = (xs: Num[]) => xs.filter((v): v is number => v !== null)

function slopePerDay(xs: Num[]): number | null {
  const pts: [number, number][] = []
  xs.forEach((v, i) => {
    if (v !== null) pts.push([i, v])
  })
  if (pts.length < 10) return null
  const mx = mean(pts.map((p) => p[0]))
  const my = mean(pts.map((p) => p[1]))
  const sxx = pts.reduce((a, p) => a + (p[0] - mx) ** 2, 0)
  return sxx === 0 ? null : pts.reduce((a, p) => a + (p[0] - mx) * (p[1] - my), 0) / sxx
}

function groupStats(a: Num[], b: Num[]) {
  const x0 = present(a)
  const x1 = present(b)
  const rho0 = lagOneRho(a)
  const rho1 = lagOneRho(b)
  const n0e = effN(x0.length, rho0)
  const n1e = effN(x1.length, rho1)
  const v0 = x0.length > 1 ? sd(x0) ** 2 : NaN
  const v1 = x1.length > 1 ? sd(x1) ** 2 : NaN
  const se = Math.sqrt(v0 / n0e + v1 / n1e)
  return { x0, x1, n0e, n1e, se, diff: mean(x1) - mean(x0) }
}

function signedText(v: number, unit: string): string {
  const d = unit === '%' || unit === 'min' ? 1 : 2
  return `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(d)}${unit === '%' ? '%' : ' ' + unit}`
}

/** Analyse one registered experiment on the daily frame. `taken` = dates with a supplement log (from the real log). */
export function analyseExperiment(spec: ExperimentSpec, frame: Frame, taken: Set<string>, today: string): ExperimentResult {
  const { protocol } = spec
  const labels = dayLabels(frame.dates, protocol)
  const first = protocol.phases[0]?.start ?? today
  const last = protocol.phases[protocol.phases.length - 1]?.end ?? today
  const stage: ExperimentResult['stage'] = today < first ? 'before' : today <= last ? 'running' : 'complete'

  const bDays = frame.dates.filter((d, i) => labels[i] === 'B' && d <= today)
  const bLogged = bDays.filter((d) => taken.has(d)).length
  const aLogged = frame.dates.filter((d, i) => labels[i] === 'A' && taken.has(d)).length
  const rate = bDays.length ? bLogged / bDays.length : null
  const adherence = { interventionDays: bDays.length, interventionLogged: bLogged, rate: rate === null ? null : r(rate), baselineLogged: aLogged, ok: rate === null ? null : rate >= protocol.minAdherence }

  const metrics: MetricResult[] = []
  const wanted: { id: MetricId; role: 'primary' | 'secondary' }[] = [{ id: spec.primary, role: 'primary' }, ...spec.secondary.map((id) => ({ id, role: 'secondary' as const }))]
  for (const { id, role } of wanted) {
    const info = METRIC_INFO[id]
    const prep = prepare(frame, labels, info, protocol.excludeAlcoholDays)
    const g = groupStats(prep.a, prep.b)
    const n0 = g.x0.length
    const n1 = g.x1.length
    const notes: string[] = []
    const base: MetricResult = {
      metric: id, label: info.label, role, verdict: 'insufficient', n0, n1, mean0: null, mean1: null, diff: null, mde: null, p: null, pAdjusted: null,
      preTrendPerWeek: null, trendWarning: false, replicated: null, summary: '', notes,
    }
    if (n0 < MIN_PHASE_DAYS || n1 < MIN_PHASE_DAYS || !Number.isFinite(g.se) || g.se === 0) {
      base.summary = `Not enough usable days yet: ${n0} without and ${n1} with the supplement, at least ${MIN_PHASE_DAYS} of each are needed.`
      metrics.push(base)
      continue
    }
    const df = Math.max(2, g.n0e + g.n1e - 2)
    const t = g.diff / g.se
    const p = pFromT(t, df)
    const half = tCritical(df) * g.se
    const mdeRaw = (tCritical(df) + 0.85) * g.se // 80 % power at the 5 % level, with the small-sample t critical value
    const phaseMean = (xs: Num[]) => (info.ln ? Math.exp(mean(present(xs))) : mean(present(xs)))
    base.mean0 = r(phaseMean(prep.rawA), 2)
    base.mean1 = r(phaseMean(prep.rawB), 2)
    base.diff = { value: r(toNatural(info, g.diff)), lo: r(toNatural(info, g.diff - half)), hi: r(toNatural(info, g.diff + half)), unit: info.unit }
    base.mde = r(Math.abs(toNatural(info, mdeRaw)))
    base.p = r(p, 5)

    // a drifting baseline can masquerade as an effect
    const slope = slopePerDay(prep.a)
    if (slope !== null) {
      base.preTrendPerWeek = r(toNatural(info, slope * 7))
      const aDates = frame.dates.filter((_, i) => labels[i] === 'A')
      const bDates = frame.dates.filter((_, i) => labels[i] === 'B')
      const gap = dayNo(bDates[Math.floor(bDates.length / 2)]) - dayNo(aDates[Math.floor(aDates.length / 2)])
      const explained = slope * gap
      if (Math.sign(explained) === Math.sign(g.diff) && Math.abs(explained) >= 0.5 * Math.abs(g.diff)) {
        base.trendWarning = true
        notes.push('The without-phase was already drifting in the same direction; part of this difference may be a trend, not the supplement.')
      }
    }

    // ABAB: did the second cycle agree with the first?
    if (protocol.design === 'ABAB') {
      const cycleDiffs: number[] = []
      for (let k = 0; k + 1 < protocol.phases.length; k += 2) {
        const pa = protocol.phases[k]
        const pb = protocol.phases[k + 1]
        const inRange = (ph: Phase) => prep.all.map((v, i) => (frame.dates[i] >= ph.start && frame.dates[i] <= ph.end && labels[i] === ph.label ? v : null))
        const xa = present(inRange(pa))
        const xb = present(inRange(pb))
        if (xa.length >= 5 && xb.length >= 5) cycleDiffs.push(mean(xb) - mean(xa))
      }
      if (cycleDiffs.length === 2) {
        base.replicated = Math.sign(cycleDiffs[0]) === Math.sign(cycleDiffs[1]) && Math.sign(cycleDiffs[0]) === Math.sign(g.diff)
        if (!base.replicated) notes.push('The two with/without cycles disagree in direction, so this is not a replicated effect.')
      }
    }
    if (rate !== null && rate < protocol.minAdherence) notes.push(`The supplement was logged on only ${Math.round(rate * 100)}% of with-phase days (registered minimum ${Math.round(protocol.minAdherence * 100)}%); analysed as registered, by phase.`)
    if (aLogged > 0) notes.push(`It was also logged on ${aLogged} without-phase day(s), which dilutes the difference.`)

    if (stage !== 'complete') {
      base.verdict = 'running'
      base.summary = `Interim, not a result: so far ${signedText(base.diff.value, info.unit)} with the supplement (${n0} without, ${n1} with). The registered analysis runs when the last phase ends.`
    } else {
      base.verdict = base.p < 0.05 ? 'effect_detected' : 'no_detectable_effect'
      base.summary = base.verdict === 'effect_detected'
        ? `${info.label} ${signedText(base.diff.value, info.unit)} with the supplement (95% interval ${signedText(base.diff.lo, info.unit)} to ${signedText(base.diff.hi, info.unit)}), ${n0} days without and ${n1} with.`
        : `No detectable effect on ${info.label.toLowerCase()}: the estimate was ${signedText(base.diff.value, info.unit)} (95% interval ${signedText(base.diff.lo, info.unit)} to ${signedText(base.diff.hi, info.unit)}), and this design could only have seen a difference larger than ${base.mde.toFixed(info.unit === '%' ? 1 : 2)} ${info.unit === '%' ? '%' : info.unit}.`
    }
    metrics.push(base)
  }

  // the registered primary needs no correction; the (at most three) secondaries are corrected together
  const sec = metrics.filter((m) => m.role === 'secondary' && m.p !== null)
  if (sec.length > 0) {
    const adj = benjaminiHochberg(sec.map((m) => m.p as number))
    sec.forEach((m, i) => {
      m.pAdjusted = r(adj[i], 5)
      if (m.verdict === 'effect_detected' && adj[i] >= 0.05) {
        m.verdict = 'no_detectable_effect'
        m.notes.push('Not distinguishable from noise after correcting across the secondary outcomes.')
        m.summary = `No detectable effect on ${m.label.toLowerCase()} after correcting across the secondary outcomes (estimate ${signedText(m.diff!.value, m.diff!.unit)}).`
      }
    })
  }
  return { id: spec.id, name: spec.name, asOf: today, stage, adherence, metrics }
}

// ---- Pre-registration preview: what could this design detect on your own data? ----

export interface MdePreview {
  metric: MetricId
  label: string
  unit: string
  /** Day-to-day standard deviation of the weekday-adjusted metric (log units for HRV). */
  sigma: number
  rho: number
  usableDays: number
  /** Smallest difference between the two phases detectable at 80 % power, 5 % level, natural units. */
  mde: number
  /** The metric's own series had a regime change inside the window used; noise is understated or overstated. */
  unsettled: boolean
}

export const PREVIEW_WINDOW_DAYS = 120

// The same rule the analysis uses: 80 % power at the 5 % level with the small-sample t critical value.
function mdeFor(info: MetricInfo, sigma: number, rho: number, nA: number, nB: number): number {
  const nAe = effN(nA, rho)
  const nBe = effN(nB, rho)
  const se = sigma * Math.sqrt(1 / nAe + 1 / nBe)
  const df = Math.max(2, nAe + nBe - 2)
  return Math.abs(toNatural(info, (tCritical(df) + 0.85) * se))
}

/** Smallest detectable difference for planned phase lengths, from the metric's recent day-to-day variability. */
export function previewMde(frame: Frame, metrics: MetricId[], plannedA: number, plannedB: number, breakMetrics: string[] = []): MdePreview[] {
  const out: MdePreview[] = []
  const n = frame.dates.length
  const from = Math.max(0, n - PREVIEW_WINDOW_DAYS)
  for (const id of metrics) {
    const info = METRIC_INFO[id]
    const raw = frame.series[info.series] ?? []
    const vals: Num[] = frame.dates.map((_, i) => {
      const v = raw[i]
      if (i < from || frame.excluded[i] === true || v === null || v === undefined || !Number.isFinite(v)) return null
      return info.ln ? (v > 0 ? Math.log(v) : null) : v
    })
    const adj = weekdayDemean(frame.dates, vals)
    const xs = present(adj)
    if (xs.length < 30) continue
    const sigma = sd(xs)
    const rho = lagOneRho(adj)
    out.push({
      metric: id, label: info.label, unit: info.unit, sigma: r(sigma, 4), rho: r(rho, 2), usableDays: xs.length,
      mde: r(mdeFor(info, sigma, rho, plannedA, plannedB)), unsettled: info.breakKeys.some((k) => breakMetrics.includes(k)),
    })
  }
  return out
}

/** Days per phase (equal phases) needed for the design to detect a difference of `delta` (natural units; percent for HRV). */
export function daysPerPhaseFor(preview: MdePreview, delta: number): number {
  const info = METRIC_INFO[preview.metric]
  for (let n = MIN_PHASE_DAYS; n <= 730; n++) if (mdeFor(info, preview.sigma, preview.rho, n, n) <= delta) return n
  return 730
}
