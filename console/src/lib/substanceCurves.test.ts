import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  alcoholSeries, caffeineAt, caffeineBelowAt, caffeinePeakMin, caffeineSeries, clockLabel, usualBedtimeMin, wallParts,
} from './substanceCurves.ts'

test('caffeine: nothing before the dose, peak about 50 min after it, half left after the half-life past the peak region', () => {
  const d = [{ at: 600, amount: 100 }]
  assert.equal(caffeineAt(d, 600), 0)
  const peak = caffeinePeakMin()
  assert.ok(peak > 40 && peak < 65, `peak ${peak}`)
  const top = caffeineAt(d, 600 + peak)
  assert.ok(top > 85 && top < 100, `top ${top}`)
  // Long after absorption the amount halves every 5 h.
  const a = caffeineAt(d, 600 + 6 * 60), b = caffeineAt(d, 600 + 11 * 60)
  assert.ok(Math.abs(b / a - 0.5) < 0.01)
})

test('caffeine: doses add up, a dose the evening before still counts', () => {
  const one = caffeineAt([{ at: -120, amount: 200 }], 0)
  assert.ok(one > 100 && one < 160, `${one}`)
  assert.ok(Math.abs(caffeineAt([{ at: 0, amount: 100 }, { at: 0, amount: 100 }], 120) - 2 * caffeineAt([{ at: 0, amount: 100 }], 120)) < 1e-9)
})

test('caffeine: when it falls below the bedtime line', () => {
  const t = caffeineBelowAt([{ at: 11 * 60, amount: 100 }], 50, 0)
  assert.ok(t != null && t > 11 * 60 + 5 * 60 && t < 11 * 60 + 7 * 60, `${t}`)
  assert.equal(caffeineBelowAt([], 50, 0), 0)
})

test('caffeine series', () => {
  const s = caffeineSeries([{ at: 60, amount: 100 }], 0, 120, 60)
  assert.deepEqual(s.map((p) => p.t), [0, 60, 120])
  assert.equal(s[0].v, 0)
})

test('alcohol: rises, then falls in a straight line at 7 g an hour, and is gone', () => {
  const s = alcoholSeries([{ at: 20 * 60, amount: 28 }], 19 * 60, 30 * 60, 5)
  const peak = Math.max(...s.map((p) => p.v))
  assert.ok(peak > 15 && peak < 28, `${peak}`)
  const at = (t: number) => s.find((p) => p.t === t)!.v
  assert.ok(Math.abs((at(22 * 60 + 30) - at(23 * 60 + 30)) - 7) < 0.3)
  assert.equal(at(30 * 60), 0)
  assert.equal(at(19 * 60), 0)
})

test('alcohol: a drink the evening before is cleared by morning', () => {
  const s = alcoholSeries([{ at: -180, amount: 14 }], 0, 360, 60)
  assert.equal(s[0].v, 0)
})

test('usual bedtime: median of recent nights in the zone of each night, after midnight counted past 24:00', () => {
  const night = (utc: string) => ({ bedtime: utc, tz_offset_min: 480 })
  // 23:57, 00:33, 23:00 local -> 1437, 1473, 1380 -> median 1437
  assert.equal(usualBedtimeMin([night('2026-11-25T15:57:00Z'), night('2026-11-24T16:33:00Z'), night('2026-11-23T15:00:00Z')]), 1437)
  assert.equal(usualBedtimeMin([night('2026-11-25T15:57:00Z')]), null)
})

test('wall clock helpers', () => {
  assert.deepEqual(wallParts('2026-11-24 10:36:22.508766+00'), { day: '2026-11-24', min: 636 })
  assert.equal(clockLabel(1437), '23:57')
  assert.equal(clockLabel(1500), '01:00')
})
