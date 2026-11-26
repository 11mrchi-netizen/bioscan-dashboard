import assert from 'node:assert/strict'
import { test } from 'node:test'
import { eatingDay, eveningGutPoints, gutDayScore, mealSeries, stoolPoints, wallMs } from './mealExposures.ts'

// Taipei: a bedtime instant at 16:30 UTC is 00:30 local.
const taipei = (stamp: string) => Date.parse(stamp) + 8 * 3600000

test('a meal in the small hours belongs to the evening before', () => {
  assert.equal(eatingDay(wallMs('2026-11-24 23:30:00+00')), '2026-11-24')
  assert.equal(eatingDay(wallMs('2026-11-25 01:10:00+00')), '2026-11-24')
  assert.equal(eatingDay(wallMs('2026-11-25 07:00:00+00')), '2026-11-25')
})

test('meal timing: gap to sleep, food before bed, eating window and daily sums', () => {
  const axis = ['2026-11-23', '2026-11-24']
  const meals = [
    { logged_at: '2026-11-24 08:00:00+00', calories: 500, protein_g: 40, carbs_g: 60, fat_g: 10, fiber_g: 8 },
    { logged_at: '2026-11-24 13:00:00+00', calories: 700, protein_g: 45, carbs_g: 80, fat_g: 20, fiber_g: 6 },
    { logged_at: '2026-11-24 22:30:00+00', calories: 300, protein_g: 5, carbs_g: 40, fat_g: 12, fiber_g: null },
    { logged_at: '2026-11-24 23:50:00+00', calories: 5, protein_g: 0, carbs_g: 1, fat_g: 0 }, // a tea: not a meal
  ]
  // the night after the 24th is the sleep row dated the 25th; asleep at 00:30 local
  const sleep = [{ date: '2026-11-25', bedtime: '2026-11-24T16:30:00Z' }]
  const s = mealSeries(axis, meals, sleep, taipei)
  assert.deepEqual(s.meal_gap_h, [null, 2])
  assert.deepEqual(s.kcal_before_bed, [null, 305])
  assert.deepEqual(s.eating_window_h, [null, 14.5])
  assert.deepEqual(s.protein_day, [null, 90])
  assert.deepEqual(s.carbs_day, [null, 181])
  assert.deepEqual(s.fat_day, [null, 42])
  // 1,200 of 1,505 kcal carry fibre (80%): counted
  assert.deepEqual(s.fiber_day, [null, 14])
})

test('meal timing: no bedtime leaves timing unknown; a mostly unfibred day leaves fibre unknown', () => {
  const s = mealSeries(['2026-11-24'], [
    { logged_at: '2026-11-24 12:00:00+00', calories: 800, fiber_g: null },
    { logged_at: '2026-11-24 19:00:00+00', calories: 200, fiber_g: 3 },
  ], [], taipei)
  assert.deepEqual(s.meal_gap_h, [null])
  assert.deepEqual(s.kcal_before_bed, [null])
  assert.deepEqual(s.fiber_day, [null])
  assert.deepEqual(s.eating_window_h, [7])
})

test('stool points: form, discomfort and evacuation', () => {
  assert.equal(stoolPoints({ occurred_at: 'x', bristol_type: 4 }), 100)
  assert.equal(stoolPoints({ occurred_at: 'x', bristol_type: 6, discomfort: 2, complete_evacuation: 'partly' }), 44)
  assert.equal(stoolPoints({ occurred_at: 'x', bristol_type: 7, discomfort: 10, complete_evacuation: 'no' }), 0)
  assert.equal(stoolPoints({ occurred_at: 'x', bristol_type: null }), null)
  assert.equal(eveningGutPoints({ date: 'd', gut_comfort: 3, gut_symptoms: ['gas'] }), 65)
  assert.equal(eveningGutPoints({ date: 'd', gut_comfort: null, gut_symptoms: ['gas'] }), null)
})

test('the daily digestion score averages the stools and the evening answer, and is unknown without either', () => {
  const axis = ['2026-11-22', '2026-11-23', '2026-11-24']
  const stool = [
    { occurred_at: '2026-11-23 07:40:00+00', bristol_type: 4 },
    { occurred_at: '2026-11-23 11:00:00+00', bristol_type: 6 },
    { occurred_at: '2026-11-24 08:00:00+00', bristol_type: 3 },
  ]
  const checkins = [{ date: '2026-11-23', gut_comfort: 4, gut_symptoms: [] }, { date: '2026-11-22', gut_comfort: 2, gut_symptoms: ['gas', 'urgency'] }]
  // 22nd: evening only (50 - 20); 23rd: stools (100 + 60) / 2 = 80 and evening 100 -> 90; 24th: stool 90
  assert.deepEqual(gutDayScore(axis, stool, checkins), [30, 90, 90])
  assert.deepEqual(gutDayScore(['2026-11-21'], stool, checkins), [null])
})

test('the digestion score takes points off for more stools than usual and an unusual time', () => {
  // ten usual days: one stool at 08:00
  const usual = Array.from({ length: 10 }, (_, i) => ({ occurred_at: `2026-11-${String(10 + i).padStart(2, '0')} 08:00:00+00`, bristol_type: 4 }))
  // the 20th: three stools (one beyond usual + 1), the first at 13:00 (5 h off, 3 h beyond the 2 h allowed: 15 points)
  const day = [13, 15, 18].map((h) => ({ occurred_at: `2026-11-20 ${h}:00:00+00`, bristol_type: 4 }))
  assert.deepEqual(gutDayScore(['2026-11-19', '2026-11-20'], [...usual, ...day], []), [100, 75])
  // with too few usual days there is no baseline
  assert.deepEqual(gutDayScore(['2026-11-20'], [...usual.slice(0, 3), ...day], []), [100])
})
