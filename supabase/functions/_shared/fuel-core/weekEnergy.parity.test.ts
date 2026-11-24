// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android week energy: fixtures/week-energy-input.json holds goals, profile and logged data; week-energy-expected.json the
// energy the phone's weekEnergyOf derives from each.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { type DailyNutrition, type MaintenanceInputs, type NutritionGoals, weekEnergyOf } from './weekEnergy.ts'

const dir = new URL('./fixtures/', import.meta.url)
const input = JSON.parse(readFileSync(new URL('week-energy-input.json', dir), 'utf8')) as {
  asOf: string; scenarios: { name: string; goals: NutritionGoals; inputs: MaintenanceInputs; nutritionDays: DailyNutrition[] | null; weights: [string, number][] }[]
}
const expected = JSON.parse(readFileSync(new URL('week-energy-expected.json', dir), 'utf8')) as Record<string, Record<string, unknown>>

for (const s of input.scenarios) {
  test(`week energy: ${s.name}`, () => {
    const got = weekEnergyOf(s.goals, s.inputs, s.nutritionDays, s.weights, input.asOf) as unknown as Record<string, unknown>
    const want = expected[s.name]
    assert.deepEqual(Object.keys(got).sort(), Object.keys(want).sort())
    for (const k of Object.keys(want)) {
      if (typeof want[k] === 'number') assert.ok(Math.abs((got[k] as number) - (want[k] as number)) <= 1e-9 * Math.max(1, Math.abs(want[k] as number)), `${k}: ${got[k]} vs ${want[k]}`)
      else assert.equal(got[k], want[k], k)
    }
  })
}

test('the scenarios reach the observed path and the equation', () => {
  const v = Object.values(expected)
  assert.ok(v.some((e) => e.observedMaintenance === true && e.fromGoal === false), 'observed maintenance without a goal')
  assert.ok(v.some((e) => e.observedMaintenance === false && e.fromGoal === false && e.dailyKcal != null), 'equation without a goal')
  assert.ok(v.some((e) => e.deficit === true) && v.some((e) => e.deficit === false && e.fromGoal === true))
  assert.ok(v.some((e) => e.dailyKcal === null))
})
