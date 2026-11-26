import assert from 'node:assert/strict'
import { test } from 'node:test'
import { caffeineBoardSeries, sleepOutcomeSeries } from './sleepExposures.ts'

const local = (stamp: string) => Date.parse(stamp) + 480 * 60000 // UTC+8

test('caffeine on board at bedtime: decayed with a 5 h half-life, zero on a meal day without caffeine, unknown without bedtime or meals', () => {
  const axis = ['2026-11-20', '2026-11-21', '2026-11-22', '2026-11-23']
  const meals = [
    // 20th: 200 mg at 15:00 (wall clock) and bedtime 23:00 local = 15:00 UTC -> 8 h later: 200 * 0.5^(8/5)
    { logged_at: '2026-11-20T15:00:00+00', meal_items: [{ caffeine_mg: 200 }] },
    // 21st: meals without caffeine
    { logged_at: '2026-11-21T09:00:00+00', meal_items: [{ caffeine_mg: null }] },
    // 22nd: caffeine but no sleep row after it
    { logged_at: '2026-11-22T09:00:00+00', meal_items: [{ caffeine_mg: 100 }] },
  ]
  const sleep = [
    { date: '2026-11-21', bedtime: '2026-11-20T15:00:00Z' }, // 23:00 local on the 20th
    { date: '2026-11-22', bedtime: '2026-11-21T15:00:00Z' },
  ]
  // the 20th: dose at wall 15:00, bedtime 23:00 local = 8 h; the stamp's UTC digits 15:00 are local 23:00 after +8
  const s = caffeineBoardSeries(axis, meals, sleep, local)
  assert.equal(s[0], Math.round(200 * 0.5 ** (8 / 5)))
  assert.ok(s[0]! > 60 && s[0]! < 70)
  assert.equal(s[1], 0)
  assert.equal(s[2], null)
  assert.equal(s[3], null)
})

test('sleep outcomes: deep share, local bedtime minutes after 18:00, dip and SpO2 with plausibility limits', () => {
  const axis = ['2026-11-25', '2026-11-26']
  const o = sleepOutcomeSeries(
    axis,
    [{ date: '2026-11-26', deep_min: 60, rem_min: 90, light_min: 250, bedtime: '2026-11-25T15:57:00Z' }, { date: '2026-11-25', deep_min: 10, rem_min: 5, light_min: 20, bedtime: '2026-11-24T20:00:00Z' }],
    [{ date: '2026-11-26', dip_pct: 31.2 }, { date: '2026-11-25', dip_pct: 120 }],
    [{ date: '2026-11-26', spo2_avg: 95.5 }, { date: '2026-11-25', spo2_avg: 50 }],
    local,
  )
  assert.deepEqual(o.deep_share, [null, 15])
  assert.deepEqual(o.hr_dip, [null, 31.2])
  assert.deepEqual(o.spo2, [null, 95.5])
  // 15:57 UTC = 23:57 local = 357 min after 18:00; 20:00 UTC = 04:00 local next day = 600
  assert.deepEqual(o.bedtime_min, [600, 357])
})
