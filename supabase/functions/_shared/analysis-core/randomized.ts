// Analysis review phase F (P8): randomised self-experiments. Erasable TypeScript, no I/O.
//
// Two designs, both registered before they start and both judged against a no-effect zone the person set at
// registration, in four words: CLEAR (benefit or harm: the interval lies outside the zone), NEGLIGIBLE (inside it),
// UNCLEAR (across an edge), with NOT YET while too few periods or days have been seen.
//
//   Periods in random order (supplements and other slow-acting changes). Pairs of equal periods; within each pair the
//   order, with-then-without or without-then-with, is drawn at random when the experiment is registered. Every period
//   starts with a washout whose days are not analysed (the old design had one only before the with-phase, so withdrawal
//   landed in the without-phase). Each pair gives one difference (with minus without), from its own two periods, so a
//   slow drift cancels within the pair; the estimate is the mean of the pair differences, with a Student t interval on
//   them, and the p-value is the exact randomisation test over the 2^pairs orders that could have been drawn.
//
//   Days in random order (same-day choices: a caffeine cut-off, a late meal, an evening workout). Within each week the
//   app draws which days are "with" (3 or 4 of 7). The outcome is read `lag` days later. Analysed as assigned, including
//   days the schedule was not followed. The estimate compares with- and without-days within each week; the p-value is
//   exact (re-drawing the schedule the way the app drew it, so the only randomness is the one the app introduced), and the
//   interval comes from inverting that test.
//
// Optional blinding (a partner fills a weekly organiser with the real thing or a placebo) changes nothing here except
// that the person does not know the schedule; the result says the comparison was blinded.
import { fnv1a } from './hash.ts'
import { mean, mulberry32, sd, tCritical, weekdayDemean } from './stats.ts'
import type { Num } from './stats.ts'

export type RandomizedDesign = 'randomized_pairs' | 'micro_randomized'
export type FourWord = 'clear_benefit' | 'clear_harm' | 'negligible' | 'unclear'

const dayMs = 86400000
export const shiftDay = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * dayMs).toISOString().slice(0, 10)
const dayNo = (d: string) => Math.round(Date.parse(d + 'T00:00:00Z') / dayMs)

// ---- drawing the schedule (the phone draws it at registration with its own random source; these mirror it for tests) ----

export interface PeriodPhase {
  label: 'A' | 'B'
  start: string
  end: string
  pair: number
}

/** Pairs of periods, each pair's order drawn at random. A = without, B = with. */
export function drawPeriodSchedule(rand: () => number, firstDay: string, periodDays: number, pairs: number): PeriodPhase[] {
  const out: PeriodPhase[] = []
  let day = firstDay
  for (let p = 0; p < pairs; p++) {
    const order: ('A' | 'B')[] = rand() < 0.5 ? ['A', 'B'] : ['B', 'A']
    for (const label of order) {
      out.push({ label, start: day, end: shiftDay(day, periodDays - 1), pair: p })
      day = shiftDay(day, periodDays)
    }
  }
  return out
}

/** Within each week from `firstDay`, `perWeek[w % 2]` days (3 then 4, and so on) drawn at random as "with" days. */
export function drawDailySchedule(rand: () => number, firstDay: string, weeks: number, perWeek: [number, number] = [3, 4]): { date: string; arm: 'A' | 'B' }[] {
  const out: { date: string; arm: 'A' | 'B' }[] = []
  for (let w = 0; w < weeks; w++) {
    const k = perWeek[w % 2]
    const days = Array.from({ length: 7 }, (_, i) => i)
    for (let i = days.length - 1; i > 0; i--) {
      const j = Math.floor(rand() * (i + 1))
      ;[days[i], days[j]] = [days[j], days[i]]
    }
    const withDays = new Set(days.slice(0, k))
    for (let i = 0; i < 7; i++) out.push({ date: shiftDay(firstDay, w * 7 + i), arm: withDays.has(i) ? 'B' : 'A' })
  }
  return out
}

// ---- the verdict ----

export interface ZoneVerdictInput {
  lo: number
  hi: number
  zone: number
  /** +1 when higher is better for this outcome, -1 when lower is better. */
  favourable: 1 | -1
}

export function fourWord({ lo, hi, zone, favourable }: ZoneVerdictInput): FourWord {
  if (lo > zone || hi < -zone) {
    const up = lo > zone
    return (up ? 1 : -1) === favourable ? 'clear_benefit' : 'clear_harm'
  }
  if (lo >= -zone && hi <= zone) return 'negligible'
  return 'unclear'
}

export const FOUR_WORDS: Record<FourWord, string> = {
  clear_benefit: 'CLEAR BENEFIT',
  clear_harm: 'CLEAR HARM',
  negligible: 'NO MEANINGFUL EFFECT',
  unclear: 'UNCLEAR',
}

// ---- periods in random order ----

export interface PairsInput {
  dates: string[]
  /** Outcome in model space (log for HRV), null where missing or excluded. */
  values: Num[]
  phases: PeriodPhase[]
  washoutDays: number
}

export interface PairsAnalysis {
  pairs: number
  /** Pairs with at least `minDaysPerPeriod` usable days in both periods. */
  usablePairs: number
  differences: number[]
  estimate: number | null
  lo: number | null
  hi: number | null
  /** Exact randomisation p over the 2^usablePairs orders. */
  p: number | null
  daysWith: number
  daysWithout: number
}

export const MIN_DAYS_PER_PERIOD = 4

export function analysePairs({ dates, values, phases, washoutDays }: PairsInput): PairsAnalysis {
  const index = new Map(dates.map((d, i) => [d, i]))
  // Weekday-adjust once over every day used in the experiment, so a weekly routine does not depend on how periods fall.
  const inPeriod: Num[] = dates.map((d, i) => {
    const ph = phases.find((p) => d >= p.start && d <= p.end)
    if (!ph || values[i] === null) return null
    return dayNo(d) - dayNo(ph.start) < washoutDays ? null : values[i]
  })
  const adj = weekdayDemean(dates, inPeriod)
  const periodMean = (ph: PeriodPhase) => {
    const xs: number[] = []
    for (let d = ph.start; d <= ph.end; d = shiftDay(d, 1)) {
      const i = index.get(d)
      if (i !== undefined && adj[i] !== null) xs.push(adj[i] as number)
    }
    return xs.length >= MIN_DAYS_PER_PERIOD ? { m: mean(xs), n: xs.length } : null
  }
  const pairIds = [...new Set(phases.map((p) => p.pair))].sort((a, b) => a - b)
  const differences: number[] = []
  let daysWith = 0
  let daysWithout = 0
  for (const id of pairIds) {
    const a = phases.find((p) => p.pair === id && p.label === 'A')
    const b = phases.find((p) => p.pair === id && p.label === 'B')
    if (!a || !b) continue
    const ma = periodMean(a)
    const mb = periodMean(b)
    if (!ma || !mb) continue
    differences.push(mb.m - ma.m)
    daysWith += mb.n
    daysWithout += ma.n
  }
  const base = { pairs: pairIds.length, usablePairs: differences.length, differences, daysWith, daysWithout }
  if (differences.length < 2) return { ...base, estimate: differences.length === 1 ? differences[0] : null, lo: null, hi: null, p: null }
  const est = mean(differences)
  const se = sd(differences) / Math.sqrt(differences.length)
  const half = tCritical(differences.length - 1) * se
  // Exact randomisation test: flipping a pair's order flips the sign of its difference.
  const k = differences.length
  let extreme = 0
  for (let mask = 0; mask < 2 ** k; mask++) {
    const m = mean(differences.map((d, i) => ((mask >> i) & 1 ? -d : d)))
    if (Math.abs(m) >= Math.abs(est) - 1e-12) extreme++
  }
  return { ...base, estimate: est, lo: est - half, hi: est + half, p: extreme / 2 ** k }
}

// ---- days in random order ----

export interface DailyInput {
  dates: string[]
  /** Outcome in model space, null where missing or excluded. */
  values: Num[]
  assignments: { date: string; arm: 'A' | 'B' }[]
  /** The outcome is read this many days after the assigned day (1: the night after, keyed to the next morning). */
  lag: number
}

export interface DailyAnalysis {
  daysWith: number
  daysWithout: number
  weeks: number
  estimate: number | null
  lo: number | null
  hi: number | null
  p: number | null
}

interface DailyUnit { week: number; arm: 'A' | 'B'; y: number }

export const DAILY_RERANDOMIZATIONS = 2000
export const MIN_DAILY_EACH = 10

// For an assignment (with-days marked 1) the within-week statistic after shifting the observed with-days by delta is
// linear in delta: stat(delta) = a - delta * b. Each re-drawn schedule is reduced to its (a, b) once, so the test can be
// inverted for an interval without re-drawing anything.
function linearStat(units: DailyUnit[], weeks: number[][], arms: Uint8Array, observed: Uint8Array): { a: number; b: number } | null {
  let a = 0
  let b = 0
  let den = 0
  for (const idx of weeks) {
    let nB = 0
    let nA = 0
    let sB = 0
    let sA = 0
    let oB = 0
    let oA = 0
    for (const i of idx) {
      if (arms[i]) { nB++; sB += units[i].y; oB += observed[i] } else { nA++; sA += units[i].y; oA += observed[i] }
    }
    if (nA === 0 || nB === 0) continue
    a += nB * (sB / nB - sA / nA)
    b += nB * (oB / nB - oA / nA)
    den += nB
  }
  return den > 0 ? { a: a / den, b: b / den } : null
}

export function analyseDaily({ dates, values, assignments, lag }: DailyInput, seed = 'daily'): DailyAnalysis {
  const index = new Map(dates.map((d, i) => [d, i]))
  const first = assignments[0]?.date
  const units: DailyUnit[] = []
  for (const a of assignments) {
    const i = index.get(shiftDay(a.date, lag))
    if (i === undefined || values[i] === null || first === undefined) continue
    units.push({ week: Math.floor((dayNo(a.date) - dayNo(first)) / 7), arm: a.arm, y: values[i] as number })
  }
  const daysWith = units.filter((u) => u.arm === 'B').length
  const daysWithout = units.length - daysWith
  const weekIds = [...new Set(units.map((u) => u.week))]
  const base = { daysWith, daysWithout, weeks: weekIds.length }
  if (daysWith < MIN_DAILY_EACH || daysWithout < MIN_DAILY_EACH) return { ...base, estimate: null, lo: null, hi: null, p: null }
  const weeks = weekIds.map((w) => units.map((u, i) => (u.week === w ? i : -1)).filter((i) => i >= 0))
  const observed = Uint8Array.from(units.map((u) => (u.arm === 'B' ? 1 : 0)))
  const obs = linearStat(units, weeks, observed, observed)
  if (!obs) return { ...base, estimate: null, lo: null, hi: null, p: null }

  // Re-draw the schedule within each week, keeping each week's number of with-days (that is how it was drawn).
  const rng = mulberry32(parseInt(fnv1a(`${seed}:${units.length}`), 16))
  const coef: { a: number; b: number }[] = []
  const arms = new Uint8Array(units.length)
  for (let r = 0; r < DAILY_RERANDOMIZATIONS; r++) {
    for (const idx of weeks) {
      const local = idx.map((i) => observed[i])
      for (let i = local.length - 1; i > 0; i--) {
        const j = Math.floor(rng() * (i + 1))
        const t = local[i]
        local[i] = local[j]
        local[j] = t
      }
      idx.forEach((i, k) => { arms[i] = local[k] })
    }
    const c = linearStat(units, weeks, arms, observed)
    if (c) coef.push(c)
  }
  // p for "the effect is delta": how often a re-drawn schedule is at least as far from zero as the real one.
  const pAt = (delta: number) => {
    const o = Math.abs(obs.a - delta * obs.b)
    let extreme = 1
    for (const c of coef) if (Math.abs(c.a - delta * c.b) >= o - 1e-12) extreme++
    return extreme / (coef.length + 1)
  }
  const est = obs.a // b = 1 for the schedule that was used
  const p = pAt(0)
  // Invert the test: the 95% interval is every delta it does not reject at 5%.
  const spread = sd(units.map((u) => u.y)) || 1
  const bound = (dir: 1 | -1) => {
    let inside = est
    let outside = est + dir * spread
    for (let k = 0; k < 20 && pAt(outside) >= 0.05; k++) outside += dir * spread
    for (let k = 0; k < 40; k++) {
      const mid = (inside + outside) / 2
      if (pAt(mid) >= 0.05) inside = mid
      else outside = mid
    }
    return (inside + outside) / 2
  }
  return { ...base, estimate: est, lo: bound(-1), hi: bound(1), p }
}
