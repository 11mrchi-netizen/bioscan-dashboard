// Phase F gates (docs/analysis-review/README.md): randomised self-experiments keep false positives at or below 5% on
// backtests that add drift, carry-over, missing days and a start in a bad week; the fixed without-then-with design does not.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseDaily, analysePairs, drawDailySchedule, drawPeriodSchedule, fourWord, shiftDay } from './randomized.ts'
import type { PeriodPhase } from './randomized.ts'
import { dateAxis, gaussian, mean, mulberry32, sd, tCritical } from './stats.ts'
import type { Num } from './stats.ts'

const START = '2026-02-02' // a Monday
const WEEKDAY = [0.0, 0.01, 0.02, 0.0, -0.01, -0.03, 0.01] // Sunday first

interface World {
  /** Planted effect of being "with" (log scale), and how many days it lingers after a with-period or with-day ends. */
  effect?: number
  carryOverDays?: number
  /** Slow drift per day (log scale): about -9% over 90 days at -0.001. */
  drift?: number
  /** The first week runs this much low (a bad week to start in). */
  badWeek?: number
  missing?: number
}

// Log HRV: AR(1) state, measurement noise, weekday pattern, drift, a bad first week; `withOn(t)` says if day t is "with".
function hrvSeries(rng: () => number, dates: string[], withOn: (t: number) => boolean, w: World): Num[] {
  let s = 0
  let lastWith = -1000
  return dates.map((d, t) => {
    s = 0.6 * s + 0.07 * gaussian(rng)
    if (withOn(t)) lastWith = t
    const active = withOn(t) || t - lastWith <= (w.carryOverDays ?? 0)
    const dow = new Date(d + 'T00:00:00Z').getUTCDay()
    const v = 4.1 + s + 0.05 * gaussian(rng) + WEEKDAY[dow] + (w.drift ?? 0) * t + (t < 7 ? (w.badWeek ?? 0) : 0) + (active ? (w.effect ?? 0) : 0)
    return rng() < (w.missing ?? 0) ? null : v
  })
}

function periodRun(seed: number, w: World, pairs = 4, periodDays = 14, washout = 3) {
  const rng = mulberry32(seed)
  const phases = drawPeriodSchedule(rng, START, periodDays, pairs)
  const dates = dateAxis(START, phases[phases.length - 1].end)
  const isWith = (t: number) => phases.some((p) => p.label === 'B' && dates[t] >= p.start && dates[t] <= p.end)
  const values = hrvSeries(rng, dates, isWith, w)
  return { res: analysePairs({ dates, values, phases, washoutDays: washout }), dates, values, phases }
}

// The old design for comparison: one without-period, then one with-period, a t-test on the two means.
function fixedAbFalsePositive(seed: number, w: World): boolean {
  const rng = mulberry32(seed)
  const phases: PeriodPhase[] = [{ label: 'A', start: START, end: shiftDay(START, 55), pair: 0 }, { label: 'B', start: shiftDay(START, 56), end: shiftDay(START, 111), pair: 0 }]
  const dates = dateAxis(START, phases[1].end)
  const values = hrvSeries(rng, dates, (t) => t >= 56, w)
  const a = values.slice(0, 56).filter((v): v is number => v !== null)
  const b = values.slice(56).filter((v): v is number => v !== null)
  const se = Math.sqrt(sd(a) ** 2 / a.length + sd(b) ** 2 / b.length)
  return Math.abs(mean(b) - mean(a)) > tCritical(a.length + b.length - 2) * se
}

test('drawing: pairs in random order with equal periods; daily schedules balanced within weeks', () => {
  const phases = drawPeriodSchedule(mulberry32(1), START, 14, 4)
  assert.equal(phases.length, 8)
  for (let p = 0; p < 4; p++) assert.deepEqual(phases.filter((x) => x.pair === p).map((x) => x.label).sort(), ['A', 'B'])
  assert.equal(phases[1].start, shiftDay(phases[0].end, 1))
  const orders = new Set(Array.from({ length: 40 }, (_, s) => drawPeriodSchedule(mulberry32(s), START, 14, 1)[0].label))
  assert.deepEqual([...orders].sort(), ['A', 'B'], 'both orders occur')
  const days = drawDailySchedule(mulberry32(2), START, 8)
  assert.equal(days.length, 56)
  for (let w = 0; w < 8; w++) assert.equal(days.slice(w * 7, w * 7 + 7).filter((d) => d.arm === 'B').length, w % 2 === 0 ? 3 : 4)
})

test('gate: periods in random order keep false positives at or below 5% with drift, a bad first week and missing days', () => {
  const hostile: World = { drift: -0.001, badWeek: -0.15, missing: 0.15 }
  let fp = 0
  let fixedFp = 0
  const runs = 300
  for (let i = 0; i < runs; i++) {
    const { res } = periodRun(1000 + i, hostile)
    if (res.lo !== null && res.hi !== null && (res.lo > 0 || res.hi < 0)) fp++
    if (fixedAbFalsePositive(5000 + i, hostile)) fixedFp++
  }
  assert.ok(fp / runs <= 0.05, `randomised pairs: ${fp} of ${runs}`)
  assert.ok(fixedFp / runs > 0.3, `the fixed without-then-with design should fail here: ${fixedFp} of ${runs}`)
})

test('periods: a planted effect with three days of carry-over is recovered once every period starts with a washout', () => {
  const truth = 0.1
  let covered = 0
  let found = 0
  const runs = 200
  for (let i = 0; i < runs; i++) {
    const { res } = periodRun(9000 + i, { effect: truth, carryOverDays: 3, drift: -0.001, missing: 0.15 }, 6, 14, 4)
    if (res.lo !== null && res.hi !== null) {
      if (res.lo <= truth && truth <= res.hi) covered++
      if (res.lo > 0) found++
    }
  }
  assert.ok(covered / runs >= 0.92, `coverage ${covered / runs}`)
  assert.ok(found / runs >= 0.6, `power ${found / runs} (about 70% for +10.5% over six 14-day pairs)`)
})

test('periods: with six or more pairs the exact randomisation p can reach 5%; with fewer it cannot', () => {
  const big = periodRun(77, { effect: 0.25 }, 6).res
  assert.ok(big.p !== null && big.p < 0.05, `p ${big.p}`)
  const small = periodRun(77, { effect: 0.25 }, 4).res
  assert.ok(small.p !== null && small.p >= 2 / 16 - 1e-12)
})

// Sleep minutes in hours: AR(1) state, weekday pattern, drift; the outcome is read the next morning (lag 1).
function dailyRun(seed: number, effectH: number, weeks = 8, w: World = {}) {
  const rng = mulberry32(seed)
  const assignments = drawDailySchedule(rng, START, weeks)
  const dates = dateAxis(START, shiftDay(START, weeks * 7))
  const byDate = new Map(assignments.map((a) => [a.date, a.arm]))
  let s = 0
  const values: Num[] = dates.map((d, t) => {
    s = 0.4 * s + 0.5 * gaussian(rng)
    const prev = t > 0 ? byDate.get(dates[t - 1]) : undefined
    const dow = new Date(d + 'T00:00:00Z').getUTCDay()
    const v = 7.2 + s + (dow === 0 || dow === 6 ? 0.4 : 0) + (w.drift ?? 0) * t + (prev === 'B' ? effectH : 0)
    return rng() < (w.missing ?? 0) ? null : v
  })
  return analyseDaily({ dates, values, assignments, lag: 1 }, `t${seed}`)
}

test('gate: days in random order keep false positives at or below 5% (exact test) with drift and missing days', () => {
  let fp = 0
  const runs = 150
  for (let i = 0; i < runs; i++) {
    const r = dailyRun(20_000 + i, 0, 8, { drift: -0.01, missing: 0.15 })
    if (r.p !== null && r.p < 0.05) fp++
  }
  assert.ok(fp / runs <= 0.05, `${fp} of ${runs}`)
})

test('days: a planted 30-minute effect is found and the inverted interval covers it', () => {
  let covered = 0
  let found = 0
  const runs = 60
  for (let i = 0; i < runs; i++) {
    const r = dailyRun(30_000 + i, 0.5, 8, { missing: 0.1 })
    if (r.lo !== null && r.hi !== null) {
      if (r.lo <= 0.5 && 0.5 <= r.hi) covered++
      if (r.lo > 0) found++
    }
  }
  assert.ok(covered / runs >= 0.9, `coverage ${covered / runs}`)
  assert.ok(found / runs >= 0.75, `power ${found / runs}`)
})

test('four words against the zone, in the outcome\'s favourable direction', () => {
  assert.equal(fourWord({ lo: 3, hi: 9, zone: 2, favourable: 1 }), 'clear_benefit')
  assert.equal(fourWord({ lo: 3, hi: 9, zone: 2, favourable: -1 }), 'clear_harm')
  assert.equal(fourWord({ lo: -9, hi: -3, zone: 2, favourable: -1 }), 'clear_benefit')
  assert.equal(fourWord({ lo: -1.5, hi: 1.2, zone: 2, favourable: 1 }), 'negligible')
  assert.equal(fourWord({ lo: -1, hi: 6, zone: 2, favourable: 1 }), 'unclear')
})
