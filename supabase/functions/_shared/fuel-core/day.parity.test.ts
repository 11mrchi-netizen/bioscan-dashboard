// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android day fuel engine: fixtures/day-input.json holds training days as the phone serialises a PlannedDay and
// fixtures/day-expected.json what the Kotlin DayFuelPlanParityTest plans from them. Every number, reason and note must match.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fatFreeMass, fmt, type PlannedDay, planFuelDays, planInWeekBlocks, roundToInt, type WeekEnergy } from './day.ts'

const dir = new URL('./fixtures/', import.meta.url)
const input = JSON.parse(readFileSync(new URL('day-input.json', dir), 'utf8')) as {
  name: string; weightKg: number; fatFreeMassKg: number | null; energy: WeekEnergy; modes: (number | null)[]; days: PlannedDay[]
}[]
const expected = JSON.parse(readFileSync(new URL('day-expected.json', dir), 'utf8')) as Record<string, unknown[]>

function same(a: unknown, b: unknown, path = '$'): void {
  if (typeof a === 'number' && typeof b === 'number') { assert.ok(Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(b)), `${path}: ${a} vs ${b}`); return }
  if (Array.isArray(a) && Array.isArray(b)) { assert.equal(a.length, b.length, `${path}: length`); a.forEach((x, i) => same(x, b[i], `${path}[${i}]`)); return }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    assert.deepEqual(Object.keys(a).sort(), Object.keys(b).sort(), `${path}: keys`)
    for (const k of Object.keys(a)) same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], `${path}.${k}`)
    return
  }
  assert.equal(a, b, path)
}

test('the fixtures cover what they should', () => {
  assert.ok(input.length > 90)
  const text = JSON.stringify(expected)
  for (const needle of [
    'Rest day', 'Tomorrow morning', 'Carbohydrate loading', 'kept at', 'Energy raised to', 'held at', 'Capped at', 'moved here from',
    'Deficit goal on a key', 'under 30', 'No calorie goal', 'Loading day:',
  ]) assert.ok(text.includes(needle), `no scenario reaches: ${needle}`)
})

for (const s of input) {
  test(`day plans: ${s.name}`, () => {
    s.modes.forEach((mode, k) => {
      const plans = mode === null ? planFuelDays(s.days, s.energy, s.weightKg, s.fatFreeMassKg) : planInWeekBlocks(s.days, mode, s.energy, s.weightKg, s.fatFreeMassKg)
      same(JSON.parse(JSON.stringify(plans)), expected[s.name][k], `${s.name}/${mode}`)
    })
  })
}

test('fmt follows Java: half up on the shortest decimal', () => {
  assert.equal(fmt(1.005, 2), '1.01')
  assert.equal(fmt(0.125, 2), '0.13')
  assert.equal(fmt(6.25, 1), '6.3')
  assert.equal(fmt(6.35, 1), '6.4')
  assert.equal(fmt(29.5, 0), '30')
  assert.equal(fmt(-2.5, 0), '-3')
  assert.equal(fmt(9.96, 1), '10.0')
  assert.equal(fmt(0, 1), '0.0')
  assert.equal(fmt(1e-7, 1), '0.0')
  assert.equal(roundToInt(-2.5), -2)
})

test('fat-free mass', () => {
  assert.equal(fatFreeMass(80, 60, 10), 60)
  assert.equal(fatFreeMass(80, null, 20), 64)
  assert.equal(fatFreeMass(80, null, 1), null)
  assert.equal(fatFreeMass(null, null, 20), null)
})
