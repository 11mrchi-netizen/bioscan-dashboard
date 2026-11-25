// Run: node --test console/src/dashboard/todayLive.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { type DayLogs, livePlan, supplementBucket, wallTime } from './todayLive.ts'
import type { DayPlan } from './todayData.ts'

// 25 Nov 2026 as the phone saved it at 12:31 local (04:31 UTC): breakfast and the run done, nothing after.
const saved: DayPlan = {
  builtAt: '2026-11-25T04:31:07.871983+00:00',
  so_far: { kcal: 387.09, target_kcal: 2949.5, carbs_g: 57.5, protein_g: 36, fat_g: 2 },
  rows: [
    { time: '08:02', title: 'Run', detail: '41 min', done: true, kind: 'SESSION' },
    { time: '09:00', title: 'Breakfast', detail: '640 kcal', done: true, kind: 'MEAL' },
    { time: '12:30', title: 'Lunch', detail: '820 kcal', done: false, kind: 'MEAL' },
    { time: '13:00', title: 'Afternoon supplements', detail: '2 due', done: false, kind: 'SUPPLEMENTS' },
    { time: '16:00', title: 'Snack', detail: '300 kcal', done: false, kind: 'MEAL' },
    { time: '19:30', title: 'Dinner', detail: '900 kcal', done: false, kind: 'MEAL' },
    { time: '22:25', title: 'Close the day', done: false, kind: 'CLOSE' },
    { time: '23:41', title: 'Bed by', done: false, kind: 'CLOSE' },
  ],
}
const meal = (logged_at: string, meal_slot: string | null, calories: number, carbs_g = 0, protein_g = 0, fat_g = 0) =>
  ({ logged_at, meal_slot, calories, carbs_g, protein_g, fat_g, description: null })
const logs = (over: Partial<DayLogs> = {}): DayLogs => ({ meals: [], sessions: [], supplements: [], routines: [], breathwork: [], closed: false, closedBefore: false, ...over })
const row = (p: ReturnType<typeof livePlan>, title: string) => p.rows.find((r) => r.title === title)!

test('wall clock times are read by their digits, never shifted', () => {
  assert.equal(wallTime('2026-11-25T12:55:31.418866+00:00'), '12:55')
  assert.equal(wallTime('2026-11-25 07:05:00+00'), '07:05')
  assert.equal(wallTime(null), undefined)
  assert.equal(supplementBucket('03:59'), null)
  assert.equal(supplementBucket('10:59'), 'morning')
  assert.equal(supplementBucket('13:29'), 'afternoon')
  assert.equal(supplementBucket('17:00'), 'night')
})

test('meals logged after the phone saved the plan tick their rows and count in SO FAR', () => {
  const p = livePlan(saved, logs({ meals: [
    meal('2026-11-25T10:46:24+00:00', 'breakfast', 387.09, 57.5, 36, 2),
    meal('2026-11-25T12:55:31+00:00', 'lunch', 435.7, 26.93, 47.89, 14.05),
    meal('2026-11-25T13:29:38+00:00', 'lunch', 319.68, 46.88, 2.68, 14.54),
    meal('2026-11-25T15:02:36+00:00', 'snack', 393.16, 27.51, 10.79, 27.61),
  ] }))
  assert.equal(row(p, 'Lunch').done, true)
  assert.equal(row(p, 'Snack').done, true)
  assert.equal(row(p, 'Dinner').done, false)
  assert.equal(Math.round(p.so_far!.kcal), 1536)
  assert.equal(p.so_far!.target_kcal, 2949.5)
  assert.equal(Math.round(p.so_far!.protein_g), 97)
  assert.equal(p.rows.length, saved.rows.length, 'no extra rows: every meal had its slot')
})

test('an unlabelled meal goes to the planned meal nearest in time; a slot with no row is added as logged', () => {
  const p = livePlan(saved, logs({ meals: [meal('2026-11-25T19:10:00+00:00', null, 700), meal('2026-11-25T17:30:00+00:00', 'pre_session', 150)] }))
  assert.equal(row(p, 'Dinner').done, true)
  const pre = row(p, 'Pre-session')
  assert.equal(pre.logged, true)
  assert.equal(pre.time, '17:30')
  assert.equal(pre.detail, '150 kcal')
  // Sorted in among the plan by time.
  assert.deepEqual(p.rows.map((r) => r.time).slice(4, 7), ['16:00', '17:30', '19:30'])
})

test('a workout near a planned session ticks it; one far from any plan row, or a duplicate, is handled', () => {
  const plan: DayPlan = { ...saved, rows: [{ time: '18:00', title: 'Strength', done: false, kind: 'SESSION' }] }
  const p = livePlan(plan, logs({ sessions: [
    { start_time: '2026-11-25T08:02:41+00:00', type: 'run', duration_min: 41 },
    { start_time: '2026-11-25T08:05:00+00:00', type: 'run', duration_min: 40 },
    { start_time: '2026-11-25T17:40:00+00:00', type: 'strength', duration_min: 55 },
  ] }))
  assert.equal(row(p, 'Strength').done, true)
  const run = p.rows.filter((r) => r.title === 'Run')
  assert.equal(run.length, 1, 'the second copy of the run is the same workout from another source')
  assert.equal(run[0].logged, true)
  assert.equal(run[0].detail, '41 min')
})

test('supplements count down by what was logged in their block after the plan was saved', () => {
  const one = livePlan(saved, logs({ supplements: [{ taken_at: '2026-11-25T13:29:07+00:00', created_at: '2026-11-25T05:29:07+00:00' }] }))
  assert.equal(row(one, 'Afternoon supplements').detail, '1 due')
  const all = livePlan(saved, logs({ supplements: [
    { taken_at: '2026-11-25T13:29:07+00:00', created_at: '2026-11-25T05:29:07+00:00' },
    { taken_at: '2026-11-25T13:29:07+00:00', created_at: '2026-11-25T05:29:09+00:00' },
    // Before the save: already left out of the phone's "2 due".
    { taken_at: '2026-11-25T11:58:00+00:00', created_at: '2026-11-25T03:58:00+00:00' },
  ] }))
  assert.equal(row(all, 'Afternoon supplements').done, true)
  assert.equal(row(all, 'Afternoon supplements').detail, 'taken')
})

test('the evening check-in closes the day; routines and breathwork are added as logged activities', () => {
  const p = livePlan({ ...saved, rows: [...saved.rows, { title: 'Close Tuesday', detail: '1 min', done: false, kind: 'CLOSE' }] }, logs({
    closed: true, closedBefore: true,
    routines: [{ started_at: '2026-11-25T14:00:00+00:00', minutes: 20, name: 'Mobility' }],
    breathwork: [{ started_at: '2026-11-25T21:30:00+00:00', duration_s: 300, pattern_name: 'Box' }],
  }))
  assert.equal(row(p, 'Close the day').done, true)
  assert.equal(row(p, 'Close Tuesday').done, true)
  assert.equal(row(p, 'Bed by').done, false)
  assert.equal(row(p, 'Mobility').kind, 'ACTIVITY')
  assert.equal(row(p, 'Breathwork · Box').detail, '5 min')
  assert.equal(p.rows[0].title, 'Close Tuesday', 'untimed rows first, as on the phone')
})

test('a day the phone never saved still shows what was logged', () => {
  const p = livePlan(null, logs({ meals: [meal('2026-11-25T09:00:00+00:00', 'breakfast', 500, 60, 30, 10)] }))
  assert.equal(p.builtAt, null)
  assert.equal(p.rows.length, 1)
  assert.equal(p.rows[0].title, 'Breakfast')
  assert.equal(p.so_far!.kcal, 500)
  assert.equal(p.so_far!.target_kcal, undefined)
})
