import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildPatch, isManual, withWallTime, type FieldSpec } from './logEdit.ts'

const fields: FieldSpec[] = [
  { key: 'logged_at', label: 'Time', kind: 'time' },
  { key: 'description', label: 'What', kind: 'text' },
  { key: 'calories', label: 'kcal', kind: 'number', min: 0, optional: true },
  { key: 'bristol_type', label: 'Bristol type', kind: 'int', min: 1, max: 7 },
  { key: 'meal_slot', label: 'Slot', kind: 'select', options: ['lunch', 'dinner'], optional: true },
]
const row = { logged_at: '2026-11-17T12:30:00+00:00', description: 'Rice', calories: 640, bristol_type: 4, meal_slot: 'lunch' }

test('a wall-clock time keeps its day and its +00 label', () => {
  assert.equal(withWallTime('2026-11-17T12:30:00+00:00', '08:05'), '2026-11-17T08:05:00+00:00')
  assert.throws(() => withWallTime('2026-11-17T12:30:00+00:00', '25:00'))
})

test('only what changed is written, parsed', () => {
  assert.deepEqual(buildPatch(fields, row, { logged_at: '12:30', description: 'Rice ', calories: '700,5' }), { calories: 700.5 })
  assert.deepEqual(buildPatch(fields, row, { logged_at: '13:10', meal_slot: '' }), { logged_at: '2026-11-17T13:10:00+00:00', meal_slot: null })
  assert.deepEqual(buildPatch(fields, row, {}), {})
})

test('bad input says why', () => {
  assert.throws(() => buildPatch(fields, row, { bristol_type: '9' }), /at most 7/)
  assert.throws(() => buildPatch(fields, row, { bristol_type: '3.5' }), /whole number/)
  assert.throws(() => buildPatch(fields, row, { description: '' }), /cannot be empty/)
  assert.throws(() => buildPatch(fields, row, { meal_slot: 'brunch' }), /choose one of/)
})

test('only hand-typed rows can be fixed', () => {
  assert.equal(isManual('manual'), true)
  assert.equal(isManual(null), true)
  assert.equal(isManual('health_connect'), false)
  assert.equal(isManual('zepp'), false)
})
