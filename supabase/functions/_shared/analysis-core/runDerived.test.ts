import assert from 'node:assert/strict'
import { test } from 'node:test'
import { derivedRows } from './runDerived.ts'

test('derived rows: the digestion score and energy availability per day, and the food around each session', () => {
  const r = derivedRows('u', {
    stool: [{ occurred_at: '2026-11-24 08:00:00+00', bristol_type: 4 }],
    checkins: [{ date: '2026-11-23', gut_comfort: 2, gut_symptoms: ['gas'] }],
    meals: [
      { at: '2026-11-23 19:00:00+00', kcal: 700, carbsG: 80, proteinG: 40 },
      { at: '2026-11-24 10:30:00+00', kcal: 600, carbsG: 70, proteinG: 35 },
    ],
    sessions: [{ id: 7, at: '2026-11-24 07:00:00+00', type: 'run', durationMin: 95, activeKcal: 900 }],
    body: [{ date: '2026-11-01', weightKg: 70, bodyFatPct: 15 }],
    fuel: [],
  }, '2026-11-23', '2026-11-24')
  assert.deepEqual(r.daily.filter((d) => d.metric === 'gut_day_score').map((d) => [d.date, d.value]), [['2026-11-23', 40], ['2026-11-24', 100]])
  const ea = r.daily.filter((d) => d.metric === 'energy_availability')
  // ffm 59.5 kg: 23rd 700 / 59.5 = 11.8; 24th (600 - 900) / 59.5 = -5
  assert.deepEqual(ea.map((d) => [d.date, d.value]), [['2026-11-23', 11.8], ['2026-11-24', -5]])
  assert.deepEqual(ea[1].detail, { intake_kcal: 600, exercise_kcal: 900, ffm_kg: 59.5 })
  assert.equal(r.sessions.length, 1)
  const s = r.sessions[0]
  assert.deepEqual([s.session_id, s.long, s.fasted, s.hours_since_meal, s.carbs_before_g, s.protein_after_g, s.protein_dose_g], [7, true, true, 12, 0, 35, 21])
})

test('derived rows: the logged energy balance only on days the log looks complete and the watch has a total', () => {
  const r = derivedRows('u', {
    stool: [], checkins: [],
    meals: [
      { at: '2026-11-23 08:00:00+00', kcal: 800, carbsG: 90, proteinG: 40 },
      { at: '2026-11-23 19:00:00+00', kcal: 900, carbsG: 90, proteinG: 40 },
      { at: '2026-11-24 12:00:00+00', kcal: 600, carbsG: 70, proteinG: 35 }, // under 1,200 kcal: not a complete log
    ],
    sessions: [], body: [{ date: '2026-11-01', weightKg: 70, bodyFatPct: 15 }], fuel: [],
    expenditure: [{ date: '2026-11-23', kcal: 2300 }, { date: '2026-11-24', kcal: 2300 }],
  }, '2026-11-23', '2026-11-24')
  const b = r.daily.filter((d) => d.metric === 'energy_balance')
  assert.deepEqual(b.map((d) => [d.date, d.value]), [['2026-11-23', -600]])
  assert.deepEqual(b[0].detail, { intake_kcal: 1700, expenditure_kcal: 2300 })
})
