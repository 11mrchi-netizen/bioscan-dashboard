// Run: node --test "supabase/functions/_shared/training-core/gear.test.ts"
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { cleanGear, describeRules, gearComparisons, gearForLoad, recordedGear, suggestGear, type GearRule, type GearSetRow } from './gear.ts'
import { movementKey } from './maxes.ts'

const dl = movementKey('Deadlift')
const rules: GearRule[] = [
  { movement_key: dl, gear: 'straps', from_kg: 140 },
  { movement_key: dl, gear: 'belt', from_kg: 160 },
  { movement_key: movementKey('Back Squat'), gear: 'belt', from_kg: null },
]

test('rules apply from their threshold upward, per lift, spelled any way the lift is logged', () => {
  assert.deepEqual(gearForLoad(rules, 'Deadlift', 139.9), [])
  assert.deepEqual(gearForLoad(rules, 'Deadlift', 140), ['straps'])
  assert.deepEqual(gearForLoad(rules, 'Barbell Deadlift', 180), ['straps', 'belt'])
  assert.deepEqual(gearForLoad(rules, 'Back Squat', 20), ['belt'])
  assert.deepEqual(gearForLoad(rules, 'Back Squat', null), ['belt'])
  assert.deepEqual(gearForLoad(rules, 'Deadlift', null), [], 'a threshold rule needs a weight')
  assert.deepEqual(gearForLoad(rules, 'Bench Press', 100), [])
})

test('rules read as a line, lowest threshold first', () => {
  assert.equal(describeRules(rules, 'Deadlift'), 'straps from 140 kg · belt from 160 kg')
  assert.equal(describeRules(rules, 'Back Squat'), 'belt')
  assert.equal(describeRules(rules, 'Row'), '')
})

test('absent gear is "not recorded", an empty list is "recorded: none"', () => {
  assert.equal(recordedGear({}), null)
  assert.equal(recordedGear({ gear: 'belt' }), null)
  assert.deepEqual(recordedGear({ gear: [] }), [])
  assert.deepEqual(recordedGear({ gear: ['belt', 'nonsense', 'straps', 'belt'] }), ['straps', 'belt'])
  assert.deepEqual(cleanGear(['belt', 'x', 'straps']), ['straps', 'belt'])
})

const set = (weight_kg: number, reps: number, gear?: string[], rpe?: number): GearSetRow => ({ name: 'Deadlift', weight_kg, reps, rpe, gear })

test('the comparison uses recorded sets only, and only the load range both sides share', () => {
  const rows: GearSetRow[] = [
    set(100, 5, []), set(100, 5), // the unrecorded one is ignored
    set(140, 5, []), set(150, 4, []), set(160, 3, []),
    set(150, 5, ['straps']), set(160, 4, ['straps']), set(180, 3, ['straps']),
  ]
  const [c] = gearComparisons(rows).filter((x) => x.gear === 'straps')
  assert.equal(c.with.sets, 3)
  assert.equal(c.without.sets, 4)
  assert.deepEqual(c.overlapKg, [150, 160])
  assert.equal(c.inOverlap?.with.sets, 2)
  assert.equal(c.inOverlap?.without.sets, 2)
  assert.equal(c.enough, false, 'two sets a side is not enough to read')
})

test('no overlap, or a lift with no gear recorded, gives nothing to compare fairly', () => {
  const apart = gearComparisons([set(100, 5, []), set(200, 3, ['belt'])])
  assert.equal(apart.length, 1)
  assert.equal(apart[0].overlapKg, null)
  assert.equal(apart[0].enough, false)
  assert.deepEqual(gearComparisons([set(100, 5), set(120, 5)]), [])
  assert.deepEqual(gearComparisons([set(100, 5, ['belt'])]), [], 'nothing without the gear to compare with')
})

test('eight sets a side inside the shared range is enough', () => {
  const rows: GearSetRow[] = []
  for (let i = 0; i < 8; i++) rows.push(set(150, 5, [], 8), set(150, 6, ['straps'], 7))
  const [c] = gearComparisons(rows)
  assert.equal(c.enough, true)
  assert.equal(c.inOverlap?.with.meanReps, 6)
  assert.equal(c.inOverlap?.without.meanRpe, 8)
  assert.equal(c.with.bestE1rmKg, 180)
})

test('a pre-fill leaves a lift with no rules not recorded, instead of recording "no gear"', () => {
  assert.equal(suggestGear(rules, 'Bench Press', 100), null)
  assert.deepEqual(suggestGear(rules, 'Deadlift', 100), [], 'a lift with rules: below the threshold is a real "none"')
  assert.deepEqual(suggestGear(rules, 'Deadlift', 150), ['straps'])
})
