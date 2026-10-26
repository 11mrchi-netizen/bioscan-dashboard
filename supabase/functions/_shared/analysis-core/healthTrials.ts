// Health concerns (docs/health-concerns/01-plan.md §4.1): a deliberate change read against the weeks before it.
// Erasable TypeScript, no I/O.
//
// A trial is an intervention linked to a concern with a hypothesis and an outcome fixed at registration. It is read once,
// on its evaluate_on date (at least four weeks after the start), and that read is kept: later check-ins cannot move it.
// Before then the numbers are interim and carry no verdict (contract R7). It stays on the OBSERVED rung: what happened
// before and after the change, never that the change did it. Every read lists what else could explain a difference:
// natural recovery (a trial started during a flare-up), a change in total training, partial adherence, other changes.
//
//   flare_rate_per_exposure  new flare-ups per unit of exposure (run km, run hours, sessions or days); exact interval
//                            for the ratio of two Poisson rates (conditional binomial, Clopper-Pearson)
//   mean_severity            mean severity on rated check-in days
//   symptom_free_share       share of check-in days that were symptom-free
//   impact_days              share of check-in days with training modified or worse (impact 2 or more)
// The last three are compared on weekly values (Welch interval), so neighbouring days are not counted as independent.
import { shiftDate } from './healthSeries.ts'
import type { DayCell } from './healthSeries.ts'
import type { Episode } from './healthEpisodes.ts'
import type { ExposureContext } from './healthExposures.ts'
import { mean, sd, tCritical } from './stats.ts'

export const TRIAL = { minDays: 28, minCoverage: 0.6, minWeeks: 3 }

export type TrialOutcome = 'flare_rate_per_exposure' | 'mean_severity' | 'symptom_free_share' | 'impact_days'
export type TrialWord = 'lower' | 'higher' | 'no_clear_change' | 'insufficient'

export interface TrialInput {
  interventionId: string
  name: string
  hypothesis: string
  outcome: TrialOutcome
  exposureUnit: 'days' | 'run_km' | 'run_hours' | 'sessions'
  preDays: number
  startedAt: string
  endedAt: string | null
  evaluateOn: string | null
  today: string
  adherence: { date: string; adhered: 'yes' | 'partial' | 'no' }[]
  /** Other interventions active during the trial (names), for the explanations. */
  concurrent: string[]
}

export interface TrialRead {
  interventionId: string
  state: 'interim' | 'evaluated' | 'insufficient' | 'not_started'
  rung: 'observed'
  outcome: TrialOutcome
  pre: { from: string; to: string; coverage: number; value: number | null; flares?: number; exposure?: number }
  post: { from: string; to: string; coverage: number; value: number | null; flares?: number; exposure?: number }
  /** Ratio (flare rate) or difference (the others), with its 95% interval; null while interim or when not estimable. */
  /** null value or hi: unbounded (no flare-up in the comparison window). */
  effect: { kind: 'ratio' | 'difference'; value: number | null; lo: number; hi: number | null; unit: string } | null
  word: TrialWord | null
  favourable: boolean | null
  adherence: { days: number; yes: number; partial: number; no: number; share: number | null }
  explanations: string[]
  summary: string
  evaluateOn: string
}

// ---- exact pieces ----

function logGamma(x: number): number {
  const c = [76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5]
  let y = x
  const tmp = x + 5.5 - (x + 0.5) * Math.log(x + 5.5)
  let ser = 1.000000000190015
  for (const k of c) ser += k / ++y
  return -tmp + Math.log((2.5066282746310005 * ser) / x)
}

function binomCdf(k: number, n: number, p: number): number {
  if (k < 0) return 0
  if (k >= n) return 1
  let s = 0
  for (let i = 0; i <= k; i++) {
    s += Math.exp(logGamma(n + 1) - logGamma(i + 1) - logGamma(n - i + 1) + (i === 0 ? 0 : i * Math.log(p)) + (n - i === 0 ? 0 : (n - i) * Math.log(1 - p)))
  }
  return Math.min(1, s)
}

/** Clopper-Pearson 95% interval for x successes of n. */
export function clopperPearson(x: number, n: number): { lo: number; hi: number } {
  // lo: P(X >= x | p) = 0.025, rising in p; hi: P(X <= x | p) = 0.025, falling in p.
  let lo = 0
  if (x > 0) {
    let a = 0, b = 1
    for (let i = 0; i < 60; i++) { const m = (a + b) / 2; if (1 - binomCdf(x - 1, n, m) < 0.025) a = m; else b = m }
    lo = (a + b) / 2
  }
  let hi = 1
  if (x < n) {
    let a = 0, b = 1
    for (let i = 0; i < 60; i++) { const m = (a + b) / 2; if (binomCdf(x, n, m) > 0.025) a = m; else b = m }
    hi = (a + b) / 2
  }
  return { lo, hi }
}

/** Ratio of Poisson rates (k1 per u1 over k0 per u0) with its exact conditional 95% interval; null when not estimable. */
export function rateRatio(k1: number, u1: number, k0: number, u0: number): { value: number; lo: number; hi: number } | null {
  const k = k1 + k0
  if (k === 0 || u1 <= 0 || u0 <= 0) return null
  const ci = clopperPearson(k1, k)
  const toRatio = (p: number) => (p >= 1 ? Infinity : (p / (1 - p)) * (u0 / u1))
  const value = k0 === 0 ? Infinity : (k1 / u1) / (k0 / u0)
  return { value, lo: toRatio(ci.lo), hi: toRatio(ci.hi) }
}

function welch(a: number[], b: number[]): { diff: number; lo: number; hi: number } | null {
  if (a.length < 2 || b.length < 2) return null
  const va = sd(a) ** 2 / a.length
  const vb = sd(b) ** 2 / b.length
  const se = Math.sqrt(va + vb)
  const df = se === 0 ? a.length + b.length - 2 : (va + vb) ** 2 / ((va ** 2) / (a.length - 1) + (vb ** 2) / (b.length - 1))
  const t = tCritical(Math.max(1, df))
  const diff = mean(b) - mean(a)
  return { diff, lo: diff - t * se, hi: diff + t * se }
}

const r2 = (v: number) => (Number.isFinite(v) ? Math.round(v * 100) / 100 : v)

// ---- the read ----

function exposurePerDay(ctx: ExposureContext, unit: TrialInput['exposureUnit']): Map<string, number> {
  const out = new Map<string, number>()
  for (const d of ctx.dates) out.set(d, unit === 'days' ? 1 : 0)
  if (unit === 'days') return out
  for (const s of ctx.sessions) {
    if (!out.has(s.date)) continue
    const add = unit === 'sessions' ? (s.type === 'walk' ? 0 : 1)
      : s.type !== 'run' ? 0
      : unit === 'run_km' ? s.distance_km ?? 0
      : (s.duration_min ?? 0) / 60
    out.set(s.date, (out.get(s.date) ?? 0) + add)
  }
  return out
}

export function readTrial(input: TrialInput, cells: DayCell[], episodes: Episode[], ctx: ExposureContext): TrialRead {
  const evaluateOn = input.evaluateOn ?? shiftDate(input.startedAt, TRIAL.minDays)
  const preFrom = shiftDate(input.startedAt, -input.preDays)
  const preTo = shiftDate(input.startedAt, -1)
  const postEnd = [shiftDate(evaluateOn, -1), input.endedAt ?? '9999-12-31', input.today].sort()[0]
  const byDate = new Map(cells.map((c) => [c.date, c]))
  const span = (a: string, b: string) => { const out: DayCell[] = []; for (let d = a; d <= b; d = shiftDate(d, 1)) { const c = byDate.get(d); out.push(c ?? { date: d, observed: false, recalled: false, severity: null, present: false, impact: null, onset: false, observationIds: [], sessionIds: [], timings: [], signals: [] }) } return out }
  const pre = span(preFrom, preTo)
  const post = input.startedAt <= postEnd ? span(input.startedAt, postEnd) : []
  const coverage = (w: DayCell[]) => (w.length ? Math.round((w.filter((c) => c.observed).length / w.length) * 100) / 100 : 0)
  const adhere = input.adherence.filter((a) => a.date >= input.startedAt && a.date <= postEnd)
  const yes = adhere.filter((a) => a.adhered === 'yes').length
  const partial = adhere.filter((a) => a.adhered === 'partial').length
  const adherence = { days: adhere.length, yes, partial, no: adhere.length - yes - partial, share: adhere.length ? Math.round(((yes + partial / 2) / adhere.length) * 100) / 100 : null }
  const base = {
    interventionId: input.interventionId, rung: 'observed' as const, outcome: input.outcome, adherence, evaluateOn,
  }
  if (input.today < input.startedAt) {
    return { ...base, state: 'not_started', pre: { from: preFrom, to: preTo, coverage: coverage(pre), value: null }, post: { from: input.startedAt, to: input.startedAt, coverage: 0, value: null }, effect: null, word: null, favourable: null, explanations: [], summary: `Starts ${input.startedAt}.` }
  }

  const exp = exposurePerDay(ctx, input.exposureUnit)
  const starts = (w: DayCell[]) => episodes.filter((e) => w.length > 0 && e.start >= w[0].date && e.start <= w[w.length - 1].date).length
  const units = (w: DayCell[]) => w.reduce((a, c) => a + (exp.get(c.date) ?? 0), 0)
  const weekly = (w: DayCell[], f: (days: DayCell[]) => number | null) => {
    const out: number[] = []
    for (let i = 0; i + 7 <= w.length; i += 7) { const v = f(w.slice(i, i + 7)); if (v !== null) out.push(v) }
    return out
  }
  const obsOf = (days: DayCell[]) => days.filter((c) => c.observed)
  const metric = (days: DayCell[]): number | null => {
    const o = obsOf(days)
    if (input.outcome === 'mean_severity') { const r = o.filter((c) => c.severity !== null); return r.length >= 2 ? mean(r.map((c) => c.severity as number)) : null }
    if (o.length < 2) return null
    if (input.outcome === 'symptom_free_share') return o.filter((c) => !c.present).length / o.length
    return o.filter((c) => (c.impact ?? 0) >= 2).length / o.length
  }
  const unitName = input.exposureUnit === 'run_km' ? '100 km run' : input.exposureUnit === 'run_hours' ? '10 h run' : input.exposureUnit === 'sessions' ? '10 sessions' : '30 days'
  const per = input.exposureUnit === 'run_km' ? 100 : input.exposureUnit === 'run_hours' ? 10 : input.exposureUnit === 'sessions' ? 10 : 30

  let preValue: number | null, postValue: number | null
  let effect: TrialRead['effect'] = null
  const preFlares = starts(pre), postFlares = starts(post), preUnits = units(pre), postUnits = units(post)
  if (input.outcome === 'flare_rate_per_exposure') {
    preValue = preUnits > 0 ? r2((preFlares / preUnits) * per) : null
    postValue = postUnits > 0 ? r2((postFlares / postUnits) * per) : null
    const rr = rateRatio(postFlares, postUnits, preFlares, preUnits)
    const fin = (v: number) => (Number.isFinite(v) ? r2(v) : null)
    if (rr) effect = { kind: 'ratio', value: fin(rr.value), lo: r2(rr.lo), hi: fin(rr.hi), unit: `flare-ups per ${unitName}` }
  } else {
    preValue = metric(pre)
    postValue = metric(post)
    const w = welch(weekly(pre, metric), weekly(post, metric))
    const scale = input.outcome === 'mean_severity' ? 1 : 100
    if (w && weekly(pre, metric).length >= TRIAL.minWeeks && weekly(post, metric).length >= TRIAL.minWeeks) {
      effect = { kind: 'difference', value: r2(w.diff * scale), lo: r2(w.lo * scale), hi: r2(w.hi * scale), unit: input.outcome === 'mean_severity' ? 'points' : 'percentage points' }
    }
    if (preValue !== null) preValue = r2(preValue * scale)
    if (postValue !== null) postValue = r2(postValue * scale)
  }

  const explanations: string[] = []
  const inFlareAtStart = episodes.some((e) => e.start <= input.startedAt && (e.end === null || e.end > input.startedAt))
  if (inFlareAtStart) explanations.push('It started during a flare-up; flare-ups usually ease on their own, so the weeks after can look better without the change doing anything.')
  const kmIn = (w: DayCell[]) => { let k = 0; for (const s of ctx.sessions) if (s.type === 'run' && w.length && s.date >= w[0].date && s.date <= w[w.length - 1].date) k += s.distance_km ?? 0; return k }
  const kmPreWeek = pre.length ? (kmIn(pre) / pre.length) * 7 : 0
  const kmPostWeek = post.length ? (kmIn(post) / post.length) * 7 : 0
  if (kmPreWeek > 0 && Math.abs(kmPostWeek - kmPreWeek) / kmPreWeek > 0.2) explanations.push(`Weekly running also changed (${Math.round(kmPreWeek)} km before, ${Math.round(kmPostWeek)} km since).`)
  if (adherence.share !== null && adherence.share < 0.8) explanations.push(`The change was kept on ${Math.round(adherence.share * 100)}% of logged days.`)
  if (adherence.days === 0) explanations.push('No adherence logged, so how fully the change was kept is unknown.')
  if (input.concurrent.length) explanations.push(`Other changes at the same time: ${input.concurrent.join(', ')}.`)
  explanations.push('A before-and-after comparison shows what happened, not what the change did.')

  const covPre = coverage(pre), covPost = coverage(post)
  const preR = { from: preFrom, to: preTo, coverage: covPre, value: preValue, ...(input.outcome === 'flare_rate_per_exposure' ? { flares: preFlares, exposure: r2(preUnits) } : {}) }
  const postR = { from: input.startedAt, to: postEnd, coverage: covPost, value: postValue, ...(input.outcome === 'flare_rate_per_exposure' ? { flares: postFlares, exposure: r2(postUnits) } : {}) }
  const interim = input.today < evaluateOn
  const enough = covPre >= TRIAL.minCoverage && covPost >= TRIAL.minCoverage && effect !== null
  if (interim) {
    return { ...base, state: 'interim', pre: preR, post: postR, effect: null, word: null, favourable: null, explanations, summary: `Interim: read on ${evaluateOn}. Numbers so far carry no verdict.` }
  }
  if (!enough) {
    const why = covPre < TRIAL.minCoverage || covPost < TRIAL.minCoverage ? `check-ins on ${Math.round(covPre * 100)}% of days before and ${Math.round(covPost * 100)}% since (60% needed in both)` : 'too few flare-ups or weeks to compare'
    return { ...base, state: 'insufficient', pre: preR, post: postR, effect, word: 'insufficient', favourable: null, explanations, summary: `Not enough to read: ${why}.` }
  }
  const e = effect as NonNullable<TrialRead['effect']>
  const neutral = e.kind === 'ratio' ? 1 : 0
  const word: TrialWord = e.hi !== null && e.hi < neutral ? 'lower' : e.lo > neutral ? 'higher' : 'no_clear_change'
  const lowerIsBetter = input.outcome !== 'symptom_free_share'
  const favourable = word === 'no_clear_change' ? null : (word === 'lower') === lowerIsBetter
  const what = input.outcome === 'flare_rate_per_exposure' ? `flare-ups per ${unitName}` : input.outcome === 'mean_severity' ? 'mean severity' : input.outcome === 'symptom_free_share' ? 'the share of symptom-free days' : 'the share of days with training modified'
  const summary = word === 'no_clear_change'
    ? `Since the change, ${what} showed no clear change (${preValue} before, ${postValue} since).`
    : `Since the change, ${what} was ${word} (${preValue} before, ${postValue} since).`
  return { ...base, state: 'evaluated', pre: preR, post: postR, effect: e, word, favourable, explanations, summary }
}
