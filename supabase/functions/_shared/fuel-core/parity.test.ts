// Run: node --test "supabase/functions/_shared/fuel-core/*.test.ts"
// Parity with the Android meal prep: fixtures/week.json is a week as the phone publishes it to fuel_week_plans, and
// fixtures/expected.json is what the Kotlin MealPrepParityTest preps from it. Every gram and label must match.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import {
  addDays, buildRecipes, cookSheet, type Container, type FoodOption, type Plate, planContainers, portionLabel, prepChoices, prepDay, type PrepPicks,
  prepText, PREP_SLOTS, shoppingList, slotKey, type WeekPlanSnapshot,
} from './index.ts'

const dir = new URL('./fixtures/', import.meta.url)
const week = JSON.parse(readFileSync(new URL('week.json', dir), 'utf8')) as { week_start: string; plan: WeekPlanSnapshot }
const expected = JSON.parse(readFileSync(new URL('expected.json', dir), 'utf8'))

function same(a: unknown, b: unknown, path = '$'): void {
  if (typeof a === 'number' && typeof b === 'number') { assert.ok(Math.abs(a - b) <= 1e-9, `${path}: ${a} vs ${b}`); return }
  if (Array.isArray(a) && Array.isArray(b)) { assert.equal(a.length, b.length, `${path}: length`); a.forEach((x, i) => same(x, b[i], `${path}[${i}]`)); return }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    const ka = Object.keys(a).filter((k) => (a as Record<string, unknown>)[k] !== undefined).sort()
    const kb = Object.keys(b).sort()
    assert.deepEqual(ka, kb, `${path}: keys`)
    for (const k of ka) same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], `${path}.${k}`)
    return
  }
  assert.equal(a, b, path)
}

const plan = week.plan
const monday = week.week_start
const foods = new Map<string, FoodOption>(plan.foods.map((f) => [f.id, f]))
const breakfast = [{ foodId: 'staple:oats', grams: 70 }, { foodId: 'staple:greek_yoghurt', grams: 200 }, { foodId: 'staple:banana', grams: 118 }]
const plateJson = (p: Plate) => ({ portions: p.portions.map((x) => ({ id: x.food.id, grams: x.grams, label: portionLabel(x) })), gaps: p.gaps })
const containerJson = (c: Container) => ({ date: c.date, slot: c.slot, letter: c.letter, plate: plateJson(c.plate) })

const scenarios: [string, PrepPicks, string[]][] = [
  ['two_each', { proteins: ['staple:chicken_breast', 'staple:salmon'], vegetables: ['staple:broccoli', 'staple:greens'], carbs: ['staple:rice_white', 'staple:sweet_potato'] }, []],
  ['five_each', { proteins: ['staple:chicken_breast', 'staple:beef_lean', 'staple:salmon', 'staple:tofu', 'staple:shrimp'], vegetables: ['staple:broccoli', 'staple:greens', 'staple:mixed_veg'], carbs: ['staple:rice_brown', 'staple:pasta', 'staple:potato', 'staple:couscous', 'staple:lentils'] },
    [slotKey(addDays(monday, 2), 'DINNER'), slotKey(addDays(monday, 4), 'LUNCH')]],
  ['one_three_one', { proteins: ['staple:turkey'], vegetables: ['staple:mixed_veg'], carbs: ['staple:rice_white', 'staple:noodles_rice', 'staple:chickpeas'] }, [slotKey(monday, 'LUNCH')]],
]

test('the PICK step offers the same foods in the same order', () => {
  const c = prepChoices(plan.foods)
  same({ proteins: c.proteins.map((f) => f.id), vegetables: c.vegetables.map((f) => f.id), carbs: c.carbs.map((f) => f.id) }, expected.choices)
})

for (const [name, picks, skippedList] of scenarios) {
  test(`prep: ${name}`, () => {
    const skipped = new Set(skippedList)
    const recipes = buildRecipes(picks)
    const containers = planContainers(monday, recipes, skipped, foods, (d, slot) => plan.days.find((x) => x.date === d)?.meals.find((m) => m.kind === slot) ?? null)
    const days = plan.days.slice(0, 5).map((d) => prepDay(d, breakfast, containers, PREP_SLOTS.filter((s) => skipped.has(slotKey(d.date, s))), foods))
    const shopping = shoppingList([...containers.map((c) => c.plate), ...days.map((d) => d.breakfast).filter((p): p is Plate => !!p)])
    const exp = expected[name]
    same(recipes.map((r) => ({ letter: r.letter, slot: r.slot, proteins: r.proteins, carbs: r.carbs, vegetables: r.vegetables })), exp.recipes)
    same(containers.map(containerJson), exp.containers)
    same(days.map((d) => ({ date: d.date, ...(d.breakfast ? { breakfast: plateJson(d.breakfast) } : {}), snack: { carbG: d.snack.carbG, proteinG: d.snack.proteinG, ...(d.snack.fatG != null ? { fatG: d.snack.fatG } : {}) }, notes: d.notes })), exp.days)
    same(shopping.map((s) => ({ id: s.food.id, eatenG: s.eatenG, rawG: s.rawG, label: s.label })), exp.shopping)
    same(cookSheet(recipes, containers).map((c) => ({ letter: c.recipe.letter, id: c.food.id, rawG: c.rawG, cookedG: c.cookedG, split: c.split.map((x) => x.grams) })), exp.cook)
    assert.equal(prepText(monday, recipes, containers, shopping, foods), exp.text)
  })
}
