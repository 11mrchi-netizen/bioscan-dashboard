import assert from 'node:assert/strict'
import { test } from 'node:test'
import { stationarySeries, stepChangeSeries } from './fixtures.ts'
import { buildSeriesBreakResult } from './seriesBreakResult.ts'
import { dateAxis } from './stats.ts'

const axis = (n: number) => dateAxis('2026-01-01', new Date(Date.UTC(2026, 0, 1) + (n - 1) * 86400000).toISOString().slice(0, 10))
const base = (values: (number | null)[], extra: Partial<Parameters<typeof buildSeriesBreakResult>[0]> = {}) =>
  buildSeriesBreakResult({ metric: 'hrv', deviceSensitive: true, dates: axis(values.length), values, originChanges: [], decisions: [], ...extra })

test('a detected break is a candidate until evidence or a decision confirms it', () => {
  const s = stepChangeSeries(1, 140, 110, 62, 6, 0, 3)
  const r = base(s)
  assert.equal(r.payload.breaks.length, 1)
  assert.equal(r.payload.breaks[0].status, 'candidate')
  assert.equal(r.payload.lastConfirmed, null)
  assert.equal(r.state, 'Unstable') // break within the last 60 days
})

test('an origin change near the break confirms it; far away does not', () => {
  const s = stepChangeSeries(1, 140, 110, 62, 6, 0, 3)
  const date = base(s).payload.breaks[0].date
  const near = new Date(Date.parse(date + 'T00:00:00Z') + 2 * 86400000).toISOString().slice(0, 10)
  const confirmed = base(s, { originChanges: [near] })
  assert.equal(confirmed.payload.breaks[0].status, 'confirmed')
  assert.equal(confirmed.payload.breaks[0].reason, 'origin_change')
  assert.equal(confirmed.payload.lastConfirmed, date)
  assert.equal(base(s, { originChanges: ['2026-01-05'] }).payload.breaks[0].status, 'candidate')
})

test('user decisions override: source_change confirms, real_change dismisses and calms the state', () => {
  const s = stepChangeSeries(1, 140, 110, 62, 6, 0, 3)
  const date = base(s).payload.breaks[0].date
  assert.equal(base(s, { decisions: [{ break_date: date, decision: 'source_change' }] }).payload.breaks[0].status, 'confirmed')
  const dismissed = base(s, { decisions: [{ break_date: date, decision: 'real_change' }] })
  assert.equal(dismissed.payload.breaks[0].status, 'dismissed')
  assert.equal(dismissed.state, 'Stable')
})

test('stationary and short series: Stable / Building, hash stable and sensitive', () => {
  const flat = stationarySeries(3, 140)
  const r = base(flat)
  assert.equal(r.state, 'Stable')
  assert.deepEqual(r.payload.breaks, [])
  assert.equal(r.inputsHash, base(flat).inputsHash)
  assert.notEqual(r.inputsHash, base(flat.map((v, i) => (i === 5 && v !== null ? v + 1 : v))).inputsHash)
  const short = base(stationarySeries(3, 30))
  assert.equal(short.state, 'Building')
  assert.equal(short.confidenceNeed, 42)
  assert.equal(base([]).asOf, '')
})
