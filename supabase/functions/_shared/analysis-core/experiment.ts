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
import { analyseDaily, analysePairs, FOUR_WORDS, fourWord, shiftDay } from './randomized.ts'
import type { FourWord, PeriodPhase } from './randomized.ts'

export type MetricId = 'hrv' | 'rhr' | 'sleep_hours' | 'deep_rem_min' | 'energy' | 'mood' | 'stress' | 'soreness' | 'fueling_carb_rate'
  | 'screen_bed_min' | 'screen_pm_social' | 'outdoor_daylight_min' | 'resp_rate'

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
  // Endurance Fueling (06.3.6): a derived daily series, not a raw table column -- the caller (fuelingExperiment.ts)
  // builds it from eligible, non-deviated activities' per-hour carb rate (fuelingSummary.ts), null on days with no
  // qualifying activity. This is the metric-vocabulary widening 08.5's notes flagged as future, separate work --
  // done now because 06.3.6 is a concrete, real caller, not a speculative one.
  fueling_carb_rate: { label: 'Carbohydrate rate', series: 'fueling_carb_rate', ln: false, unit: 'g/h', breakKeys: [] },
  // Routine intelligence R6: phone and mobility outcomes (routineExposures.ts), so a habit itself can be the outcome
  // (does a wind-down routine cut phone use in the hour before sleep?). Null on days the phone did not measure.
  screen_bed_min: { label: 'Phone in the hour before sleep', series: 'screen_bed_min', ln: false, unit: 'min', breakKeys: [] },
  screen_pm_social: { label: 'Social and video apps after 21:00', series: 'screen_pm_social', ln: false, unit: 'min', breakKeys: [] },
  outdoor_daylight_min: { label: 'Outdoors in daylight (estimated)', series: 'outdoor_daylight_min', ln: false, unit: 'min', breakKeys: [] },
  // Repeatable interventions (a nasal strip): the night's mean respiratory rate from sleep_daily.
  resp_rate: { label: 'Respiratory rate, sleep', series: 'resp_rate', ln: false, unit: 'br/min', breakKeys: [] },
}

export const MIN_PHASE_DAYS = 14

export interface Phase {
  label: 'A' | 'B'
  start: string
  end: string
}

export interface Protocol {
  design: 'AB' | 'ABAB' | 'randomized_pairs' | 'micro_randomized'
  phases: Phase[]
  washoutDays: number
  /** Share of B-phase days with a supplement log below which the result carries a low-adherence note. */
  minAdherence: number
  excludeAlcoholDays: boolean
  // ---- Analysis review phase F (protocol version 2): randomised designs, judged against a zone set at registration ----
  version?: 2
  /** No-effect zone of the primary outcome, natural units (% for HRV, minutes for sleep, as the outcome reports). */
  zone?: number
  /** A partner filled a weekly organiser with the real thing or a placebo, so the person did not know the schedule. */
  blinded?: boolean
  /** randomized_pairs: which pair each phase belongs to (the order inside a pair was drawn at random). */
  pairs?: number
  periodDays?: number
  /** micro_randomized: the drawn daily schedule (B = with) and how many days later the outcome is read. */
  assignments?: { date: string; arm: 'A' | 'B' }[]
  lag?: number
  /** micro_randomized: the behaviour a with-day asks for, checked against the log (e.g. no caffeine after 14:00). */
  behaviour?: { series: string; withValue: number; label: string }
  /** How the schedule was drawn (recorded at registration). */
  randomization?: { method: string; drawnAt: string }
}

export interface ExperimentSpec {
  id: string
  name: string
  primary: MetricId
  secondary: MetricId[]
  protocol: Protocol
}

export type Verdict = 'effect_detected' | 'no_detectable_effect' | 'insufficient' | 'running' | FourWord

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
  /** Phase F designs: the zone the verdict used and the exact randomisation p. */
  zone?: number | null
  randomizationP?: number | null
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

/** Analyse one registered experiment on the daily frame. `taken` = dates with a supplement log (from the real log).
 *  `frozen` = days left out of periods that have ended, as recorded when each ended (phase F), used instead of the live
 *  illness and alcohol logs for those periods. */
/** The registration defaults, for protocols stored without them: the phone's JSON encoder leaves out a field equal to
 *  its default, so a protocol registered with no washout, a 70% minimum and alcohol days excluded arrived without those
 *  three keys (and read as no exclusion and failed adherence). Explicit values always win. */
export function withProtocolDefaults(protocol: Protocol): Protocol {
  return { ...protocol, washoutDays: protocol.washoutDays ?? 0, minAdherence: protocol.minAdherence ?? 0.7, excludeAlcoholDays: protocol.excludeAlcoholDays ?? true }
}

export function analyseExperiment(spec: ExperimentSpec, frame: Frame, taken: Set<string>, today: string, frozen?: FrozenExclusions): ExperimentResult {
  spec = { ...spec, protocol: withProtocolDefaults(spec.protocol) }
  if (spec.protocol.version === 2) return analyseRandomized(spec, frame, taken, today, frozen)
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

// ---- Analysis review phase F: randomised designs (protocol version 2) ----

/** Days left out of each ended period, frozen when it ended: key = period start date. */
export type FrozenExclusions = Map<string, Set<string>>

// The favourable direction of each outcome (more HRV, lower resting heart rate, more sleep, more energy and mood, less
// stress and soreness, a steadier carb rate is neither).
const FAVOURABLE: Record<MetricId, 1 | -1> = {
  hrv: 1, rhr: -1, sleep_hours: 1, deep_rem_min: 1, energy: 1, mood: 1, stress: -1, soreness: -1, fueling_carb_rate: 1,
  // Routine intelligence R6: less phone around bedtime and late at night, more daylight.
  screen_bed_min: -1, screen_pm_social: -1, outdoor_daylight_min: 1,
  // A slower breathing rate reads as favourable (wording only; not a medical claim).
  resp_rate: -1,
}

/** Each outcome's default no-effect zone in its display unit (% for HRV, minutes for sleep), as the evidence board uses;
 *  `scale` turns the metric's own unit into the display unit. */
export const DEFAULT_ZONE: Record<MetricId, { zone: number; unit: string; scale?: number }> = {
  hrv: { zone: 2, unit: '%' }, rhr: { zone: 1, unit: 'bpm' }, sleep_hours: { zone: 10, unit: 'min', scale: 60 }, deep_rem_min: { zone: 10, unit: 'min' },
  energy: { zone: 0.5, unit: 'points' }, mood: { zone: 0.5, unit: 'points' }, stress: { zone: 0.5, unit: 'points' }, soreness: { zone: 0.5, unit: 'points' },
  fueling_carb_rate: { zone: 5, unit: 'g/h' },
  screen_bed_min: { zone: 10, unit: 'min' }, screen_pm_social: { zone: 10, unit: 'min' }, outdoor_daylight_min: { zone: 10, unit: 'min' },
  resp_rate: { zone: 0.3, unit: 'br/min' },
}

/** The blocks whose exclusions are frozen when they end: the periods, or for a daily design each week of the schedule
 *  (through the morning its last outcome is read). */
export function freezeBlocks(protocol: Protocol): { start: string; end: string }[] {
  if (protocol.design === 'micro_randomized') {
    const days = protocol.assignments ?? []
    const out: { start: string; end: string }[] = []
    for (let k = 0; k < days.length; k += 7) out.push({ start: days[k].date, end: shiftDay(days[Math.min(days.length, k + 7) - 1].date, protocol.lag ?? 1) })
    return out
  }
  return protocol.phases.map((p) => ({ start: p.start, end: p.end }))
}

/** Days to leave out on the frame: frozen sets for ended blocks, the live illness and alcohol logs otherwise. */
export function exclusionMask(frame: Frame, protocol: Protocol, frozen: FrozenExclusions | undefined): boolean[] {
  const alcohol = frame.series.alcohol_any ?? []
  const live = frame.dates.map((_, i) => frame.excluded[i] === true || (protocol.excludeAlcoholDays && (alcohol[i] === 1 || (i > 0 && alcohol[i - 1] === 1))))
  if (!frozen || frozen.size === 0) return live
  const blocks = freezeBlocks(protocol)
  return frame.dates.map((d, i) => {
    const b = blocks.find((x) => d >= x.start && d <= x.end)
    const set = b ? frozen.get(b.start) : undefined
    return set ? set.has(d) : live[i]
  })
}

function natural(info: MetricInfo, v: number, unit: string): number {
  if (info.ln) return (Math.exp(v) - 1) * 100
  return unit === 'min' && info.unit === 'h' ? v * 60 : v
}

function zoneText(zone: number, unit: string): string {
  return unit === '%' ? `±${zone}%` : `±${zone} ${unit}`
}

export function analyseRandomized(spec: ExperimentSpec, frame: Frame, taken: Set<string>, today: string, frozen?: FrozenExclusions): ExperimentResult {
  const { protocol } = spec
  const daily = protocol.design === 'micro_randomized'
  const schedule = protocol.assignments ?? []
  const first = daily ? schedule[0]?.date ?? today : protocol.phases[0]?.start ?? today
  const lastDay = daily ? shiftDay(schedule[schedule.length - 1]?.date ?? today, protocol.lag ?? 1) : protocol.phases[protocol.phases.length - 1]?.end ?? today
  const stage: ExperimentResult['stage'] = today < first ? 'before' : today <= lastDay ? 'running' : 'complete'
  const excluded = exclusionMask(frame, protocol, frozen)

  // Adherence: with-periods from the supplement log; with-days from the behaviour series (e.g. no caffeine after 14:00).
  let adherence: ExperimentResult['adherence']
  if (daily && protocol.behaviour) {
    const series = frame.series[protocol.behaviour.series] ?? []
    const idx = new Map(frame.dates.map((d, i) => [d, i]))
    const withDays = schedule.filter((a) => a.arm === 'B' && a.date <= today)
    const known = withDays.filter((a) => { const v = series[idx.get(a.date) ?? -1]; return v !== null && v !== undefined })
    const followed = known.filter((a) => series[idx.get(a.date) as number] === protocol.behaviour!.withValue).length
    const rate = known.length ? followed / known.length : null
    adherence = { interventionDays: withDays.length, interventionLogged: followed, rate: rate === null ? null : r(rate), baselineLogged: 0, ok: rate === null ? null : rate >= protocol.minAdherence }
  } else {
    const labels = dayLabels(frame.dates, protocol)
    const bDays = frame.dates.filter((d, i) => labels[i] === 'B' && d <= today)
    const bLogged = bDays.filter((d) => taken.has(d)).length
    const aLogged = frame.dates.filter((d, i) => labels[i] === 'A' && taken.has(d)).length
    const rate = bDays.length ? bLogged / bDays.length : null
    adherence = { interventionDays: bDays.length, interventionLogged: bLogged, rate: rate === null ? null : r(rate), baselineLogged: aLogged, ok: rate === null ? null : rate >= protocol.minAdherence }
  }

  const metrics: MetricResult[] = []
  const wanted: { id: MetricId; role: 'primary' | 'secondary' }[] = [{ id: spec.primary, role: 'primary' }, ...spec.secondary.map((id) => ({ id, role: 'secondary' as const }))]
  for (const { id, role } of wanted) {
    const info = METRIC_INFO[id]
    const unit = info.unit === 'h' ? 'min' : info.unit
    const raw = frame.series[info.series] ?? []
    const values: Num[] = frame.dates.map((d, i) => {
      const v = raw[i]
      if (d > today || excluded[i] || v === null || v === undefined || !Number.isFinite(v)) return null
      return info.ln ? (v > 0 ? Math.log(v) : null) : v
    })
    const notes: string[] = []
    const zone = role === 'primary' ? protocol.zone ?? null : null
    const base: MetricResult = {
      metric: id, label: info.label, role, verdict: 'insufficient', n0: 0, n1: 0, mean0: null, mean1: null, diff: null, mde: null, p: null, pAdjusted: null,
      preTrendPerWeek: null, trendWarning: false, replicated: null, summary: '', notes, zone, randomizationP: null,
    }
    let est: number | null = null
    let lo: number | null = null
    let hi: number | null = null
    let pRand: number | null = null
    let se: number | null = null
    if (daily) {
      const d = analyseDaily({ dates: frame.dates, values, assignments: schedule.filter((a) => a.date <= today), lag: protocol.lag ?? 1 }, `${spec.id}:${id}`)
      base.n0 = d.daysWithout
      base.n1 = d.daysWith
      est = d.estimate
      lo = d.lo
      hi = d.hi
      pRand = d.p
      if (lo !== null && hi !== null) se = (hi - lo) / (2 * 1.96)
    } else {
      const phases = protocol.phases.map((ph, k) => ({ ...ph, pair: (ph as Phase & { pair?: number }).pair ?? Math.floor(k / 2) })) as PeriodPhase[]
      const a = analysePairs({ dates: frame.dates, values, phases, washoutDays: protocol.washoutDays })
      base.n0 = a.daysWithout
      base.n1 = a.daysWith
      est = a.estimate
      lo = a.lo
      hi = a.hi
      pRand = a.usablePairs >= 6 ? a.p : null
      if (lo !== null && hi !== null && est !== null) se = (hi - est) / tCritical(Math.max(1, a.usablePairs - 1))
      if (a.usablePairs < a.pairs && stage === 'complete') notes.push(`${a.pairs - a.usablePairs} of ${a.pairs} pairs had too few usable days in one of their periods and were left out.`)
    }
    if (est === null || lo === null || hi === null) {
      base.summary = daily
        ? `Not enough usable days yet: ${base.n0} without and ${base.n1} with, at least 10 of each are needed.`
        : `Not enough usable periods yet: at least two complete pairs are needed.`
      metrics.push(base)
      continue
    }
    base.diff = { value: r(natural(info, est, unit)), lo: r(natural(info, lo, unit)), hi: r(natural(info, hi, unit)), unit }
    base.randomizationP = pRand === null ? null : r(pRand, 4)
    base.p = pRand === null ? null : r(pRand, 5)
    if (se !== null) base.mde = r(Math.abs(natural(info, 2.8 * se, unit)))
    if (protocol.blinded) notes.push('Blinded: a placebo filled the without-periods, so expectation should not explain a difference.')
    if (adherence.rate !== null && adherence.rate < protocol.minAdherence) notes.push(`The schedule was followed on ${Math.round(adherence.rate * 100)}% of with-days; analysed as assigned, which shrinks any effect toward zero.`)

    const words = (v: number) => `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(unit === 'min' ? 0 : 1)}${unit === '%' ? '%' : ' ' + unit}`
    const interval = `95% interval ${words(base.diff.lo)} to ${words(base.diff.hi)}`
    if (stage !== 'complete') {
      base.verdict = 'running'
      base.summary = `Interim, not a result: so far ${words(base.diff.value)} with it (${interval}). The verdict comes when the last ${daily ? 'day' : 'period'} ends.`
    } else if (zone !== null) {
      const v = fourWord({ lo: base.diff.lo, hi: base.diff.hi, zone, favourable: FAVOURABLE[id] })
      base.verdict = v
      const what = `${info.label} ${words(base.diff.value)} with it (${interval}), against a ${zoneText(zone, unit)} zone set at registration`
      base.summary = v === 'unclear' ? `Unclear: ${what}. A longer test would settle it.` : `${FOUR_WORDS[v]}: ${what}.`
    } else {
      // Secondary outcomes have no zone of their own: their intervals are reported, not judged.
      base.verdict = 'unclear'
      base.summary = `${info.label} ${words(base.diff.value)} with it (${interval}); a secondary outcome, reported without a verdict.`
    }
    metrics.push(base)
  }
  return { id: spec.id, name: spec.name, asOf: today, stage, adherence, metrics }
}

export type { FourWord }
