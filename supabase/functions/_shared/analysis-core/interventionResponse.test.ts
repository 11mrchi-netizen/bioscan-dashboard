// Backtest gate for the observational intervention-response engine (docs/intervention-protocol-intelligence).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseInterventionResponse, MIN_WINDOW_DAYS } from './interventionResponse.ts'
import type { InterventionResponseWindows } from './interventionResponse.ts'
import type { Frame } from './relationship.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

const START = '2026-01-05'
const shiftDate = (d: string, n: number) => new Date(Date.parse(d + 'T00:00:00Z') + n * 86400000).toISOString().slice(0, 10)

function build(seed: number, days: number, activeFromDay: number, effect: number) {
  const rng = mulberry32(seed)
  const dates = dateAxis(START, shiftDate(START, days - 1))
  let e = 0
  const hrv: Num[] = []
  const rhr: Num[] = []
  for (let t = 0; t < days; t++) {
    e = 0.5 * e + 0.0866 * gaussian(rng)
    const eff = t >= activeFromDay ? effect : 0
    hrv.push(Math.exp(4.1 + e + eff))
    rhr.push(55 + gaussian(rng))
  }
  const frame: Frame = { dates, series: { hrv, rhr }, excluded: dates.map(() => false) }
  const windows: InterventionResponseWindows = {
    baselineStart: dates[0],
    baselineEnd: shiftDate(START, activeFromDay - 1),
    activeStart: shiftDate(START, activeFromDay),
    activeEnd: null,
  }
  return { frame, windows }
}

test('a large planted signal is observed, with the right size, sign and a sensible interval', () => {
  const { frame, windows } = build(11, 90, 45, 0.22) // +22 %, baseline days 0-44, active days 45-89
  const today = shiftDate(START, 89)
  const res = analyseInterventionResponse('iv-1', ['hrv'], windows, frame, today)
  const m = res.metrics[0]
  assert.equal(m.verdict, 'signal_observed')
  assert.ok(m.diff!.value > 0 && m.diff!.lo < 24.6 && m.diff!.hi > 24.6, JSON.stringify(m.diff)) // exp(0.22) - 1 = 24.6 %
  assert.ok(m.summary.includes('observed signal during intervention'))
  assert.ok(!m.summary.toLowerCase().includes('effect detected'), 'never claims a detected effect')
})

test('no planted signal: reports no_signal, not insufficient, once there is enough data', () => {
  const { frame, windows } = build(1, 90, 45, 0)
  const today = shiftDate(START, 89)
  const res = analyseInterventionResponse('iv-1', ['hrv'], windows, frame, today)
  assert.equal(res.metrics[0].verdict, 'no_signal')
  assert.ok(res.metrics[0].mde! > 0, 'states the smallest signal this comparison could have seen')
})

test('fewer than MIN_WINDOW_DAYS on either side reports insufficient', () => {
  const { frame, windows } = build(3, 30, 20, 0.3) // only 10 active days
  const today = shiftDate(START, 29)
  const res = analyseInterventionResponse('iv-1', ['hrv'], windows, frame, today)
  assert.equal(res.metrics[0].verdict, 'insufficient')
  assert.equal(res.metrics[0].n1, 10)
  assert.ok(res.metrics[0].n1 < MIN_WINDOW_DAYS)
})

test('an ongoing intervention (activeEnd null) still analyses using data up to today -- no "complete" stage gate', () => {
  const { frame, windows } = build(7, 90, 45, 0.22)
  const today = shiftDate(START, 70) // well before the series even ends; nothing marks this intervention "ended"
  const res = analyseInterventionResponse('iv-1', ['hrv'], windows, frame, today)
  assert.notEqual(res.metrics[0].verdict, 'insufficient')
  assert.equal(windows.activeEnd, null)
})

test('multiple metrics analysed together share one Benjamini-Hochberg correction', () => {
  const { frame, windows } = build(9, 90, 45, 0)
  const today = shiftDate(START, 89)
  const res = analyseInterventionResponse('iv-1', ['hrv', 'rhr'], windows, frame, today)
  assert.equal(res.metrics.length, 2)
  for (const m of res.metrics) assert.ok(m.pAdjusted !== null && m.pAdjusted! >= m.p!)
})
