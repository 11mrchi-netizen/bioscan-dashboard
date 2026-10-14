// Endurance Fueling & Personal Experiments (06.3.2): deterministic totals for a fueling recipe built from canonical
// food ingredients. Erasable TypeScript, no I/O -- mirrors `domain/NutritionResolver.kt`'s scaling formula exactly
// (`amount * (consumedAmount / basisQty)`), so a recipe's carbs/fluid/sodium/caffeine are computed the same way a
// logged meal's nutrients already are, not a second independent formula. `resolveConsumedBaseAmount`'s precedent
// (a named serving's own grams/ml equivalence wins over a raw quantity) is mirrored the same way.
//
// A missing nutrient on an ingredient's food stays null and propagates as "unknown" on the recipe total -- it is
// never treated as zero, per the milestone's own principle ("unknown carb source split or nutrient values remain
// unknown; never fabricate precision"). An ingredient with no resolvable food contributes null for every nutrient,
// but other ingredients' known values still sum -- one unresolved item does not discard the whole recipe.
export interface FoodNutrientRow {
  nutrient: string
  amount: number
  basisQty: number
}

export interface FoodServingRow {
  id: string | number
  grams: number | null
  ml: number | null
}

export interface RecipeIngredient {
  foodId: string | number | null
  quantity: number
  servingId?: string | number | null
}

export interface RecipeTotals {
  carbsG: number | null
  fluidMl: number | null
  sodiumMg: number | null
  caffeineMg: number | null
}

const NUTRIENT_CARBS = 'carbs'
const NUTRIENT_SODIUM = 'sodium'
const NUTRIENT_CAFFEINE = 'caffeine'
const NUTRIENT_WATER = 'water'

function resolveConsumedBaseAmount(quantity: number, serving: FoodServingRow | null): number {
  if (serving) {
    const gramsOrMl = serving.grams ?? serving.ml
    if (gramsOrMl !== null) return quantity * gramsOrMl
  }
  return quantity
}

function scaleNutrient(row: FoodNutrientRow | undefined, consumedAmount: number): number | null {
  return row ? row.amount * (consumedAmount / row.basisQty) : null
}

// Any single known value makes the sum known; all-unknown stays unknown -- distinct from zero, which would
// misrepresent a recipe where every ingredient simply lacks that nutrient datum.
function sumKnown(values: (number | null)[]): number | null {
  const known = values.filter((v): v is number => v !== null)
  return known.length > 0 ? known.reduce((a, b) => a + b, 0) : null
}

/** One ingredient's resolved totals, before summing into the recipe. Exposed for per-ingredient review UIs. */
export function resolveIngredientTotals(ingredient: RecipeIngredient, nutrients: FoodNutrientRow[], servingsById: Map<string | number, FoodServingRow>): RecipeTotals {
  if (ingredient.foodId === null) return { carbsG: null, fluidMl: null, sodiumMg: null, caffeineMg: null }
  const byName = new Map(nutrients.map((n) => [n.nutrient, n]))
  const serving = ingredient.servingId != null ? servingsById.get(ingredient.servingId) ?? null : null
  const consumedAmount = resolveConsumedBaseAmount(ingredient.quantity, serving)
  return {
    carbsG: scaleNutrient(byName.get(NUTRIENT_CARBS), consumedAmount),
    fluidMl: scaleNutrient(byName.get(NUTRIENT_WATER), consumedAmount),
    sodiumMg: scaleNutrient(byName.get(NUTRIENT_SODIUM), consumedAmount),
    caffeineMg: scaleNutrient(byName.get(NUTRIENT_CAFFEINE), consumedAmount),
  }
}

const scaleOne = (v: number | null, quantity: number): number | null => (v === null ? null : v * quantity)

/**
 * Actual-consumed totals for a logged quantity of servings of a recipe version (06.3.3), e.g. 0.5 for half a gel.
 * The caller freezes this result onto the intake-log row at log time -- it is never recomputed later if the
 * recipe is revised, since `fueling_recipe_versions` is itself append-only (a later edit is a new version, this
 * log keeps pointing at the version it actually used).
 */
export function scaleRecipeToQuantity(perServing: RecipeTotals, quantity: number): RecipeTotals {
  return {
    carbsG: scaleOne(perServing.carbsG, quantity),
    fluidMl: scaleOne(perServing.fluidMl, quantity),
    sodiumMg: scaleOne(perServing.sodiumMg, quantity),
    caffeineMg: scaleOne(perServing.caffeineMg, quantity),
  }
}

/**
 * Deterministic totals for a whole recipe (or one serving of it, depending on what quantities `ingredients`
 * represent) from its ingredient list, each ingredient's food_nutrients rows, and the food_servings rows any
 * ingredient names by `servingId`.
 */
export function computeRecipeTotals(
  ingredients: RecipeIngredient[],
  nutrientsByFood: Map<string | number, FoodNutrientRow[]>,
  servingsById: Map<string | number, FoodServingRow>,
): RecipeTotals {
  const resolved = ingredients.map((ing) => resolveIngredientTotals(ing, ing.foodId !== null ? nutrientsByFood.get(ing.foodId) ?? [] : [], servingsById))
  return {
    carbsG: sumKnown(resolved.map((r) => r.carbsG)),
    fluidMl: sumKnown(resolved.map((r) => r.fluidMl)),
    sodiumMg: sumKnown(resolved.map((r) => r.sodiumMg)),
    caffeineMg: sumKnown(resolved.map((r) => r.caffeineMg)),
  }
}
