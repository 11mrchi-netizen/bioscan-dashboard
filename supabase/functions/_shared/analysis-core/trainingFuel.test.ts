import assert from 'node:assert/strict'
import { test } from 'node:test'
import { eaBand, energyAvailability, ffmOn, sessionFuel, trainingFuelSection, trainingFuelSuggestions, type TfMeal, type TfSession } from './trainingFuel.ts'

const meal = (at: string, kcal: number, carbsG = 0, proteinG = 0): TfMeal => ({ at, kcal, carbsG, proteinG })
const session = (id: number, at: string, durationMin: number, type = 'run', activeKcal: number | null = 600): TfSession => ({ id, at, type, durationMin, activeKcal })

test('a morning run before breakfast is fasted; food after it counts as recovery', () => {
  const meals = [meal('2026-11-24 21:00:00+00', 600, 70, 30), meal('2026-11-25 09:30:00+00', 500, 60, 35), meal('2026-11-25 10:30:00+00', 150, 10, 20)]
  const [s] = sessionFuel([session(1, '2026-11-25 07:00:00+00', 120)], meals)
  assert.equal(s.hoursSinceMeal, 10)
  assert.equal(s.fasted, true)
  assert.equal(s.long, true)
  assert.equal(s.carbsBefore, 0)
  assert.equal(s.carbsDuring, null)
  // ends 09:00; 09:30 and 10:30 are within 2 h
  assert.equal(s.proteinAfter, 55)
  assert.equal(s.carbsAfter, 70)
})

test('a fed session: carbs in the 4 h before, fuel during from the log, walks left out', () => {
  const meals = [meal('2026-11-25 12:00:00+00', 700, 90, 30), meal('2026-11-25 14:30:00+00', 30, 7, 0)]
  const out = sessionFuel([session(2, '2026-11-25 15:00:00+00', 60), session(3, '2026-11-25 18:00:00+00', 45, 'walk')], meals,
    [{ sessionId: 2, carbsG: 30, status: 'consumed' }, { sessionId: 2, carbsG: 25, status: 'planned' }])
  assert.equal(out.length, 1)
  assert.equal(out[0].hoursSinceMeal, 3)
  assert.equal(out[0].fasted, false)
  assert.equal(out[0].carbsBefore, 97)
  assert.equal(out[0].carbsDuring, 30)
})

test('energy availability: (intake - exercise) / fat-free mass, with the latest body composition', () => {
  const body = [{ date: '2026-11-20', weightKg: 73, bodyFatPct: 20 }]
  assert.equal(ffmOn(body, '2026-11-25'), 58.4)
  assert.equal(ffmOn(body, '2026-11-19'), null)
  assert.equal(ffmOn(body, '2027-02-01'), null) // older than 60 days
  const days = energyAvailability(
    [meal('2026-11-24 08:00:00+00', 1500), meal('2026-11-24 19:00:00+00', 700), meal('2026-11-25 12:00:00+00', 3000)],
    [session(1, '2026-11-24 07:00:00+00', 300, 'run', 3000), session(2, '2026-11-24 18:00:00+00', 60, 'walk', 300)],
    body,
  )
  assert.deepEqual(days.map((d) => [d.date, d.ea, eaBand(d.ea)]), [['2026-11-24', -13.7, 'low'], ['2026-11-25', 51.4, 'adequate']])
})

test('the report section and its fixed-rule suggestions', () => {
  const meals = [meal('2026-11-17 19:00:00+00', 800, 80, 40)]
  const sessions = sessionFuel([session(1, '2026-11-18 07:00:00+00', 150), session(2, '2026-11-19 07:00:00+00', 40), session(3, '2026-11-20 07:00:00+00', 40)], meals)
  const energy = [
    { date: '2026-11-18', intakeKcal: 1500, exerciseKcal: 2000, ffmKg: 58, ea: -8.6 },
    { date: '2026-11-19', intakeKcal: 2000, exerciseKcal: 500, ffmKg: 58, ea: 25.9 },
    { date: '2026-11-20', intakeKcal: 3000, exerciseKcal: 300, ffmKg: 58, ea: 46.6 },
  ]
  const t = trainingFuelSection(sessions, energy, '2026-11-16', '2026-11-22', 73)
  assert.equal(t.proteinDoseG, 22)
  assert.deepEqual(t.counts, { sessions: 3, fasted: 3, long: 1, longFastedNoFuel: 1, proteinAfterOk: 0 })
  assert.deepEqual([t.energy.low, t.energy.reduced, t.energy.adequate, t.energy.median], [2, 0, 1, 25.9])
  assert.deepEqual(t.energy.lowest.map((d) => d.date), ['2026-11-18', '2026-11-19'])
  assert.deepEqual(trainingFuelSuggestions(t, 'week').map((s) => s.id), ['energy_low', 'long_fasted', 'protein_after'])
  assert.deepEqual(trainingFuelSuggestions(t, 'month').map((s) => s.id), ['long_fasted', 'protein_after'])
})
