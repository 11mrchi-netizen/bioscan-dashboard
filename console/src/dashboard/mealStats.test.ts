// Run: node --test console/src/dashboard/mealStats.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { macroSplit, rankMicros, standing } from './mealStats.ts'

test('standing is the share of other meals at or below, with quartiles, and needs five meals', () => {
  const xs = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
  const s = standing(65, xs)!
  assert.equal(s.pct, 60)
  assert.equal(s.median, 55)
  assert.equal(s.min, 10)
  assert.equal(s.max, 100)
  assert.equal(standing(65, [1, 2, 3]), null)
  assert.equal(standing(null, xs), null)
  assert.equal(standing(5, [null, undefined, 1, 2, 3, 4, 5])?.n, 5)
})

test('macro split is by energy share', () => {
  const s = macroSplit({ protein_g: 25, carbs_g: 50, fat_g: 10 })!
  assert.ok(Math.abs(s.protein + s.carbs + s.fat - 1) < 1e-9)
  assert.ok(Math.abs(s.carbs - 200 / 390) < 1e-9)
  assert.equal(macroSplit({ protein_g: null, carbs_g: 0, fat_g: null }), null)
})

test('micronutrients sum over items, leave out macros and amino acids and carry the share of the day', () => {
  const out = rankMicros(
    [{ nutrient: 'iron', amount: 2, unit: 'mg' }, { nutrient: 'iron', amount: 1, unit: 'mg' }, { nutrient: 'protein', amount: 20, unit: 'g' },
      { nutrient: 'leucine', amount: 2, unit: 'g' }, { nutrient: 'magnesium', amount: 50, unit: 'mg' }, { nutrient: 'zinc', amount: 0, unit: 'mg' }],
    new Map([['iron', 6], ['magnesium', 200]]),
  )
  assert.deepEqual(out.map((m) => m.nutrient), ['magnesium', 'iron'])
  assert.equal(out[1].amount, 3)
  assert.equal(out[1].dayShare, 0.5)
})
