// Port of the Android domain/fuel/MealPrep.kt: Sunday meal prep. Up to five proteins, vegetables and carbohydrates are
// picked; four recipes are built (A and B for lunch, C and D for dinner); Monday to Friday get A-B-A-B-A at lunch and
// C-D-C-D-C at dinner, less the meals eaten out. Each container is portioned for its own day's targets from the phone's
// fuel engine (fuel_week_plans). The shopping list adds a week of the fixed breakfast, converted from cooked to raw weights
// by USDA cooking yields. Deterministic throughout.

import {
  carbMid, FOOD_ROLES, type FoodOption, MAIN_MEALS, type MealSlotKind, type Plate, plateCarb, plateFat, plateLabel, plateProtein,
  type PlateTarget, type Portion, roundToInt, solvePortions,
} from './plates.ts'

// ---- the week the phone published (fuel_week_plans.plan, v1) ----

export interface CodedMeal {
  kind: MealSlotKind; time: string; code: string; carb_low_g: number; carb_high_g: number; protein_g: number; fat_g?: number | null
  before_key_session: boolean; reason: string
}
export interface PlannedSession { title: string; type: string; intensity: string; duration_min: number; start?: string | null }
export interface CodedDay { date: string; meals: CodedMeal[]; sessions: PlannedSession[] }
export interface WeekPlanSnapshot { v: number; weight_kg: number; days: CodedDay[]; foods: FoodOption[] }

export const plateTarget = (m: CodedMeal): PlateTarget => ({
  kind: m.kind, carbLowG: m.carb_low_g, carbHighG: m.carb_high_g, proteinG: m.protein_g, fatG: m.fat_g ?? null, beforeKeySession: m.before_key_session,
})
export const mealCarbMid = (m: CodedMeal) => (m.carb_low_g + m.carb_high_g) / 2

// ---- picks and recipes ----

export type PrepSlot = 'LUNCH' | 'DINNER'
export const PREP_SLOTS: PrepSlot[] = ['LUNCH', 'DINNER']
export const MAX_PICKS_PER_GROUP = 5
export const PREP_DAYS = 5
export const PREP_VEG_G = 150

export interface PrepPicks { proteins: string[]; vegetables: string[]; carbs: string[] }
export const picksReady = (p: PrepPicks) => p.proteins.length > 0 && p.vegetables.length > 0 && p.carbs.length > 0

export interface Recipe { letter: string; slot: PrepSlot; proteins: string[]; carbs: string[]; vegetables: string[]; oil: boolean }

export const preppable = (f: FoodOption): boolean =>
  (f.slots.includes('LUNCH') || f.slots.includes('DINNER')) && f.taste !== 'SWEET' && !f.serving

export function prepChoices(foods: FoodOption[]): { proteins: FoodOption[]; vegetables: FoodOption[]; carbs: FoodOption[] } {
  const rank = (f: FoodOption) => (f.yours ? 1000 : 0) + Object.values(f.uses).reduce((a, b) => a + (b ?? 0), 0)
  const ok = foods.filter(preppable).sort((a, b) => rank(b) - rank(a) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
  return {
    proteins: ok.filter((f) => f.role === 'PROTEIN' || f.role === 'MIXED'),
    vegetables: ok.filter((f) => f.role === 'VEG'),
    carbs: ok.filter((f) => f.role === 'CARB' || f.role === 'MIXED'),
  }
}

export function buildRecipes(picks: PrepPicks): Recipe[] {
  const rotate = (items: string[], crossed = false): string[][] => {
    const n = items.length
    if (n === 0) return [[], [], [], []]
    const idx = n === 1 ? [0, 0, 0, 0] : n === 2 ? (crossed ? [0, 1, 0, 1] : [0, 1, 1, 0]) : n === 3 ? (crossed ? [0, 1, 2, 1] : [0, 1, 2, 0]) : crossed ? [0, 1, 3, 2] : [0, 1, 2, 3]
    const out = idx.map((i) => [items[i]])
    items.slice(4).forEach((extra, i) => out[i % 4].push(extra))
    return out
  }
  const p = rotate(picks.proteins.slice(0, MAX_PICKS_PER_GROUP))
  const c = rotate(picks.carbs.slice(0, MAX_PICKS_PER_GROUP), true)
  const v = rotate(picks.vegetables.slice(0, MAX_PICKS_PER_GROUP))
  return ['A', 'B', 'C', 'D'].map((letter, i) => ({ letter, slot: i < 2 ? 'LUNCH' : 'DINNER', proteins: p[i], carbs: c[i], vegetables: v[i], oil: true }))
}

// ---- dates ----

const utc = (d: string) => { const [y, m, dd] = d.split('-').map(Number); return Date.UTC(y, m - 1, dd) }
export const addDays = (d: string, n: number): string => new Date(utc(d) + n * 86_400_000).toISOString().slice(0, 10)
const isoDow = (d: string) => { const w = new Date(utc(d)).getUTCDay(); return w === 0 ? 7 : w }
/** The Monday of the week being prepped: the coming one (from Sunday, tomorrow). */
export const upcomingPrepWeek = (today: string): string => { const t = addDays(today, 1); return addDays(t, (8 - isoDow(t)) % 7) }
/** The Monday of the week [today] is in. */
export const currentPrepWeek = (today: string): string => addDays(today, -(isoDow(today) - 1))
export const slotKey = (date: string, slot: PrepSlot) => `${date}:${slot}`

export function letterFor(weekStart: string, date: string, slot: PrepSlot): string | null {
  const d = Math.round((utc(date) - utc(weekStart)) / 86_400_000)
  if (d < 0 || d >= PREP_DAYS) return null
  return slot === 'LUNCH' ? (d % 2 === 0 ? 'A' : 'B') : (d % 2 === 0 ? 'C' : 'D')
}

// ---- containers ----

export interface Container { date: string; slot: PrepSlot; letter: string; plate: Plate; target: PlateTarget }
export interface StoredContainer { date: string; slot: PrepSlot; letter: string; grams: Record<string, number> }

const oilFor = (foods: Map<string, FoodOption>): FoodOption | null => {
  const o = foods.get('staple:olive_oil')
  return o ? { ...o, min_g: 0, max_g: 20, typical_g: 5, step_g: 5, serving: null } : null
}

export function planContainers(weekStart: string, recipes: Recipe[], skipped: Set<string>, foods: Map<string, FoodOption>,
  mealFor: (date: string, slot: PrepSlot) => CodedMeal | null): Container[] {
  const byLetter = new Map(recipes.map((r) => [r.letter, r]))
  const out: Container[] = []
  for (let d = 0; d < PREP_DAYS; d++) {
    const date = addDays(weekStart, d)
    for (const slot of PREP_SLOTS) {
      if (skipped.has(slotKey(date, slot))) continue
      const recipe = byLetter.get(letterFor(weekStart, date, slot) ?? '')
      if (!recipe) continue
      const meal = mealFor(date, slot)
      if (!meal) continue
      const target = plateTarget(meal)
      const vegFoods = recipe.vegetables.map((id) => foods.get(id)).filter((f): f is FoodOption => !!f)
      const veg: Portion[] = vegFoods.map((f) => ({ food: f, grams: PREP_VEG_G / vegFoods.length }))
      const varIds = [...new Set([...recipe.carbs, ...recipe.proteins])]
      const vars = varIds.map((id) => foods.get(id)).filter((f): f is FoodOption => !!f)
      const oil = recipe.oil && target.fatG != null ? oilFor(foods) : null
      if (oil) vars.push(oil)
      const plate = solvePortions(target, vars, veg)
      out.push({ date, slot, letter: recipe.letter, plate: { ...plate, portions: plate.portions.filter((p) => p.grams > 0) }, target })
    }
  }
  return out
}

export function containerFromStored(s: StoredContainer, foods: Map<string, FoodOption>, target: PlateTarget): Container {
  const portions = Object.entries(s.grams).map(([id, g]) => (foods.get(id) ? { food: foods.get(id)!, grams: g } : null)).filter((p): p is Portion => !!p)
    .sort((a, b) => (a.food.id < b.food.id ? -1 : a.food.id > b.food.id ? 1 : 0))
  return { date: s.date, slot: s.slot, letter: s.letter, plate: { portions, gaps: [] }, target }
}

// ---- breakfast ----

export interface BreakfastItem { foodId: string; grams: number }
export const BREAKFAST_MIN_SCALE = 0.5
export const BREAKFAST_MAX_SCALE = 1.75

export function scaleBreakfast(template: BreakfastItem[], target: PlateTarget, foods: Map<string, FoodOption>): Plate | null {
  const items = template.map((i) => { const f = foods.get(i.foodId); return f ? { ...f, typical_g: i.grams, min_g: i.grams * BREAKFAST_MIN_SCALE, max_g: i.grams * BREAKFAST_MAX_SCALE } : null })
    .filter((f): f is FoodOption => !!f)
  if (items.length === 0) return null
  return solvePortions(target, items)
}

// ---- a prepped day ----

export interface Remainder { carbG: number; proteinG: number; fatG: number | null }
export interface PreppedDay { date: string; breakfast: Plate | null; lunch: Container | null; dinner: Container | null; skipped: PrepSlot[]; snack: Remainder; notes: string[] }

const plus = (t: PlateTarget, dc: number, dp: number, df: number): PlateTarget => ({
  ...t, carbLowG: Math.max(0, t.carbLowG + dc), carbHighG: Math.max(0, t.carbHighG + dc), proteinG: Math.max(0, t.proteinG + dp), fatG: t.fatG != null ? Math.max(0, t.fatG + df) : null,
})

export function prepDay(day: CodedDay, template: BreakfastItem[], containers: Container[], skipped: PrepSlot[], foods: Map<string, FoodOption>): PreppedDay {
  const lunch = containers.find((c) => c.date === day.date && c.slot === 'LUNCH') ?? null
  const dinner = containers.find((c) => c.date === day.date && c.slot === 'DINNER') ?? null
  const fixed = [lunch, dinner].filter((c): c is Container => !!c)
  const dc = fixed.reduce((a, c) => a + (carbMid(c.target) - plateCarb(c.plate)), 0)
  const dp = fixed.reduce((a, c) => a + (c.target.proteinG - plateProtein(c.plate)), 0)
  const df = fixed.reduce((a, c) => a + ((c.target.fatG ?? 0) - plateFat(c.plate)), 0)
  const notes: string[] = []
  const bMeal = day.meals.find((m) => m.kind === 'BREAKFAST')
  const breakfast = bMeal ? scaleBreakfast(template, plus(plateTarget(bMeal), dc, dp, df), foods) : null
  const [rc, rp, rf] = breakfast && bMeal
    ? [mealCarbMid(bMeal) + dc - plateCarb(breakfast), bMeal.protein_g + dp - plateProtein(breakfast), (bMeal.fat_g ?? 0) + df - plateFat(breakfast)]
    : [dc, dp, df]
  const small = day.meals.filter((m) => !MAIN_MEALS.has(m.kind))
  const hasFat = day.meals.some((m) => m.fat_g != null)
  const snack: Remainder = {
    carbG: Math.max(0, small.reduce((a, m) => a + mealCarbMid(m), 0) + rc),
    proteinG: Math.max(0, small.reduce((a, m) => a + m.protein_g, 0) + rp),
    fatG: hasFat ? Math.max(0, small.reduce((a, m) => a + (m.fat_g ?? 0), 0) + rf) : null,
  }
  if (fixed.length > 0 && Math.abs(dc) >= 15) {
    notes.push(`The containers are ${roundToInt(Math.abs(dc))} g of carbohydrate ${dc > 0 ? 'short of' : 'over'} today's plan: breakfast and the snack take it up.`)
  }
  return { date: day.date, breakfast, lunch, dinner, skipped, snack, notes }
}

// ---- shopping and cooking ----

const RAW_PER_COOKED: Record<string, number> = {
  'staple:rice_white': 0.36, 'staple:rice_brown': 0.40, 'staple:pasta': 0.45, 'staple:noodles_wheat': 0.50,
  'staple:noodles_rice': 0.40, 'staple:couscous': 0.40, 'staple:sweet_potato': 1.20, 'staple:lentils': 0.40,
  'staple:chickpeas': 0.45, 'staple:beans': 0.45, 'staple:chicken_breast': 1.35, 'staple:beef_lean': 1.35,
  'staple:pork_loin': 1.35, 'staple:turkey': 1.35, 'staple:salmon': 1.25, 'staple:white_fish': 1.25,
  'staple:shrimp': 1.30, 'staple:greens': 1.20, 'staple:broccoli': 1.10, 'staple:mixed_veg': 1.15,
}
const RAW_WORD: Record<string, string> = {
  'staple:rice_white': 'white rice, dry', 'staple:rice_brown': 'brown rice, dry', 'staple:pasta': 'pasta, dry',
  'staple:noodles_wheat': 'wheat noodles, dry', 'staple:noodles_rice': 'rice noodles, dry', 'staple:couscous': 'couscous, dry',
  'staple:lentils': 'lentils, dry', 'staple:chickpeas': 'chickpeas, dry', 'staple:beans': 'beans, dry',
  'staple:chicken_breast': 'chicken breast, raw', 'staple:beef_lean': 'lean beef, raw', 'staple:pork_loin': 'pork loin, raw',
  'staple:turkey': 'turkey breast, raw', 'staple:salmon': 'salmon, raw', 'staple:white_fish': 'white fish, raw',
  'staple:shrimp': 'shrimp, raw', 'staple:sweet_potato': 'sweet potato, raw', 'staple:greens': 'leafy greens, raw',
  'staple:broccoli': 'broccoli, raw', 'staple:mixed_veg': 'mixed vegetables, raw',
}
export const rawPerCooked = (id: string): number => RAW_PER_COOKED[id] ?? 1
export const rawWord = (f: FoodOption): string => RAW_WORD[f.id] ?? f.name
const TBSP_OIL_G = 13.5

export interface ShoppingItem { food: FoodOption; eatenG: number; rawG: number; label: string }

export function shoppingList(plates: Plate[]): ShoppingItem[] {
  const byFood = new Map<string, Portion[]>()
  for (const p of plates.flatMap((x) => x.portions)) if (p.grams > 0) { const l = byFood.get(p.food.id) ?? []; l.push(p); byFood.set(p.food.id, l) }
  const out: ShoppingItem[] = [...byFood].map(([id, ps]) => {
    const food = ps[0].food
    const eaten = ps.reduce((a, p) => a + p.grams, 0)
    const raw = eaten * rawPerCooked(id)
    const s = food.serving
    let label: string
    if (id === 'staple:olive_oil') label = `${Math.ceil(raw / TBSP_OIL_G)} tbsp olive oil`
    else if (s) { const n = Math.ceil(raw / s.grams - 1e-6); label = `${n} ${n === 1 ? s.singular : s.plural}` }
    else {
      const rounded = Math.ceil(raw / 50) * 50
      const name = RAW_WORD[id] ?? food.name
      label = rounded >= 1000 ? `${(rounded / 1000).toFixed(2)} kg ${name}`.replace('.00 kg', ' kg') : `${rounded} ${food.unit} ${name}`
    }
    return { food, eatenG: eaten, rawG: raw, label }
  })
  return out.sort((a, b) => FOOD_ROLES.indexOf(a.food.role) - FOOD_ROLES.indexOf(b.food.role) || (a.food.name < b.food.name ? -1 : a.food.name > b.food.name ? 1 : 0))
}

export interface CookStep { recipe: Recipe; food: FoodOption; rawG: number; cookedG: number; split: { container: Container; grams: number }[] }

export function cookSheet(recipes: Recipe[], containers: Container[]): CookStep[] {
  return recipes.flatMap((r) => {
    const mine = containers.filter((c) => c.letter === r.letter)
    const foods: FoodOption[] = []
    for (const c of mine) for (const p of c.plate.portions) if (!foods.some((f) => f.id === p.food.id)) foods.push(p.food)
    return foods.map((f) => {
      const split = mine.map((c) => ({ container: c, grams: c.plate.portions.find((p) => p.food.id === f.id)?.grams ?? 0 }))
      const cooked = split.reduce((a, s) => a + s.grams, 0)
      return { recipe: r, food: f, rawG: cooked * rawPerCooked(f.id), cookedG: cooked, split }
    }).filter((s) => s.cookedG > 0)
  })
}

export const recipeName = (r: Recipe, foods: Map<string, FoodOption>): string =>
  [...r.proteins, ...r.carbs, ...r.vegetables].map((id) => foods.get(id)?.name).filter(Boolean).join(' · ')

const DOW = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
export function prepText(weekStart: string, recipes: Recipe[], containers: Container[], shopping: ShoppingItem[], foods: Map<string, FoodOption>): string {
  const lines = [`MEAL PREP · WEEK OF ${weekStart}`, '', 'SHOPPING', ...shopping.map((s) => `- ${s.label}`), '', 'RECIPES',
    ...recipes.map((r) => `${r.letter} (${r.slot.toLowerCase()}): ${recipeName(r, foods)}`), '', 'COOK',
    ...cookSheet(recipes, containers).map((s) => {
      const raw = rawPerCooked(s.food.id) !== 1 ? `${roundToInt(s.rawG)} g ${rawWord(s.food)} → ` : ''
      return `${s.recipe.letter}: ${raw}${roundToInt(s.cookedG)} g ${s.food.name}, split ${s.split.map((x) => String(roundToInt(x.grams))).join(' / ')}`
    }), '', 'CONTAINERS',
    ...containers.map((c) => `${DOW[isoDow(c.date) - 1]} ${c.slot} (${c.letter}): ${plateLabel(c.plate)}`)]
  return lines.join('\n').trimEnd()
}

export { plateCarb, plateFat, plateProtein }
