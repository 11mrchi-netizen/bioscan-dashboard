import assert from 'node:assert/strict'
import { test } from 'node:test'
import { type BpRow, categorize, series, sittings } from './bp.ts'

// The same week as android BloodPressureTest: 2 readings morning and evening, the first day higher.
function week(days: number, s: number, d: number, s1 = s, d1 = d): BpRow[] {
  const out: BpRow[] = []
  let id = 1
  for (let i = 0; i < days; i++) {
    const date = `2026-11-${String(10 + i).padStart(2, '0')}`
    const [ss, dd] = i === 0 ? [s1, d1] : [s, d]
    for (const t of ['07:00', '07:01', '19:00', '19:01']) out.push({ id: id++, occurred_at: `${date}T${t}:00+00:00`, systolic: ss, diastolic: dd, pulse: null, source: 'manual' })
  }
  return out
}

test('categories follow ACC/AHA 2017, the higher one winning', () => {
  assert.equal(categorize(119, 79), 'Normal')
  assert.equal(categorize(125, 79), 'Elevated')
  assert.equal(categorize(118, 82), 'Stage 1 hypertension')
  assert.equal(categorize(140, 60), 'Stage 2 hypertension')
})

test('two readings a minute apart are one sitting', () => {
  assert.equal(sittings(week(1, 120, 80)).length, 2)
})

test('the first day is left out and 3 days must remain, as on the phone', () => {
  const w = series(week(7, 120, 76, 150, 95))
  assert.equal(w.length, 1)
  assert.equal(w[0].systolic, 120)
  assert.equal(w[0].readings, 24)
  assert.equal(series(week(3, 120, 76))[0].usable, false)
  assert.equal(series(week(4, 120, 76))[0].usable, true)
})
