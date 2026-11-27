// Run: node --test "supabase/functions/_shared/training-core/*.test.ts"
// Mirrors MovementKeyTest.kt: the exercise library says "Trap Bar Deadlift", the definitions say "Trapbar Deadlift".
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { movementKey } from './maxes.ts'

test('trap bar spellings share one key', () => {
  const keys = new Set(['Trap Bar Deadlift', 'Trapbar Deadlift', 'Trap-Bar Deadlift', 'TRAPBAR DEADLIFT', 'Hex Bar Deadlift', 'Trapbar DL', 'Trap Bar Deadlifts'].map(movementKey))
  assert.equal(keys.size, 1)
  assert.notEqual(movementKey('Trap Bar Deadlift'), movementKey('Deadlift'))
})

test('plural and joined spellings fold', () => {
  assert.equal(movementKey('Rack Pull'), movementKey('Rack Pulls'))
  assert.equal(movementKey('Push-ups'), movementKey('Pushups'))
  assert.equal(movementKey('Weighted Pull-up'), movementKey('Weighted Pullups'))
})
