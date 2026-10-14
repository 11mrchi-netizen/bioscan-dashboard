import assert from 'node:assert/strict'
import { test } from 'node:test'
import { stationarySeries, stepChangeSeries } from './fixtures.ts'
import { detectSeriesBreaks, integerShare, latestBreak, robustSd } from './seriesBreak.ts'
import { dateAxis, gaussian, mulberry32 } from './stats.ts'
import type { Num } from './stats.ts'

const axis = (n: number) => dateAxis('2026-01-01', new Date(Date.UTC(2026, 0, 1) + (n - 1) * 86400000).toISOString().slice(0, 10))

test('robust sd matches the sd of normal data and ignores outliers', () => {
  const r = mulberry32(2)
  const xs = Array.from({ length: 2000 }, () => 10 + 3 * gaussian(r))
  assert.ok(Math.abs(robustSd(xs) - 3) < 0.25)
  assert.ok(Math.abs(robustSd([...xs, 1000, -1000, 900]) - 3) < 0.3)
  assert.equal(integerShare([1, 2.5, 3, 4.2]), 0.5)
})

test('quiet on stationary autocorrelated series (many seeds)', () => {
  let alarms = 0
  for (let seed = 1; seed <= 40; seed++) {
    const s = stationarySeries(seed, 140)
    if (detectSeriesBreaks(axis(140), s).length > 0) alarms++
  }
  assert.ok(alarms <= 2, `false alarms on ${alarms}/40 stationary series`)
})

test('finds a noise break near the planted date (HRV after a sensor change)', () => {
  const s = stepChangeSeries(7, 140, 80, 62, 6, 0, 3)
  const [b] = detectSeriesBreaks(axis(140), s)
  assert.ok(b, 'a break is reported')
  assert.ok(Math.abs(b.index - 80) <= 6, `index ${b.index}`)
  assert.ok(b.kind === 'dispersion' || b.kind === 'both')
  assert.ok(b.after.robustSd > 2 * b.before.robustSd)
})

test('finds a level break near the planted date (stage regime pattern)', () => {
  const s = stepChangeSeries(9, 140, 70, 100, 12, 8, 1) // +8 sd, same noise
  const [b] = detectSeriesBreaks(axis(140), s)
  assert.ok(b && Math.abs(b.index - 70) <= 4)
  assert.ok(b.kind === 'level' || b.kind === 'both')
  assert.ok(b.after.median > b.before.median + 50)
})

test('two regime changes are both found and ordered; gaps are skipped, not filled', () => {
  const r = mulberry32(31)
  const v: Num[] = []
  for (let t = 0; t < 200; t++) {
    const mu = t < 70 ? 100 : t < 140 ? 270 : 75
    v.push(t % 9 === 4 ? null : mu + 15 * gaussian(r))
  }
  const bs = detectSeriesBreaks(axis(200), v)
  assert.equal(bs.length, 2)
  assert.ok(Math.abs(bs[0].index - 70) <= 5 && Math.abs(bs[1].index - 140) <= 5)
  assert.equal(latestBreak(bs), bs[1])
  assert.equal(latestBreak([]), null)
})

test('too little data reports nothing', () => {
  assert.deepEqual(detectSeriesBreaks(axis(30), stationarySeries(1, 30)), [])
})

test('power: a late noise break (30 readings after) is found and located on most seeds', () => {
  let found = 0
  for (let seed = 1; seed <= 20; seed++) {
    const bs = detectSeriesBreaks(axis(140), stepChangeSeries(seed, 140, 110, 62, 6, 0, 3))
    if (bs.some((b) => Math.abs(b.index - 110) <= 7)) found++
  }
  assert.ok(found >= 15, `found ${found}/20`)
})
