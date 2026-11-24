// Run: node --test "src/lib/*.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ageYears, buildFuelDays, eventInput, type FuelRows, loggedInstant } from './fuelInputs.ts'

const zone = 'Asia/Taipei'
const rows = (over: Partial<FuelRows> = {}): FuelRows => ({
  events: [], planned: [], calendar: [], recorded: [], maxima: [], resting: [], overrides: [], links: [], profile: { sex: 'male', date_of_birth: '1994-07-11', nutrition_goals: { caloriesKcal: 2800, proteinG: 150 } },
  body: [{ date: '2026-11-24', weight_kg: 73.8, lean_mass_kg: 58.6, body_fat_pct: 20.5 }], heightCm: 176, trainingMin28: 600, meals: [], ...over,
})

test('a logged time is the zone\'s instant for its digits, not shifted by its zero offset', () => {
  assert.equal(loggedInstant('2026-11-24T08:01:54+00:00', zone), '2026-11-24T08:01:54+08:00[Asia/Taipei]')
  assert.equal(loggedInstant('2026-11-24 13:10:00+00', zone), '2026-11-24T13:10+08:00[Asia/Taipei]')
  assert.equal(loggedInstant('nonsense', zone), null)
})

test('events: keys the phone does not know fall back as its EventKind.of does', () => {
  const e = eventInput({ id: 3, name: 'X', kind: 'weird', sport: null, priority: 'Z', event_date: '2027-03-06', start_time: '2027-03-05T21:00:00+00:00', status: null,
    target_time_min: null, expected_duration_min: 300, distance_km: null, course_profile: [[0, 10], [42000, null]], calendar_event_id: null, exercise_session_id: null })
  assert.deepEqual([e.kind, e.sport, e.priority, e.status, e.plannedDurationMin, e.courseEndM], ['OTHER_HARD_DAY', 'OTHER', 'B', 'PLANNED', 300, 42000])
})

test('age is whole years', () => {
  assert.equal(ageYears('1994-07-11', '2026-11-24'), 32)
  assert.equal(ageYears('1994-12-11', '2026-11-24'), 31)
})

test('no weight, no plan', () => {
  assert.equal(buildFuelDays(rows({ body: [] }), '2026-11-24', '2026-11-24T06:00:00+08:00', zone), null)
})

test('a planned day: easy session, a recording replaces it, the goal sets the energy', () => {
  const r = rows({
    planned: [{ id: 1, scheduled_date: '2026-11-24', start_time: '07:00:00', duration_min: 20, domain: 'aerobic_base', title: 'LSS', gcal_event_id: null, exercise_session_id: null }],
    recorded: [{ id: 9, type: 'run', start_time: '2026-11-24T07:10:00+00:00', duration_min: 22, calories_active: 200, avg_hr: 120, rpe: null, distance_km: 3.2, elevation_gain_m: null }],
  })
  const out = buildFuelDays(r, '2026-11-24', '2026-11-24T09:00:00+08:00', zone)
  assert.ok(out)
  const day = out.days[out.todayIdx]
  assert.equal(day.date, '2026-11-24')
  assert.equal(day.items.length, 1)
  assert.equal(day.items[0].recorded, true)
  assert.equal(day.items[0].planRef, 'planned:1')
  assert.equal(out.energy.dailyKcal, 2800)
  assert.equal(out.plans[out.todayIdx].proteinG, 150)
  assert.equal(out.days.length, 20)
  assert.equal(out.meals.length, 20)
  assert.deepEqual(out.meals[out.todayIdx].meals.map((m) => m.kind), ['BREAKFAST', 'LUNCH', 'SNACK', 'DINNER'])
})

test('the plan\'s own link and the stored answers: the stored answer wins', () => {
  const r = rows({
    planned: [{ id: 1, scheduled_date: '2026-11-24', start_time: '07:00:00', duration_min: 60, domain: 'aerobic_base', title: 'Run', gcal_event_id: null, exercise_session_id: 9 },
      { id: 2, scheduled_date: '2026-11-24', start_time: '18:00:00', duration_min: 60, domain: 'speed', title: 'Track', gcal_event_id: null, exercise_session_id: null }],
    recorded: [{ id: 9, type: 'run', start_time: '2026-11-24T07:10:00+00:00', duration_min: 55, calories_active: null, avg_hr: null, rpe: null, distance_km: null, elevation_gain_m: null }],
    links: [{ exercise_session_id: 9, plan_ref: 'planned:2', plan_title: 'Track', source: 'manual', confidence: 1 }],
  })
  const out = buildFuelDays(r, '2026-11-24', '2026-11-24T09:00:00+08:00', zone)
  assert.ok(out)
  const rec = out.days[out.todayIdx].items.find((i) => i.recorded)
  assert.equal(rec?.planRef, 'planned:2')
})

test('today\'s logged meals are placed by their own label, else by time, and judged against the range', () => {
  const r = rows({
    meals: [
      { logged_at: '2026-11-24T08:50:00+00:00', calories: 600, protein_g: 30, fat_g: 20, carbs_g: 90, meal_slot: 'breakfast' },
      { logged_at: '2026-11-24T12:40:00+00:00', calories: 700, protein_g: 30, fat_g: 20, carbs_g: 20, meal_slot: null },
      { logged_at: '2026-11-24T19:45:00+00:00', calories: 700, protein_g: 30, fat_g: 20, carbs_g: 200, meal_slot: 'nonsense' },
      { logged_at: '2026-11-23T19:45:00+00:00', calories: 700, protein_g: 30, fat_g: 20, carbs_g: 999, meal_slot: 'dinner' },
    ],
  })
  const out = buildFuelDays(r, '2026-11-24', '2026-11-24T21:00:00+08:00', zone)
  assert.ok(out)
  assert.equal(out.logged.BREAKFAST.carbG, 90)
  assert.equal(out.logged.LUNCH.carbG, 20)
  assert.equal(out.logged.LUNCH.status, 'UNDER')
  assert.equal(out.logged.DINNER.carbG, 200)
  assert.equal(out.logged.DINNER.status, 'OVER')
  assert.equal(out.logged.SNACK.status, 'NOT_LOGGED')
})
