// Analysis Layer 3 / Phase 3: the confirmatory relationship engine. Erasable TypeScript, no I/O.
//
// What it replaces: scanning every lag of every pair and showing whatever clears |r| >= 0.1 ("Moderate link" with no
// significance requirement). Here a fixed, versioned catalogue of hypotheses is tested once each:
//   * both series are adjusted for day of week (a regression on weekday means), so a weekly schedule (training on fixed
//     days, drinking on weekends) cannot masquerade as an effect;
//   * the p-value comes from a weekly-block permutation test, which keeps autocorrelation inside a week;
//   * Benjamini-Hochberg runs across the hypotheses actually tested, not across lags;
//   * the effect is reported in natural units with a block-bootstrap interval, plus the smallest effect the data could
//     detect, so "not distinguishable from noise" says how big a real effect would have had to be;
//   * there is no "Moderate" label: a link is either supported after correction, not distinguishable, or not testable.
// Days that are missing stay missing (never zero) except where a hypothesis states its exposure model explicitly.
import { fnv1a, hashSeries } from './hash.ts'
import {
  autocorr1, benjaminiHochberg, blockBootstrapCi, blockPermutationP, effectiveN, mulberry32, pairedLag, pearson, sd, slope,
  spearman, weekdayDemean,
} from './stats.ts'
import type { Num } from './stats.ts'

export type Sign = -1 | 0 | 1

export interface Hypothesis {
  id: string
  /** The question in words, for the card title. */
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
  causeText: string
  targetText: string
  /** Unit of the target when not ln-transformed. */
  targetUnit: string
  expectedSign: Sign
  /** How days without a record are treated, stated on the card. */
  exposureModel: string
  /** Rank-based statistic for ordinal targets. */
  ordinal?: boolean
}

export interface Frame {
  /** Consecutive ISO dates. */
  dates: string[]
  series: Record<string, Num[]>
  /** True for days to leave out (illness). */
  excluded: boolean[]
}

export type RelationshipState = 'supported' | 'not_distinguishable' | 'insufficient'

export interface RelationshipOutcome {
  id: string
  label: string
  state: RelationshipState
  /** Paired days used. */
  n: number
  nOn: number | null
  nOff: number | null
  reason: string | null
  r: number | null
  effect: { value: number; lo: number; hi: number; unit: string } | null
  /** Smallest absolute effect detectable with 80 % power at 5 %, same unit as `effect`. */
  mde: number | null
  nEff: number | null
  p: number | null
  pAdjusted: number | null
  direction: 'positive' | 'negative' | null
  signMatchesPrior: boolean | null
  summary: string
  notes: string[]
  inputsHash: string
}

export const MIN_PAIRS = 30
export const MIN_GROUP = 6
export const ALPHA = 0.05

const round = (v: number, d = 4) => (Number.isFinite(v) ? Math.round(v * 10 ** d) / 10 ** d : NaN)
const seedOf = (id: string, seed: number) => parseInt(fnv1a(`${id}:${seed}`), 16)

function emptyOutcome(h: Hypothesis, inputsHash: string): RelationshipOutcome {
  return {
    id: h.id, label: h.label, state: 'insufficient', n: 0, nOn: null, nOff: null, reason: null, r: null, effect: null, mde: null,
    nEff: null, p: null, pAdjusted: null, direction: null, signMatchesPrior: null, summary: '', notes: [], inputsHash,
  }
}

function insufficient(h: Hypothesis, inputsHash: string, n: number, reason: string, nOn: number | null = null, nOff: number | null = null): RelationshipOutcome {
  return { ...emptyOutcome(h, inputsHash), n, nOn, nOff, reason, summary: `Not enough data to test: ${reason}.` }
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

const toNatural = (h: Hypothesis, b: number) => (h.targetTransform === 'ln' ? (Math.exp(b) - 1) * 100 : b)
const unitOf = (h: Hypothesis) => (h.targetTransform === 'ln' ? '%' : h.targetUnit)
const signed = (v: number, d = 1) => `${v >= 0 ? '+' : '-'}${Math.abs(v).toFixed(d)}`

/** Test one hypothesis. The returned state is 'insufficient' or provisional; runCatalogue applies the correction. */
export function analyseHypothesis(h: Hypothesis, frame: Frame, seed = 20261012, reps = 2000): RelationshipOutcome {
  const { cause, target } = prepared(h, frame)
  const hash = fnv1a(`${h.id}|${hashSeries(frame.dates, cause)}|${hashSeries(frame.dates, target)}`)

  // Adjust both series for day of week (Frisch-Waugh: the slope below is then the effect within the same weekday).
  const causeAdj = weekdayDemean(frame.dates, cause)
  const targetAdj = weekdayDemean(frame.dates, target)
  const raw = pairedLag(cause, target, h.lag)
  const pairs = pairedLag(causeAdj, targetAdj, h.lag)
  const n = pairs.x.length
  if (n < MIN_PAIRS) return insufficient(h, hash, n, `${n} paired days, ${MIN_PAIRS} needed`)

  let nOn: number | null = null
  let nOff: number | null = null
  if (h.exposure === 'binary') {
    nOn = raw.x.filter((v) => v > 0).length
    nOff = raw.x.length - nOn
    if (nOn < MIN_GROUP || nOff < MIN_GROUP) {
      return insufficient(h, hash, n, `${nOn} days with it and ${nOff} without, at least ${MIN_GROUP} of each are needed`, nOn, nOff)
    }
  }
  if (sd(pairs.x) < 1e-9) return insufficient(h, hash, n, 'no variation left after adjusting for day of week (it follows a fixed weekly schedule)', nOn, nOff)
  if (sd(pairs.y) < 1e-12) return insufficient(h, hash, n, 'the target does not vary', nOn, nOff)

  const stat = h.ordinal ? spearman : pearson
  const r = stat(pairs.x, pairs.y)
  const b = slope(pairs.x, pairs.y)
  const p = blockPermutationP(causeAdj, targetAdj, h.lag, mulberry32(seedOf(h.id, seed)), stat, reps)

  const [loB, hiB] = blockBootstrapCi(pairs.x, pairs.y, mulberry32(seedOf(h.id, seed + 1)), slope, 1000)
  const resid = pairs.y.map((y, i) => y - b * pairs.x[i])
  const nEff = effectiveN(n, autocorr1(causeAdj), autocorr1(targetAdj))
  const mdeB = (2.8 * sd(resid)) / (sd(pairs.x) * Math.sqrt(nEff))

  const effect = { value: round(toNatural(h, b)), lo: round(toNatural(h, loB)), hi: round(toNatural(h, hiB)), unit: unitOf(h) }
  const mde = round(Math.abs(toNatural(h, mdeB)))
  const direction = b === 0 ? null : b > 0 ? 'positive' : 'negative'
  const signMatchesPrior = h.expectedSign === 0 || direction === null ? null : (b > 0 ? 1 : -1) === h.expectedSign

  const notes = [`Both series are adjusted for day of week. ${h.exposureModel}`]
  const causeRawSd = sd(raw.x)
  if (causeRawSd > 0 && 1 - sd(pairs.x) ** 2 / causeRawSd ** 2 > 0.6) {
    notes.push('The cause mostly follows a weekly pattern, so this compares only the variation within the same weekday.')
  }
  if (signMatchesPrior === false) notes.push('The direction is opposite to what is usually expected; treat it with extra caution.')

  return {
    ...emptyOutcome(h, hash),
    state: 'not_distinguishable', // provisional
    n, nOn, nOff, r: round(r), effect, mde, nEff: round(nEff, 1), p: round(p, 5), direction, signMatchesPrior, notes,
  }
}

function summarise(h: Hypothesis, o: RelationshipOutcome): string {
  if (o.state === 'insufficient' || !o.effect || o.mde === null) return o.summary
  const u = o.effect.unit
  const d = u === '%' ? 1 : 2
  if (o.state === 'supported') {
    return `${h.causeText} -> ${h.targetText}: ${signed(o.effect.value, d)}${u} (95% interval ${signed(o.effect.lo, d)} to ${signed(o.effect.hi, d)}${u}), ${o.n} days, adjusted for day of week.`
  }
  return `Not distinguishable from noise (${o.n} days). Only a change in ${h.targetText} larger than ${o.mde.toFixed(d)}${u} could have been detected; the estimate was ${signed(o.effect.value, d)}${u} (95% interval ${signed(o.effect.lo, d)} to ${signed(o.effect.hi, d)}${u}).`
}

/** Test every hypothesis, then correct across the ones that could be tested. */
export function runCatalogue(hypotheses: Hypothesis[], frame: Frame, seed = 20261012, reps = 2000): RelationshipOutcome[] {
  const outcomes = hypotheses.map((h) => analyseHypothesis(h, frame, seed, reps))
  const tested = outcomes.filter((o) => o.state !== 'insufficient')
  const adjusted = benjaminiHochberg(tested.map((o) => o.p as number))
  tested.forEach((o, i) => {
    o.pAdjusted = round(adjusted[i], 5)
    o.state = adjusted[i] < ALPHA ? 'supported' : 'not_distinguishable'
    if (tested.length > 1) o.notes.push(`Corrected across ${tested.length} hypotheses tested (Benjamini-Hochberg).`)
  })
  outcomes.forEach((o, i) => {
    o.summary = summarise(hypotheses[i], o)
  })
  return outcomes
}

