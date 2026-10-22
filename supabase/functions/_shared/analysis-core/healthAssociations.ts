// Health concerns (docs/health-concerns/01-plan.md §3.6-3.7): which exposures went with flare-ups. Erasable TypeScript,
// no I/O. Two views, both exploratory, both reported with their base rates:
//
//   A. Flare-ups against comparable symptom-free days (time-stratified case-crossover; Maclure 1991, Janes et al.
//      2005). A case is the start of an episode whose start is known to within three days. Its references are days in
//      the same 28-day stratum and the same weekday class (weekday / weekend) that were checked in and symptom-free,
//      outside any episode and the three days after one. The exposure is read in the window before each day. Because
//      the references sample how often an exposure happens on ordinary days, a common exposure (most days include a
//      run) does not look like a trigger only because it often comes before a flare-up. The estimate is the
//      Mantel-Haenszel odds ratio with the Robins-Breslow-Greenland interval (evidence.ts); the p-value is the exact
//      conditional test for one case per set (each set's chance that the case is the exposed one is its exposed share,
//      the count of exposed cases is Poisson-binomial), which stays valid when every flare-up was exposed and the
//      odds ratio cannot be estimated. The expected number of exposed cases from those shares is the plain base rate.
//   B. After each session of a kind, did symptoms start within the window, against other sessions of the same sport
//      (runs for run kinds, strength sessions for strength)? Only sessions on days outside an episode with a check-in
//      in the window count. Risk ratio with Katz's interval (0.5 added to every cell when one is empty), Fisher's exact
//      one-sided p.
//
// Each concern's primary-window p-values are adjusted together (Benjamini-Hochberg). Sensitivity windows are computed
// and shown, but can only weaken a finding. Nothing here says "causes".
import { mantelHaenszel } from './evidence.ts'
import type { MatchedSet } from './evidence.ts'
import { daysBetween, shiftDate } from './healthSeries.ts'
import type { DayCell } from './healthSeries.ts'
import type { Episode } from './healthEpisodes.ts'
import { inWindow, isQuality, isTrail, longRunMinutes, musclesOf } from './healthExposures.ts'
import type { ExposureContext, ExposureSpec } from './healthExposures.ts'
import { benjaminiHochberg, weekdayOf } from './stats.ts'
import type { Num } from './stats.ts'

export const HEALTH_GATES = {
  /** A case's start must be known to within this many days. */
  maxStartUncertainty: 3,
  /** Days after an episode that are not used as references. */
  afterEpisodeDays: 3,
  stratumDays: 28,
  minCases: 5,
  minExposedCases: 3,
  minReferenceDays: 20,
  minOddsRatio: 1.5,
  minLift: 1.5,
  q: 0.1,
  maxMissingness: 3,
  minSessions: 15,
  minExposedEvents: 3,
  minRiskRatio: 1.5,
  /** New cases (after a finding first became possible) needed to call it persistent. */
  confirmCases: 3,
}

// ---- small exact tests ----

function logChoose(n: number, k: number): number {
  let s = 0
  for (let i = 1; i <= k; i++) s += Math.log(n - k + i) - Math.log(i)
  return s
}

/** One-sided Fisher exact p (more exposed events than expected) for the 2x2 table [[a, b], [c, d]]. */
export function fisherGreater(a: number, b: number, c: number, d: number): number {
  const r1 = a + b
  const c1 = a + c
  const n = a + b + c + d
  const max = Math.min(r1, c1)
  const lp = (x: number) => logChoose(r1, x) + logChoose(n - r1, c1 - x) - logChoose(n, c1)
  let p = 0
  for (let x = a; x <= max; x++) p += Math.exp(lp(x))
  return Math.min(1, p)
}

/** P(X >= k) for a sum of independent Bernoulli(p_i) (Poisson-binomial), by dynamic programming. */
export function poissonBinomialUpper(ps: number[], k: number): number {
  let dist = [1]
  for (const p of ps) {
    const next = new Array(dist.length + 1).fill(0)
    for (let j = 0; j < dist.length; j++) {
      next[j] += dist[j] * (1 - p)
      next[j + 1] += dist[j] * p
    }
    dist = next
  }
  let s = 0
  for (let j = k; j < dist.length; j++) s += dist[j]
  return Math.min(1, s)
}

function wilson(x: number, n: number): { lo: number; hi: number } {
  if (n === 0) return { lo: 0, hi: 1 }
  const z = 1.96
  const p = x / n
  const den = 1 + (z * z) / n
  const mid = (p + (z * z) / (2 * n)) / den
  const half = (z * Math.sqrt((p * (1 - p)) / n + (z * z) / (4 * n * n))) / den
  return { lo: Math.max(0, mid - half), hi: Math.min(1, mid + half) }
}

const r2 = (v: number) => Math.round(v * 100) / 100
export const LIFT_CAP = 99

// ---- A: case-crossover ----

export interface CaseInfo {
  date: string
  episodeId: string
}

export interface CrossoverResult {
  window: [number, number]
  cases: number
  exposedCases: number
  /** Exposed cases expected from how common the exposure was on each case's reference days. */
  expectedExposed: number
  referenceDays: number
  exposedReferenceDays: number
  or: number | null
  lo: number | null
  hi: number | null
  p: number | null
  /** Exposed share of cases over exposed share of reference days. */
  lift: number | null
  caseShare: { value: number; lo: number; hi: number } | null
  referenceShare: number | null
  exposedCaseDates: string[]
  caseDates: string[]
}

/** The cases (episode starts known to within three days) and which days may serve as references. */
export function crossoverFrame(cells: DayCell[], episodes: Episode[]): { cases: CaseInfo[]; dropped: number; referenceOk: boolean[] } {
  const cases: CaseInfo[] = []
  let dropped = 0
  for (const e of episodes) {
    const known = e.startEarliest !== null && daysBetween(e.startEarliest, e.start) <= HEALTH_GATES.maxStartUncertainty
    if (known && !e.boundaryUncertain) cases.push({ date: e.start, episodeId: e.id })
    else dropped++
  }
  const blocked = (d: string) => episodes.some((e) => d >= e.start && (e.end === null || d < shiftDate(e.end, HEALTH_GATES.afterEpisodeDays)))
  const referenceOk = cells.map((c) => c.observed && !c.present && !blocked(c.date))
  return { cases, dropped, referenceOk }
}

const stratum = (d: string) => Math.floor(Date.parse(d + 'T00:00:00Z') / 86400000 / HEALTH_GATES.stratumDays)
const weekendClass = (d: string) => (weekdayOf(d) === 0 || weekdayOf(d) === 6 ? 1 : 0)

export function caseCrossover(dates: string[], exposure: Num[], cases: CaseInfo[], referenceOk: boolean[], window: [number, number]): CrossoverResult {
  const idx = new Map(dates.map((d, i) => [d, i]))
  const sets: MatchedSet[] = []
  const shares: number[] = []
  const caseDates: string[] = []
  const exposedCaseDates: string[] = []
  const refDays = new Set<number>()
  const refExposed = new Set<number>()
  for (const c of cases) {
    const t = idx.get(c.date)
    if (t === undefined) continue
    const a = inWindow(exposure, t, window)
    if (a === null) continue
    const refs: number[] = []
    for (let r = 0; r < dates.length; r++) {
      if (!referenceOk[r] || r === t) continue
      if (stratum(dates[r]) !== stratum(c.date) || weekendClass(dates[r]) !== weekendClass(c.date)) continue
      const x = inWindow(exposure, r, window)
      if (x === null) continue
      refs.push(x)
      refDays.add(r)
      if (x === 1) refExposed.add(r)
    }
    if (refs.length === 0) continue
    sets.push({ caseExposed: a as 0 | 1, refs })
    // Chance the exposed member of the set is the case, given how many members were exposed.
    const exposedInSet = a + refs.filter((v) => v === 1).length
    shares.push(exposedInSet / (refs.length + 1))
    caseDates.push(c.date)
    if (a === 1) exposedCaseDates.push(c.date)
  }
  const exposedCases = exposedCaseDates.length
  const mh = sets.length > 0 ? mantelHaenszel(sets) : null
  const p = sets.length > 0 ? poissonBinomialUpper(shares, exposedCases) : null
  const refShare = refDays.size > 0 ? refExposed.size / refDays.size : null
  const caseShare = sets.length > 0 ? exposedCases / sets.length : null
  const ci = sets.length > 0 ? wilson(exposedCases, sets.length) : null
  return {
    window,
    cases: sets.length,
    exposedCases,
    expectedExposed: r2(shares.reduce((a, b) => a + b, 0)),
    referenceDays: refDays.size,
    exposedReferenceDays: refExposed.size,
    or: mh ? r2(mh.or) : null,
    lo: mh ? r2(mh.lo) : null,
    hi: mh ? r2(mh.hi) : null,
    p,
    // No exposed reference day at all: the ratio is unbounded; LIFT_CAP stands for it (JSON has no Infinity).
    lift: caseShare !== null && refShare !== null && refShare > 0 ? r2(caseShare / refShare) : caseShare && refShare === 0 ? LIFT_CAP : null,
    caseShare: caseShare === null || !ci ? null : { value: r2(caseShare), lo: r2(ci.lo), hi: r2(ci.hi) },
    referenceShare: refShare === null ? null : r2(refShare),
    exposedCaseDates,
    caseDates,
  }
}

// ---- B: after each session ----

export interface SessionLevelResult {
  window: [number, number]
  exposedSessions: number
  exposedFollowed: number
  otherSessions: number
  otherFollowed: number
  rr: number | null
  lo: number | null
  hi: number | null
  p: number | null
  exposedSessionIds: number[]
  followedSessionIds: number[]
}

type SessionKind = { sport: (s: ExposureContext['sessions'][number]) => boolean; kind: (s: ExposureContext['sessions'][number]) => boolean }

export function sessionKindFor(spec: ExposureSpec, ctx: ExposureContext, regionMuscles: Set<string>): SessionKind | null {
  const run = (s: ExposureContext['sessions'][number]) => s.type === 'run' && (s.duration_min ?? 0) >= 10
  switch (spec.id) {
    case 'long_run': {
      const min = longRunMinutes(ctx.sessions.filter(run).map((s) => s.duration_min ?? 0))
      return { sport: run, kind: (s) => (s.duration_min ?? 0) >= min }
    }
    case 'trail_run': return { sport: run, kind: (s) => isTrail(s, ctx.zeppSport) }
    case 'quality_run': return { sport: run, kind: (s) => isQuality(s, ctx) }
    case 'regional_strength':
      if (regionMuscles.size === 0) return null
      return {
        sport: (s) => s.type === 'strength',
        kind: (s) => {
          const ex = Array.isArray(s.details?.exercises) ? (s.details!.exercises as { name?: string }[]) : []
          return ex.some((e) => typeof e.name === 'string' && (musclesOf(e.name, ctx.library) ?? []).some((m) => regionMuscles.has(m)))
        },
      }
    default: return null
  }
}

export function sessionLevel(ctx: ExposureContext, cells: DayCell[], episodes: Episode[], kind: SessionKind, window: [number, number], since?: string): SessionLevelResult {
  const byDate = new Map(cells.map((c) => [c.date, c]))
  const inEpisode = (d: string) => episodes.some((e) => d >= e.start && (e.end === null || d < e.end))
  let ea = 0, eb = 0, oa = 0, ob = 0
  const exposedIds: number[] = []
  const followedIds: number[] = []
  const seenDay = new Set<string>()
  // A comparison session whose follow-up days overlap those of an exposed one would count the exposed session's
  // symptoms against the comparison, diluting any difference; such sessions are left out of the comparison.
  const exposedDays = ctx.sessions.filter((s) => kind.sport(s) && kind.kind(s)).map((s) => s.date)
  const shadowed = (d: string) => exposedDays.some((x) => x < d && d <= shiftDate(x, window[1]))
  const sportDays = [...new Set(ctx.sessions.filter((s) => kind.sport(s)).map((s) => s.date))]
  for (const s of [...ctx.sessions].sort((a, b) => (a.start_time < b.start_time ? -1 : 1))) {
    if (!kind.sport(s) || (since !== undefined && s.date <= since)) continue
    // One session per day and sport (a second run the same day shares its follow-up days).
    const key = `${s.date}`
    if (seenDay.has(key)) continue
    // Only sessions done while symptom-free: not inside an episode, unless the episode starts that very day.
    if (inEpisode(shiftDate(s.date, -1)) || (inEpisode(s.date) && !episodes.some((e) => e.start === s.date))) continue
    const follow: DayCell[] = []
    for (let k = 0; k <= window[1]; k++) { const c = byDate.get(shiftDate(s.date, k)); if (c) follow.push(c) }
    if (!follow.some((c) => c.observed)) continue
    seenDay.add(key)
    // Followed = a flare-up started within the window. Each flare-up is credited to the last session of the sport before
    // it: an earlier session whose window also held it is left out, so one flare-up is never counted for two sessions
    // (overlapping windows made the counts look more certain than they are).
    const end = shiftDate(s.date, window[1])
    const onset = episodes.map((e) => e.start).filter((d) => d >= s.date && d <= end).sort()[0]
    if (onset !== undefined && sportDays.some((d) => d > s.date && d <= onset)) continue
    const followed = onset !== undefined
    const isKind = kind.kind(s)
    if (!isKind && shadowed(s.date)) continue
    if (isKind) {
      exposedIds.push(s.id)
      if (followed) { ea++; followedIds.push(s.id) } else eb++
    } else if (followed) oa++
    else ob++
  }
  const n1 = ea + eb
  const n0 = oa + ob
  let rr: number | null = null, lo: number | null = null, hi: number | null = null
  if (n1 > 0 && n0 > 0) {
    const zero = ea === 0 || eb === 0 || oa === 0 || ob === 0
    const a = ea + (zero ? 0.5 : 0), b = eb + (zero ? 0.5 : 0), c = oa + (zero ? 0.5 : 0), d = ob + (zero ? 0.5 : 0)
    const est = (a / (a + b)) / (c / (c + d))
    const se = Math.sqrt(1 / a - 1 / (a + b) + 1 / c - 1 / (c + d))
    rr = r2(est)
    lo = r2(Math.exp(Math.log(est) - 1.96 * se))
    hi = r2(Math.exp(Math.log(est) + 1.96 * se))
  }
  return {
    window, exposedSessions: n1, exposedFollowed: ea, otherSessions: n0, otherFollowed: oa, rr, lo, hi,
    p: n1 > 0 && n0 > 0 ? fisherGreater(ea, eb, oa, ob) : null,
    exposedSessionIds: exposedIds, followedSessionIds: followedIds,
  }
}

// ---- per concern: every question, adjusted together ----

export interface ExposureEvaluation {
  exposure: ExposureSpec
  crossover: CrossoverResult
  crossoverSensitivity: CrossoverResult[]
  session: SessionLevelResult | null
  sessionSensitivity: SessionLevelResult[]
  qCrossover: number | null
  qSession: number | null
  /** Did the primary-window criteria hold tonight (before history is taken into account)? */
  meets: boolean
  meetsVia: 'crossover' | 'session' | null
  reasonsNot: string[]
}

export function evaluateExposures(
  ctx: ExposureContext,
  cells: DayCell[],
  episodes: Episode[],
  specs: ExposureSpec[],
  series: Record<string, Num[]>,
  regionMuscles: Set<string>,
  quality: { word: string; informativeMissingness: number | null },
): { evaluations: ExposureEvaluation[]; dropped: number; cases: CaseInfo[]; referenceOk: boolean[] } {
  const { cases, dropped, referenceOk } = crossoverFrame(cells, episodes)
  const evals = specs.map((spec) => {
    const x = series[spec.id] ?? cells.map(() => null)
    const crossover = caseCrossover(ctx.dates, x, cases, referenceOk, spec.window)
    const crossoverSensitivity = spec.sensitivity.map((w) => caseCrossover(ctx.dates, x, cases, referenceOk, w))
    const kind = spec.sessionLevel ? sessionKindFor(spec, ctx, regionMuscles) : null
    const session = kind ? sessionLevel(ctx, cells, episodes, kind, spec.window) : null
    const sessionSensitivity = kind ? spec.sensitivity.map((w) => sessionLevel(ctx, cells, episodes, kind, w)) : []
    return { exposure: spec, crossover, crossoverSensitivity, session, sessionSensitivity, qCrossover: null as number | null, qSession: null as number | null, meets: false, meetsVia: null as ExposureEvaluation['meetsVia'], reasonsNot: [] as string[] }
  })
  // One family of tests per concern: one pre-specified primary analysis per exposure, primary window only.
  const ps: { i: number; via: 'c' | 's'; p: number }[] = []
  evals.forEach((e, i) => {
    if (e.session) {
      if (e.session.p !== null && e.session.exposedSessions > 0) ps.push({ i, via: 's', p: e.session.p })
    } else if (e.crossover.p !== null && e.crossover.cases > 0) ps.push({ i, via: 'c', p: e.crossover.p })
  })
  const qs = benjaminiHochberg(ps.map((x) => x.p))
  ps.forEach((x, k) => {
    const q = Math.round(qs[k] * 1000) / 1000
    if (x.via === 'c') evals[x.i].qCrossover = q
    else evals[x.i].qSession = q
  })
  const G = HEALTH_GATES
  for (const e of evals) {
    const c = e.crossover
    const blockers: string[] = []
    if (quality.word === 'THIN') blockers.push('check-ins too sparse (DATA: THIN)')
    if (quality.informativeMissingness !== null && quality.informativeMissingness > G.maxMissingness) blockers.push('check-ins mostly on bad days, so symptom-free days are under-observed')
    const why: string[] = []
    const aOk =
      c.cases >= G.minCases && c.exposedCases >= G.minExposedCases && c.referenceDays >= G.minReferenceDays &&
      e.qCrossover !== null && e.qCrossover < G.q && (c.or === null ? c.exposedCases === c.cases : c.or >= G.minOddsRatio) &&
      c.lift !== null && c.lift >= G.minLift && (c.lo === null || c.lo > 1)
    const s = e.session
    const bOk = s !== null && s.exposedSessions >= G.minSessions && s.exposedFollowed >= G.minExposedEvents &&
      e.qSession !== null && e.qSession < G.q && s.rr !== null && s.rr >= G.minRiskRatio && s.lo !== null && s.lo > 1
    if (s) {
      if (s.exposedSessions < G.minSessions) why.push(`${s.exposedSessions} of ${G.minSessions} sessions with a check-in in the days after`)
    } else if (c.cases < G.minCases) why.push(`${c.cases} of ${G.minCases} flare-ups with check-ins before them`)
    else if (c.referenceDays < G.minReferenceDays) why.push(`${c.referenceDays} of ${G.minReferenceDays} symptom-free reference days`)
    // Sensitivity windows can only weaken: a primary finding whose other windows point the other way is not one.
    const sensitivityAgrees = e.crossoverSensitivity.every((x) => x.cases === 0 || x.lift === null || x.lift >= 1)
      && e.sessionSensitivity.every((x) => x.rr === null || x.rr >= 1)
    const primaryOk = s !== null ? bOk : aOk
    e.meets = blockers.length === 0 && primaryOk && sensitivityAgrees
    e.meetsVia = e.meets ? (s !== null ? 'session' : 'crossover') : null
    if (!e.meets && primaryOk && blockers.length === 0) why.push('a different time window points the other way')
    e.reasonsNot = e.meets ? [] : [...blockers, ...why]
    if (!e.meets && e.reasonsNot.length === 0) e.reasonsNot.push('no clear difference from comparable symptom-free days')
  }
  return { evaluations: evals, dropped, cases, referenceOk }
}

/** The same comparison on flare-ups after `since` only: the confirmation set (no re-use of the cases that raised it). */
export function confirmation(dates: string[], exposure: Num[], cases: CaseInfo[], referenceOk: boolean[], window: [number, number], since: string): CrossoverResult {
  return caseCrossover(dates, exposure, cases.filter((c) => c.date > since), referenceOk, window)
}
