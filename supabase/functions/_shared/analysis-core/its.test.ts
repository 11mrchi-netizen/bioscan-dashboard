// Analysis review phase F: interventions read as an interrupted time series, against a forecast from a fixed pre-period.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { analyseIts } from './its.ts'
import type { ItsInput } from './its.ts'
import { shiftDay } from './randomized.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

const START = '2026-01-05'
const WEEKDAY = [0.0, 0.01, 0.02, 0.0, -0.01, -0.03, 0.01]

function input(seed: number, step: number, opts: { badWeekBefore?: number } = {}): ItsInput {
  const rng = mulberry32(seed)
  const dates = dateAxis(START, shiftDay(START, 119))
  let s = 0
  const values: Num[] = dates.map((d, t) => {
    s = 0.6 * s + 0.07 * gaussian(rng)
    const dow = new Date(d + 'T00:00:00Z').getUTCDay()
    const bad = t >= 83 && t < 90 ? (opts.badWeekBefore ?? 0) : 0
    const v = Math.exp(4.1 + s + 0.04 * gaussian(rng) + WEEKDAY[dow] + (t >= 90 ? step : 0) + bad)
    return rng() < 0.1 ? null : v
  })
  return {
    metric: 'hrv', label: 'HRV', dates, values, excluded: dates.map(() => false), ln: true, unit: '%',
    preStart: dates[30], activeStart: dates[90], evaluateOn: dates[117], today: dates[119], zone: 2,
  }
}

test('no change: the 95% interval of the gap covers zero about 95% of the time', () => {
  let covered = 0
  const runs = 200
  for (let i = 0; i < runs; i++) {
    const r = analyseIts(input(100 + i, 0))
    assert.equal(r.state, 'evaluated')
    if (r.gap!.lo <= 0 && 0 <= r.gap!.hi) covered++
  }
  assert.ok(covered / runs >= 0.92 && covered / runs <= 0.99, `coverage ${covered / runs}`)
})

test('a planted step after the start is covered by the interval and usually reads clear', () => {
  const truth = (Math.exp(0.15) - 1) * 100 // +16.2%
  let covered = 0
  let clear = 0
  const runs = 150
  for (let i = 0; i < runs; i++) {
    const r = analyseIts(input(i, 0.15))
    if (r.gap!.lo <= truth && truth <= r.gap!.hi) covered++
    if (r.verdict === 'clear') clear++
  }
  assert.ok(covered / runs >= 0.9, `coverage ${covered / runs}`)
  assert.ok(clear / runs >= 0.65, `power ${clear / runs} (about 77% over four weeks)`)
  const r = analyseIts(input(0, 0.15))
  assert.equal(r.verdict, 'clear')
  assert.equal(r.rung, 'observed')
  assert.match(r.summary, /^Observed: HRV ran \+\d+\.\d% against the forecast from before the start/)
  assert.equal(r.days[0].date, '2026-04-05')
})

test('it is read once, on its date: collecting before, insufficient without enough days before the start', () => {
  const early = analyseIts({ ...input(8, 0), today: '2026-04-20' })
  assert.equal(early.state, 'collecting')
  assert.equal(early.gap, null)
  const thin = analyseIts({ ...input(8, 0), preStart: '2026-03-25' })
  assert.equal(thin.state, 'insufficient')
})

test('a device change before the start shortens the pre-period; one after it leaves nothing to read', () => {
  const plain = analyseIts(input(9, 0))
  const shortened = analyseIts({ ...input(9, 0), breaks: ['2026-03-01'] })
  assert.equal(shortened.state, 'evaluated')
  assert.ok(shortened.preDays < plain.preDays && shortened.preDays >= 28, `${shortened.preDays} vs ${plain.preDays}`)
  const during = analyseIts({ ...input(9, 0), breaks: ['2026-04-10'] })
  assert.equal(during.state, 'insufficient')
  assert.match(during.summary, /source changed on 2026-04-10/)
})
