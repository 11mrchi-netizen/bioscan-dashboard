// Gate for deterministic fueling-recipe totals (docs/endurance-fueling).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { computeRecipeTotals, resolveIngredientTotals, scaleRecipeToQuantity } from './fuelingRecipe.ts'
import type { FoodNutrientRow, FoodServingRow, RecipeIngredient } from './fuelingRecipe.ts'

// A maltodextrin-based gel: 24g carbs, 20mg sodium per 32g basis (its own package serving), no caffeine/water data.
const GEL_NUTRIENTS: FoodNutrientRow[] = [
  { nutrient: 'carbs', amount: 24, basisQty: 32 },
  { nutrient: 'sodium', amount: 20, basisQty: 32 },
]

// Plain water: 100g water per 100g basis.
const WATER_NUTRIENTS: FoodNutrientRow[] = [{ nutrient: 'water', amount: 100, basisQty: 100 }]

test('scales a direct gram quantity against the basis ratio, exactly', () => {
  const ing: RecipeIngredient = { foodId: 'gel', quantity: 64 } // 2x the 32g basis
  const totals = resolveIngredientTotals(ing, GEL_NUTRIENTS, new Map())
  assert.equal(totals.carbsG, 48)
  assert.equal(totals.sodiumMg, 40)
  assert.equal(totals.fluidMl, null)
  assert.equal(totals.caffeineMg, null)
})

test('a named serving\'s grams/ml equivalence wins over the raw quantity (quantity becomes a serving count)', () => {
  const servingsById = new Map<string, FoodServingRow>([['one-packet', { id: 'one-packet', grams: 32, ml: null }]])
  const ing: RecipeIngredient = { foodId: 'gel', quantity: 3, servingId: 'one-packet' } // 3 packets = 96g
  const totals = resolveIngredientTotals(ing, GEL_NUTRIENTS, servingsById)
  assert.equal(totals.carbsG, 72) // 24 * (96/32)
})

test('an ingredient with no matching food contributes null for every nutrient, not zero', () => {
  const ing: RecipeIngredient = { foodId: null, quantity: 32 }
  assert.deepEqual(resolveIngredientTotals(ing, [], new Map()), { carbsG: null, fluidMl: null, sodiumMg: null, caffeineMg: null })
})

test('a food with no matching nutrient row for that nutrient stays null, not zero', () => {
  const totals = resolveIngredientTotals({ foodId: 'gel', quantity: 32 }, GEL_NUTRIENTS, new Map())
  assert.equal(totals.fluidMl, null)
  assert.equal(totals.caffeineMg, null)
})

test('computeRecipeTotals sums across ingredients, one unresolved ingredient does not discard the rest', () => {
  const nutrientsByFood = new Map([
    ['gel', GEL_NUTRIENTS],
    ['water', WATER_NUTRIENTS],
  ])
  const ingredients: RecipeIngredient[] = [
    { foodId: 'gel', quantity: 32 }, // 24g carbs, 20mg sodium
    { foodId: 'water', quantity: 250 }, // 250ml fluid
    { foodId: null, quantity: 1 }, // unresolved -- contributes nothing, breaks nothing
  ]
  const totals = computeRecipeTotals(ingredients, nutrientsByFood, new Map())
  assert.equal(totals.carbsG, 24)
  assert.equal(totals.sodiumMg, 20)
  assert.equal(totals.fluidMl, 250)
  assert.equal(totals.caffeineMg, null, 'no ingredient had caffeine data, so the recipe total stays unknown, not zero')
})

test('scaleRecipeToQuantity scales each known field and leaves unknown fields unknown', () => {
  const perServing = { carbsG: 24, fluidMl: null, sodiumMg: 20, caffeineMg: 0 }
  assert.deepEqual(scaleRecipeToQuantity(perServing, 0.5), { carbsG: 12, fluidMl: null, sodiumMg: 10, caffeineMg: 0 })
  assert.deepEqual(scaleRecipeToQuantity(perServing, 2), { carbsG: 48, fluidMl: null, sodiumMg: 40, caffeineMg: 0 })
})

test('an entirely unresolvable recipe reports every total as unknown, never zero', () => {
  const ingredients: RecipeIngredient[] = [{ foodId: null, quantity: 1 }, { foodId: 'missing', quantity: 10 }]
  const totals = computeRecipeTotals(ingredients, new Map(), new Map())
  assert.deepEqual(totals, { carbsG: null, fluidMl: null, sodiumMg: null, caffeineMg: null })
})
