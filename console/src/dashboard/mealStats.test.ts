// Run: node --test console/src/dashboard/mealStats.test.ts
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { combineProfiles, macroSplit, rankMicros, standing } from './mealStats.ts'

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

const prof = (over: Record<string, unknown>) => ({
  meal_id: 1, logged_at: '', food_grams: 200, calories: 400, protein_g: 40, carbs_g: 40, fat_g: 10, fiber_g: 4, energy_density_kcal_g: 2, protein_per_100kcal: 10,
  carbs_per_100kcal: 10, fiber_per_1000kcal: 10, glycemic_load_est: 10, fullness_factor_est: 2, ultra_processed_pct: 0, food_groups: ['eggs'], tags: [], macro_mismatch: false, ...over,
})

test('two entries of one meal combine into one profile, indexes worked out again', () => {
  const c = combineProfiles([prof({}), prof({ meal_id: 2, food_grams: 100, calories: 300, protein_g: 4, carbs_g: 60, fat_g: 5, fiber_g: 2, energy_density_kcal_g: 3, glycemic_load_est: 25, fullness_factor_est: 1, ultra_processed_pct: 100, food_groups: ['sweets'], tags: ['gluten'] })])!
  assert.equal(c.calories, 700)
  assert.equal(c.protein_g, 44)
  assert.equal(c.glycemic_load_est, 35)
  assert.equal(c.food_grams, 300)
  assert.ok(Math.abs(c.energy_density_kcal_g! - (2 * 200 + 3 * 100) / 300) < 1e-9)
  assert.ok(Math.abs(c.protein_per_100kcal! - (44 / 700) * 100) < 1e-9)
  assert.ok(Math.abs(c.fullness_factor_est! - (2 * 200 + 1 * 100) / 300) < 1e-9)
  assert.ok(Math.abs(c.ultra_processed_pct! - (0 * 400 + 100 * 300) / 700) < 1e-9)
  assert.deepEqual(c.food_groups, ['eggs', 'sweets'])
  assert.deepEqual(c.tags, ['gluten'])
})

test('an index is null when one part of the meal lacks it', () => {
  const c = combineProfiles([prof({}), prof({ glycemic_load_est: null, fiber_g: null, energy_density_kcal_g: null })])!
  assert.equal(c.glycemic_load_est, null)
  assert.equal(c.fiber_per_1000kcal, null)
  assert.equal(c.energy_density_kcal_g, null)
  assert.equal(combineProfiles([]), null)
})

test('a total is null when one part lacks it, but an entry with nothing recorded is left out', () => {
  const a = combineProfiles([prof({}), prof({ meal_id: 2, protein_g: null })])!
  assert.equal(a.protein_g, null)
  assert.equal(a.protein_per_100kcal, null)
  assert.equal(a.calories, 800)
  const b = combineProfiles([prof({}), prof({ meal_id: 2, calories: null, protein_g: null, carbs_g: null, fat_g: null })])!
  assert.equal(b.calories, 400)
  assert.equal(b.protein_g, 40)
  const c = combineProfiles([prof({}), prof({ meal_id: 2, calories: null, protein_g: null, carbs_g: null, fat_g: null, tags: ['caffeine'], food_groups: ['coffee_tea'] })])!
  assert.deepEqual(c.tags, ['caffeine'])
  assert.deepEqual(c.food_groups, ['eggs', 'coffee_tea'])
})
