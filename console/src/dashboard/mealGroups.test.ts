// Run: node --test console/src/dashboard/mealGroups.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { groupMeals } from './mealGroups.ts'

const e = (logged_at: string, meal_slot: string | null) => ({ logged_at, meal_slot })

test('two things logged for the same meal are one meal', () => {
  const g = groupMeals([e('2026-11-24T12:54:47+00:00', 'lunch'), e('2026-11-24T12:42:02+00:00', 'lunch')])
  assert.equal(g.length, 1)
  assert.equal(g[0].length, 2)
  assert.equal(g[0][0].logged_at, '2026-11-24T12:42:02+00:00', 'in time order')
})

test('an hour or less joins, a longer gap starts a new meal', () => {
  assert.equal(groupMeals([e('2026-11-25T12:55:00+00:00', 'lunch'), e('2026-11-25T13:55:00+00:00', 'lunch')]).length, 1)
  assert.equal(groupMeals([e('2026-11-25T12:55:00+00:00', 'lunch'), e('2026-11-25T13:56:00+00:00', 'lunch')]).length, 2)
})

test('entries chain: each follows the last one of its meal', () => {
  assert.equal(groupMeals([e('2026-11-24T20:00:00+00:00', 'dinner'), e('2026-11-24T20:50:00+00:00', 'dinner'), e('2026-11-24T21:40:00+00:00', 'dinner')]).length, 1)
})

test('different labels stay separate meals', () => {
  const g = groupMeals([e('2026-11-19T21:07:00+00:00', 'dinner'), e('2026-11-19T21:16:00+00:00', 'snack')])
  assert.equal(g.length, 2)
})

test('an unlabelled entry joins the meal before it, and a later label then must match', () => {
  const g = groupMeals([e('2026-11-18T09:57:00+00:00', null), e('2026-11-18T09:58:00+00:00', null), e('2026-11-18T10:20:00+00:00', 'breakfast'), e('2026-11-18T10:30:00+00:00', 'snack')])
  assert.deepEqual(g.map((x) => x.length), [3, 1])
})

test('the gap is read across midnight too', () => {
  assert.equal(groupMeals([e('2026-11-24T23:50:00+00:00', null), e('2026-11-25T00:20:00+00:00', null)]).length, 1)
  assert.equal(groupMeals([e('2026-11-24T22:50:00+00:00', null), e('2026-11-25T08:20:00+00:00', null)]).length, 2)
})
