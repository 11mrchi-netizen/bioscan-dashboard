// Analysis Layer 3 / Phase 3, rebuilt in analysis review phase D: the confirmatory relationship engine. Erasable
// TypeScript, no I/O.
//
// A fixed, versioned catalogue of questions (hypotheses.ts) is tested once each with one regression per question:
//   outcome(day t) = b * exposure(day t - lag) + c * outcome before the exposure + d * the exposure before that
//                    + weekday + slow trend + error
//   * the outcome before the exposure (the morning before an evening drink, the morning before a night's sleep) takes out
//     the state the person was already in, and with it most of the day-to-day autocorrelation; the exposure behind that
//     earlier outcome is adjusted for too, so a repeating exposure does not lose part of its own effect through it;
//   * weekday indicators and a linear-spline trend (a knot every 60 days) stop a weekly schedule or a slow drift from
//     reading as an effect;
//   * standard errors are Newey-West in calendar days (regression.ts), so neighbouring days are not counted as
//     independent.
// The interval, the verdict and the smallest detectable effect all come from that one fit (they used to come from a
// bootstrap, a permutation test and an analytic formula, and could disagree). The verdict compares the 95% interval with
// the question's no-effect zone, set in the catalogue before any data is seen:
//   CLEAR       the interval lies entirely outside the zone: there is an effect and it is big enough to matter;
//   NEGLIGIBLE  the interval lies entirely inside the zone: any effect is too small to matter, which is an answer;
//   UNCLEAR     the interval crosses an edge of the zone (with roughly how many more days would settle it);
// (since phase E the verdict's interval is a repeated confidence interval, wider while information is short, because
// every question is re-checked every night; the published 95% interval stays the calibrated one);
//   NOT YET     too few days, or too few with and without the exposure, to test.
// Two checks travel with every answer: the E-value (how strong a hidden factor would have to be to explain it away;
// VanderWeele & Ding 2017) and a negative-control lag (the same model on the outcome measured before the exposure,
// where it should find nothing). Words follow the evidence ladder's language contract: an association "goes with" or
// "followed", it never "lowers" or "causes".
// Days that are missing stay missing (never zero) except where a question states its exposure model explicitly.
import { fnv1a, hashSeries } from './hash.ts'
import { partialPool, probabilityBeyondZone } from './pooling.ts'
import { coefficient, olsHac, trendColumns, weekdayColumns } from './regression.ts'
import type { OlsFit } from './regression.ts'
import { benjaminiHochberg, sd, weekdayDemean } from './stats.ts'
import type { Num } from './stats.ts'

export type Sign = -1 | 0 | 1

export interface Hypothesis {
  id: string
  /** The question in words, for the row title ("Alcohol and next-morning HRV"). */
  label: string
  /** Name of the cause series in the frame. */
  cause: string
  /** Name of the target series in the frame. */
  target: string
  /** The target day is the cause day plus `lag`. */
  lag: number
  exposure: 'binary' | 'continuous'
  /** ln-transform the target (HRV): the effect is then reported as a percentage change. */
  targetTransform?: 'ln'
  /** Divide the cause by this before analysis (steps / 1000). */
  causeScale?: number
  /** Multiply the effect by this for display (hours to minutes: 60). */
  targetScale?: number
  causeText: string
  targetText: string
  /** Unit of the target when not ln-transformed, after targetScale. */
  targetUnit: string
  expectedSign: Sign
  /** How days without a record are treated, stated on the card. */
  exposureModel: string
  /** Half-width of the no-effect zone, in the effect's display unit (% for ln targets). Fixed before any data. */
  noEffect: number
  /** The outcome group the evidence board files the row under. */
  outcome: 'hrv' | 'rhr' | 'sleep' | 'energy'
  /** Ordinal target (0-10 scores): analysed as a number, said in points. */
  ordinal?: boolean
}

export interface Frame {
  /** Consecutive ISO dates. */
  dates: string[]
  series: Record<string, Num[]>
  /** True for days to leave out (illness). */
  excluded: boolean[]
  /** How the exposures were built (analysis review phase B); absent on frames built by tests or older callers. */
  capture?: FrameCapture
}

export interface FrameCapture {
  /** First evening with a check-in; before it, a day with a food log still counts as a zero (the legacy rule). */
  firstCheckin: string | null
  /** Share of the last 28 days whose alcohol and afternoon-caffeine status is known (answered or logged). */
  knownShare28: { alcohol: number; caffeine_pm: number }
  /** True when local dates and hours came from the phone's recorded UTC offset rather than UTC. */
  localTime: boolean
}

export type Verdict = 'clear' | 'negligible' | 'unclear' | 'not_yet'

export interface Effect {
  value: number
  lo: number
  hi: number
  unit: string
}

export interface RelationshipOutcome {
  id: string
  label: string
  outcome: Hypothesis['outcome']
  state: Verdict
  /** Evidence ladder rung: a catalogue answer from observational data is at most "associated". */
  rung: 'associated'
  /** Paired days used. */
  n: number
  nOn: number | null
  nOff: number | null
  reason: string | null
  effect: Effect | null
  /** The no-effect zone is [-noEffect, +noEffect] in the effect's unit. */
  noEffect: number
  /** Smallest effect detectable with 80% power at 5% two-sided, same unit as `effect`. */
  mde: number | null
  /** The interval the verdict uses: wider than `effect`'s while information is short, because the question is re-checked
   *  every night (repeated confidence interval). `boundary` is its critical value, `information` the fraction t. */
  verdictInterval: { lo: number; hi: number; boundary: number; information: number } | null
  p: number | null
  /** Benjamini-Hochberg across the questions tested tonight; reported, not used by the verdict. */
  pAdjusted: number | null
  direction: 'positive' | 'negative' | null
  signMatchesPrior: boolean | null
  /** How strong (risk-ratio scale) an unmeasured factor would need to be to explain the estimate, and the interval's edge. */
  eValue: { point: number; ci: number } | null
  /** The same model on the outcome before the exposure; an interval clear of zero is a warning sign. */
  negativeControl: (Effect & { clear: boolean }) | null
  /** Progress toward an answer: days (or days with the exposure) available against roughly how many would settle it. */
  readiness: { have: number; need: number; unit: 'days with it' | 'days' } | null
  adjustments: string[]
  /** Estimate and standard error in model units (log for HRV), for pooling and the ledger. */
  model: { estimate: number; se: number } | null
  /** Partial pooling with the family's other questions (same exposure); reported next to the verdict, never instead. */
  pooled: { family: string; members: number; value: number; lo: number; hi: number; unit: string; shrinkage: number; probabilityBeyondZone: number } | null
  summary: string
  notes: string[]
  inputsHash: string
}

export const MIN_PAIRS = 60
export const MIN_GROUP = 12
export const ALPHA = 0.05
// Analysis review phase E: every question is re-checked every night, so the verdict uses a repeated confidence interval
// (Jennison & Turnbull) with an O'Brien-Fleming-type boundary for continuous monitoring, c(t) = 2.24 / sqrt(t) (Lan &
// DeMets 1983), where t is the information so far against a full year: 60 days with a yes/no exposure, or 300 days for
// a continuous one. Early on only a very large effect can read CLEAR; the 95% interval shown stays the calibrated one.
export const SEQUENTIAL = { c: 2.24, fullExposed: 60, fullDays: 300 }
const Z80 = 0.8416
export const ADJUSTMENTS = ['weekday', 'slow trend', 'the outcome before the exposure and the exposure before that']

const round = (v: number, d = 4) => (Number.isFinite(v) ? Math.round(v * 10 ** d) / 10 ** d : NaN)

function emptyOutcome(h: Hypothesis, inputsHash: string): RelationshipOutcome {
  return {
    id: h.id, label: h.label, outcome: h.outcome, state: 'not_yet', rung: 'associated', n: 0, nOn: null, nOff: null, reason: null,
    effect: null, noEffect: h.noEffect, mde: null, verdictInterval: null, p: null, pAdjusted: null, direction: null, signMatchesPrior: null, eValue: null,
    negativeControl: null, readiness: null, adjustments: ADJUSTMENTS, model: null, pooled: null, summary: '', notes: [], inputsHash,
  }
}

function prepared(h: Hypothesis, frame: Frame): { cause: Num[]; target: Num[] } {
  const rawCause = frame.series[h.cause] ?? []
  const rawTarget = frame.series[h.target] ?? []
  const n = frame.dates.length
  const cause: Num[] = []
  const target: Num[] = []
  for (let i = 0; i < n; i++) {
    const out = frame.excluded[i] === true
    const c = rawCause[i] ?? null
    const t = rawTarget[i] ?? null
    cause.push(out || c === null || !Number.isFinite(c) ? null : c / (h.causeScale ?? 1))
    target.push(out || t === null || !Number.isFinite(t) || (h.targetTransform === 'ln' && t <= 0) ? null : h.targetTransform === 'ln' ? Math.log(t) : t)
  }
  return { cause, target }
}

const toNatural = (h: Hypothesis, b: number) => (h.targetTransform === 'ln' ? (Math.exp(b) - 1) * 100 : b * (h.targetScale ?? 1))
const unitOf = (h: Hypothesis) => (h.targetTransform === 'ln' ? '%' : h.targetUnit)
/** The zone's half-width in model units (the narrower side for logs). */
const zoneInModel = (h: Hypothesis) => (h.targetTransform === 'ln' ? Math.log(1 + h.noEffect / 100) : h.noEffect / (h.targetScale ?? 1))

interface Design {
  rows: number[][]
  y: number[]
  days: number[]
  x: number[]
  /** Index of the exposure column in each row. */
  xCol: number
}

// Rows: days t with the outcome, the exposure on t - lag and an outcome measured before the exposure (the latest of the
// three days before it). Columns: intercept, exposure, earlier outcome, weekday indicators, trend.
function buildDesign(cause: Num[], target: Num[], dates: string[], lag: number, withExposure = true): Design {
  const rowsRaw: { t: number; x: number; pre: number; far: number; prevX: number | null }[] = []
  const back = Math.max(lag, 1)
  for (let t = 0; t < dates.length; t++) {
    const y = target[t]
    const xi = t - lag
    if (y === null || xi < 0 || xi >= dates.length) continue
    const x = cause[xi]
    if (x === null) continue
    let pre: number | null = null
    let far = 0
    let preIndex = -1
    for (let k = t - back; k >= Math.max(0, t - back - 2); k--) {
      if (target[k] !== null) { pre = target[k] as number; far = k < t - back ? 1 : 0; preIndex = k; break }
    }
    if (pre === null) continue
    // The exposure behind that earlier outcome: without it, an exposure that repeats (sleep, weekend drinking) would
    // have part of its own effect taken out through the earlier outcome.
    const prevX = preIndex - lag >= 0 ? cause[preIndex - lag] : null
    rowsRaw.push({ t, x, pre, far, prevX })
  }
  const first = rowsRaw[0]?.t ?? 0
  const last = rowsRaw[rowsRaw.length - 1]?.t ?? 0
  const preMean = rowsRaw.reduce((a, r) => a + r.pre, 0) / Math.max(1, rowsRaw.length)
  // An earlier outcome from two or three days back says less about today than yesterday's would: it gets its own slope.
  const anyFar = rowsRaw.some((r) => r.far === 1) && rowsRaw.some((r) => r.far === 0)
  const known = rowsRaw.filter((r) => r.prevX !== null)
  const prevMean = known.reduce((a, r) => a + (r.prevX as number), 0) / Math.max(1, known.length)
  const anyUnknownPrev = known.length < rowsRaw.length && known.length > 0
  const usePrev = known.length > 0
  const rows = rowsRaw.map((r) => [
    1, ...(withExposure ? [r.x] : []), r.pre - preMean, ...(anyFar ? [r.far, r.far * (r.pre - preMean)] : []),
    ...(usePrev ? [r.prevX === null ? 0 : r.prevX - prevMean] : []), ...(anyUnknownPrev ? [r.prevX === null ? 1 : 0] : []),
    ...weekdayColumns(dates[r.t]), ...trendColumns(r.t, first, last),
  ])
  return { rows, y: rowsRaw.map((r) => target[r.t] as number), days: rowsRaw.map((r) => r.t), x: rowsRaw.map((r) => r.x), xCol: 1 }
}

// The exposure's variation that is left after the weekday: a fixed weekly schedule has none.
function residualExposureSd(design: Design, dates: string[]): number {
  const demeaned = weekdayDemean(design.days.map((t) => dates[t]), design.x)
  return sd(demeaned.filter((v): v is number => v !== null))
}

function eValueOf(estimate: number, lo: number, hi: number, sdY: number): { point: number; ci: number } | null {
  if (!(sdY > 0)) return null
  const ev = (d: number) => {
    const rr = Math.exp(0.91 * Math.abs(d))
    return rr + Math.sqrt(rr * (rr - 1))
  }
  const ciEdge = lo > 0 ? lo : hi < 0 ? -hi : 0
  return { point: round(ev(estimate / sdY), 2), ci: round(ciEdge === 0 ? 1 : ev(ciEdge / sdY), 2) }
}

function verdictOf(lo: number, hi: number, zone: number): Verdict {
  if (lo > zone || hi < -zone) return 'clear'
  if (lo >= -zone && hi <= zone) return 'negligible'
  return 'unclear'
}

// Roughly how many days (with the exposure, for a yes/no exposure) would make the verdict's interval no wider than the
// zone, assuming the same mix of days keeps arriving (the boundary is SEQUENTIAL.c once information is full).
function readinessFor(h: Hypothesis, se: number, have: number): number {
  const target = zoneInModel(h) / SEQUENTIAL.c
  return Math.max(have, Math.ceil(have * (se / target) ** 2))
}

function notYet(h: Hypothesis, hash: string, n: number, reason: string, nOn: number | null, nOff: number | null, readiness: RelationshipOutcome['readiness']): RelationshipOutcome {
  const o = { ...emptyOutcome(h, hash), n, nOn, nOff, reason, readiness }
  o.summary = summarise(h, o)
  return o
}

// Settling estimate before a question can be tested at all: the outcome's spread after the adjustments, and the share
// of days with the exposure so far (one in five if there are none yet).
function readinessBeforeTest(h: Hypothesis, cause: Num[], target: Num[], dates: string[], nOn: number | null, n: number): RelationshipOutcome['readiness'] {
  const base = buildDesign(cause, target, dates, h.lag, false)
  const fit = base.rows.length > 15 ? olsHac(base.rows, base.y, base.days) : null
  if (!fit) return h.exposure === 'binary' ? { have: nOn ?? 0, need: MIN_GROUP, unit: 'days with it' } : { have: n, need: MIN_PAIRS, unit: 'days' }
  const target95 = zoneInModel(h) / SEQUENTIAL.c
  if (h.exposure === 'binary') {
    const known = cause.filter((v) => v !== null) as number[]
    const share = known.length > 0 && known.some((v) => v > 0) ? known.filter((v) => v > 0).length / known.length : 0.2
    const need = Math.ceil((fit.sigma / target95) ** 2 / Math.max(0.05, 1 - share))
    return { have: nOn ?? 0, need: Math.max(MIN_GROUP, need), unit: 'days with it' }
  }
  const xs = cause.filter((v) => v !== null) as number[]
  const sx = sd(xs)
  const need = sx > 0 ? Math.ceil((fit.sigma / (sx * target95)) ** 2) : MIN_PAIRS
  return { have: n, need: Math.max(MIN_PAIRS, need), unit: 'days' }
}

function effectFrom(h: Hypothesis, fit: OlsFit, j: number): { est: number; lo: number; hi: number; se: number; p: number; effect: Effect } {
  const c = coefficient(fit, j)
  return {
    est: c.estimate, lo: c.lo, hi: c.hi, se: c.se, p: c.p,
    effect: { value: round(toNatural(h, c.estimate)), lo: round(toNatural(h, c.lo)), hi: round(toNatural(h, c.hi)), unit: unitOf(h) },
  }
}

/** Test one question. The returned outcome is final except for pAdjusted (runCatalogue). */
export function analyseHypothesis(h: Hypothesis, frame: Frame): RelationshipOutcome {
  const { cause, target } = prepared(h, frame)
  const hash = fnv1a(`${h.id}|${h.noEffect}|${hashSeries(frame.dates, cause)}|${hashSeries(frame.dates, target)}`)
  const design = buildDesign(cause, target, frame.dates, h.lag)
  const n = design.y.length
  let nOn: number | null = null
  let nOff: number | null = null
  if (h.exposure === 'binary') {
    nOn = design.x.filter((v) => v > 0).length
    nOff = n - nOn
  }
  if (n < MIN_PAIRS) {
    return notYet(h, hash, n, `${n} paired days, ${MIN_PAIRS} needed`, nOn, nOff, readinessBeforeTest(h, cause, target, frame.dates, nOn, n))
  }
  if (h.exposure === 'binary' && ((nOn as number) < MIN_GROUP || (nOff as number) < MIN_GROUP)) {
    return notYet(h, hash, n, `${nOn} days with it and ${nOff} without, at least ${MIN_GROUP} of each are needed`, nOn, nOff, readinessBeforeTest(h, cause, target, frame.dates, nOn, n))
  }
  if (residualExposureSd(design, frame.dates) < 1e-9) {
    return notYet(h, hash, n, 'no variation left after adjusting for day of week (it follows a fixed weekly schedule)', nOn, nOff, null)
  }
  if (sd(design.y) < 1e-12) return notYet(h, hash, n, 'the outcome does not vary', nOn, nOff, null)
  const fit = olsHac(design.rows, design.y, design.days)
  if (!fit) return notYet(h, hash, n, 'the adjustments leave too little to estimate (too few distinct days)', nOn, nOff, null)

  const e = effectFrom(h, fit, design.xCol)
  const zone = h.noEffect
  // The verdict's interval: the same estimate, widened by the sequential boundary while information is short.
  const information = Math.min(1, h.exposure === 'binary' ? (nOn as number) / SEQUENTIAL.fullExposed : n / SEQUENTIAL.fullDays)
  const tc = coefficient(fit, design.xCol).tc
  const boundary = Math.max(tc, SEQUENTIAL.c / Math.sqrt(information))
  const vLo = round(toNatural(h, e.est - boundary * e.se))
  const vHi = round(toNatural(h, e.est + boundary * e.se))
  const verdictInterval = { lo: vLo, hi: vHi, boundary: round(boundary, 3), information: round(information, 3) }
  const state = verdictOf(vLo, vHi, zone)
  const mde = round(Math.abs(toNatural(h, (tc + Z80) * e.se)))
  const direction = e.est === 0 ? null : e.est > 0 ? 'positive' : 'negative'
  const signMatchesPrior = h.expectedSign === 0 || direction === null ? null : (e.est > 0 ? 1 : -1) === h.expectedSign

  // Negative control: the outcome measured before the exposure.
  let negativeControl: RelationshipOutcome['negativeControl'] = null
  const nc = buildDesign(cause, target, frame.dates, h.lag - 1)
  if (nc.y.length >= MIN_PAIRS && residualExposureSd(nc, frame.dates) > 1e-9) {
    const ncFit = olsHac(nc.rows, nc.y, nc.days)
    if (ncFit) {
      const c = effectFrom(h, ncFit, nc.xCol)
      negativeControl = { ...c.effect, clear: c.lo > 0 || c.hi < 0 }
    }
  }
  const sdY = sd(weekdayDemean(design.days.map((t) => frame.dates[t]), design.y).filter((v): v is number => v !== null))
  const eValue = eValueOf(e.est, e.lo, e.hi, sdY)
  const have = h.exposure === 'binary' ? (nOn as number) : n
  const readiness = state === 'unclear' ? { have, need: readinessFor(h, e.se, have), unit: h.exposure === 'binary' ? 'days with it' as const : 'days' as const } : null

  const notes = [`Adjusted for ${ADJUSTMENTS.join(', ')}. ${h.exposureModel}`]
  const causeRawSd = sd(design.x)
  const left = residualExposureSd(design, frame.dates)
  if (causeRawSd > 0 && 1 - left ** 2 / causeRawSd ** 2 > 0.6) {
    notes.push('The exposure mostly follows a weekly pattern, so this compares only the variation within the same weekday.')
  }
  if (signMatchesPrior === false && state === 'clear') notes.push('The direction is opposite to what is usually expected; treat it with extra caution.')
  if (negativeControl?.clear) notes.push('Warning: the same model finds a difference before the exposure, so something else may drive both.')

  const o: RelationshipOutcome = {
    ...emptyOutcome(h, hash),
    state, n, nOn, nOff, effect: e.effect, mde, verdictInterval, p: round(e.p, 5), direction, signMatchesPrior, eValue, negativeControl, readiness, notes,
    model: { estimate: round(e.est, 6), se: round(e.se, 6) },
  }
  o.summary = summarise(h, o)
  return o
}

const signed = (v: number, d = 1) => `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(d)}`
const fmt = (v: number, u: string) => (u === '%' ? `${signed(v)}%` : `${signed(v, u === 'h' || u === 'points' ? 2 : u === 'bpm' ? 1 : 0)} ${u}`)
const days = (h: Hypothesis, o: RelationshipOutcome) =>
  h.exposure === 'binary' ? `${o.nOn} days with it, ${o.nOff} without` : `${o.n} days`

// Words from the evidence ladder's language contract: "went with", "followed"; never "lowers", "raises" or "causes".
function summarise(h: Hypothesis, o: RelationshipOutcome): string {
  if (o.state === 'not_yet') {
    const r = o.readiness
    const progress = r ? ` ${r.have} of about ${r.need} ${r.unit}.` : ''
    return `Not yet: ${o.reason}.${progress}`
  }
  const e = o.effect as Effect
  const interval = `95% interval ${fmt(e.lo, e.unit)} to ${fmt(e.hi, e.unit)}`
  const zone = e.unit === '%' ? `±${h.noEffect}%` : `±${h.noEffect} ${e.unit}`
  const what = `${h.causeText} went with ${h.targetText} ${fmt(e.value, e.unit)}`
  if (o.state === 'clear') return `${what} (${interval}; ${days(h, o)}), clear of the ${zone} no-effect zone.`
  if (o.state === 'negligible') return `No meaningful difference: ${what} (${interval}; ${days(h, o)}), inside the ${zone} no-effect zone.`
  const more = o.readiness ? ` About ${Math.max(0, o.readiness.need - o.readiness.have)} more ${o.readiness.unit} would settle it.` : ''
  const v = o.verdictInterval
  if (v && (e.lo > h.noEffect || e.hi < -h.noEffect)) {
    return `Unclear so far: ${what} (${interval}; ${days(h, o)}). Because this is re-checked every night, a clear answer needs the wider interval ${fmt(v.lo, e.unit)} to ${fmt(v.hi, e.unit)} to clear the ${zone} zone too.${more}`
  }
  return `Unclear: ${what} (${interval}; ${days(h, o)}), which crosses the edge of the ${zone} no-effect zone.${more}`
}

// The favourable direction of each outcome group: higher HRV, lower resting heart rate, more sleep, more energy.
const FAVOURABLE: Record<Hypothesis['outcome'], 1 | -1> = { hrv: 1, rhr: -1, sleep: 1, energy: 1 }

/** Partial pooling within each family of questions about the same exposure (pooling.ts). Mutates `pooled`. */
export function poolFamilies(hypotheses: Hypothesis[], outcomes: RelationshipOutcome[]): void {
  const families = new Map<string, number[]>()
  outcomes.forEach((o, i) => {
    if (o.state === 'not_yet' || !o.model) return
    const key = hypotheses[i].cause
    families.set(key, [...(families.get(key) ?? []), i])
  })
  for (const [family, idx] of families) {
    if (idx.length < 2) continue
    const inputs = idx.map((i) => {
      const h = hypotheses[i]
      const z = zoneInModel(h)
      const m = outcomes[i].model as { estimate: number; se: number }
      return { id: String(i), estimate: (FAVOURABLE[h.outcome] * m.estimate) / z, se: m.se / z }
    })
    const pooled = partialPool(inputs)
    if (!pooled) continue
    for (const member of pooled.members) {
      const i = Number(member.id)
      const h = hypotheses[i]
      const z = zoneInModel(h)
      const back = (v: number) => toNatural(h, FAVOURABLE[h.outcome] * v * z)
      const a = back(member.mean - 1.96 * member.sd)
      const b = back(member.mean + 1.96 * member.sd)
      outcomes[i].pooled = {
        family, members: idx.length, value: round(back(member.mean)), lo: round(Math.min(a, b)), hi: round(Math.max(a, b)),
        unit: unitOf(h), shrinkage: round(member.shrinkage, 3), probabilityBeyondZone: round(probabilityBeyondZone(member.mean, member.sd), 3),
      }
    }
  }
}

/** Test every question, then report the Benjamini-Hochberg adjustment across the ones that could be tested. */
export function runCatalogue(hypotheses: Hypothesis[], frame: Frame): RelationshipOutcome[] {
  const outcomes = hypotheses.map((h) => analyseHypothesis(h, frame))
  poolFamilies(hypotheses, outcomes)
  const tested = outcomes.filter((o) => o.state !== 'not_yet' && o.p !== null)
  const adjusted = benjaminiHochberg(tested.map((o) => o.p as number))
  tested.forEach((o, i) => {
    o.pAdjusted = round(adjusted[i], 5)
  })
  if (tested.length > 1) {
    const chance = round(tested.length * ALPHA, 1)
    tested.forEach((o) => o.notes.push(`${tested.length} questions were tested; about ${chance} of them would show an interval clear of zero by chance, which is why a clear answer must also clear the no-effect zone.`))
  }
  return outcomes
}

