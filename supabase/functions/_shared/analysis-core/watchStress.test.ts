import assert from 'node:assert/strict'
import { test } from 'node:test'
import { offsetLookup, samplesOf, watchStressDays } from './watchStress.ts'

const at = (iso: string) => Date.parse(iso)

test('samplesOf reads the extractor body, skips bad items and values', () => {
  const body = { items: [{ data: JSON.stringify([{ time: 1000, value: 30 }, { time: 2000, value: 0 }, { time: 3000, value: 101 }]) }, { data: 'not json' }, { data: null }] }
  assert.deepEqual(samplesOf(body), [{ time: 1000, value: 30 }])
  assert.deepEqual(samplesOf(null), [])
  assert.deepEqual(samplesOf({ items: 'x' }), [])
})

test('watchStressDays regroups by local day (UTC+8: 17:00 UTC belongs to the next day) and skips thin days', () => {
  const samples = []
  // 14 readings at 20:00-21:00 UTC on the 24th = 04:00-05:00 on the 25th local; half of them high
  for (let i = 0; i < 14; i++) samples.push({ time: at('2026-11-24T20:00:00Z') + i * 60_000, value: i % 2 ? 90 : 10 })
  // 3 readings at 10:00 UTC on the 25th (18:00 local the 25th): too few for a summary alone, but the day has 14 + 3
  for (let i = 0; i < 3; i++) samples.push({ time: at('2026-11-25T10:00:00Z') + i * 60_000, value: 50 })
  // a day with 5 readings only
  for (let i = 0; i < 5; i++) samples.push({ time: at('2026-11-20T04:00:00Z') + i * 60_000, value: 70 })
  // a duplicate timestamp counts once
  samples.push(samples[0])
  const days = watchStressDays(samples, () => 480)
  assert.equal(days.length, 1)
  assert.equal(days[0].date, '2026-11-25')
  assert.equal(days[0].n, 17)
  assert.equal(days[0].highShare, 41.2)
  assert.equal(days[0].mean, 50)
})

test('offsetLookup uses the newest offset on or before the date, the first known one before them, else 480', () => {
  const f = offsetLookup([{ date: '2026-11-01', utc_offset_min: 60 }, { date: '2026-11-10', utc_offset_min: 480 }])
  assert.equal(f('2026-11-05'), 60)
  assert.equal(f('2026-11-10'), 480)
  assert.equal(f('2026-10-01'), 60)
  assert.equal(offsetLookup([])('2026-11-05'), 480)
})
