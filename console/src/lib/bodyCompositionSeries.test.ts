// Run: node --test console/src/lib/bodyCompositionSeries.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ageOn, buildPoints, dailyLast, goodWeighIns, impedanceIndexByTimeOfDay } from './bodyCompositionSeries.ts'

const profile = { date_of_birth: '1996-10-30', sex: 'male', height_cm: 180 }

test('age counts whole years on the day', () => {
  assert.equal(ageOn('1996-10-30', '2026-10-29'), 29)
  assert.equal(ageOn('1996-10-30', '2026-10-30'), 30)
  assert.equal(ageOn('bad', '2026-10-30'), null)
})

test('the captured weigh-in is used, a glitch is rejected, missing pieces are named', () => {
  const rows = [
    { occurred_at: '2026-10-29T07:30:00+00:00', weight_kg: 75, impedance_ohm: 503 },
    { occurred_at: '2026-10-29T21:00:00+00:00', weight_kg: 75, impedance_ohm: 50 },
    { occurred_at: '2026-10-28T07:00:00+00:00', weight_kg: 75, impedance_ohm: null },
  ]
  const pts = buildPoints(rows, profile, null)
  assert.deepEqual(pts.map((p) => p.status), ['no-impedance', 'ok', 'rejected'])
  assert.ok(Math.abs(pts[1].composition!.split!.bodyFatPct - 19) < 0.05) // age 29 on 2026-10-29
  assert.equal(buildPoints(rows, null, null)[1].status, 'no-profile')
  assert.equal(buildPoints(rows, { ...profile, height_cm: null }, 178)[1].status, 'ok') // height falls back to body_metrics
  assert.equal(buildPoints(rows, { ...profile, height_cm: null }, null)[1].status, 'no-profile')
})

test('daily series keeps the last weigh-in of a day; good weigh-ins exclude rejected ones', () => {
  const rows = [
    { occurred_at: '2026-10-29T07:30:00+00:00', weight_kg: 75, impedance_ohm: 503 },
    { occurred_at: '2026-10-29T20:00:00+00:00', weight_kg: 76, impedance_ohm: 495 },
    { occurred_at: '2026-10-30T07:00:00+00:00', weight_kg: 75, impedance_ohm: 50 },
  ]
  const pts = buildPoints(rows, profile, null)
  assert.deepEqual(dailyLast(pts, (p) => p.weightKg), [['2026-10-29', 76], ['2026-10-30', 75]])
  assert.equal(goodWeighIns(pts).length, 2)
})

test('impedance index by time of day groups into 3-hour buckets', () => {
  const rows = [
    { occurred_at: '2026-10-29T07:30:00+00:00', weight_kg: 75, impedance_ohm: 500 },
    { occurred_at: '2026-10-30T08:10:00+00:00', weight_kg: 75, impedance_ohm: 520 },
    { occurred_at: '2026-10-29T21:00:00+00:00', weight_kg: 75, impedance_ohm: 480 },
  ]
  const b = impedanceIndexByTimeOfDay(buildPoints(rows, profile, null))
  assert.deepEqual(b.map((x) => [x.label, x.n]), [['06–09h', 2], ['21–24h', 1]])
})
