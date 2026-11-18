// Port of the Android domain/fuel/MealSuggestions.kt pieces meal prep needs: foods, portions, plates, a meal's targets,
// and solvePortions (least squares on carbohydrate, protein and fat, rounded to real steps and scored). Deterministic;
// parity with the phone is checked by fixtures/expected.json, written by the Kotlin MealPrepParityTest.

export type FoodRole = 'CARB' | 'PROTEIN' | 'FAT' | 'MIXED' | 'VEG'
export const FOOD_ROLES: FoodRole[] = ['CARB', 'PROTEIN', 'FAT', 'MIXED', 'VEG']
export type Taste = 'SAVOURY' | 'SWEET' | 'NEUTRAL'
export type MealSlotKind = 'BREAKFAST' | 'LUNCH' | 'SNACK' | 'DINNER' | 'PRE_SESSION' | 'RECOVERY'
export const MAIN_MEALS = new Set<MealSlotKind>(['BREAKFAST', 'LUNCH', 'DINNER'])

export interface Serving { singular: string; plural: string; grams: number; step: number }

export interface FoodOption {
  id: string; name: string; role: FoodRole; slots: MealSlotKind[]
  carb_per_g: number; protein_per_g: number; fat_per_g: number; fibre_per_g: number; kcal_per_g: number
  typical_g: number; min_g: number; max_g: number; step_g: number; serving?: Serving | null; unit: string; taste: Taste
  yours: boolean; uses: Partial<Record<MealSlotKind, number>>
}

export const totalUses = (f: FoodOption): number => Object.values(f.uses).reduce((a, b) => a + (b ?? 0), 0)
export const usesIn = (f: FoodOption, k: MealSlotKind): number => f.uses[k] ?? 0
export const isUsual = (f: FoodOption): boolean => f.yours || totalUses(f) >= 2

export interface Portion { food: FoodOption; grams: number }
export const pCarb = (p: Portion) => p.food.carb_per_g * p.grams
export const pProtein = (p: Portion) => p.food.protein_per_g * p.grams
export const pFat = (p: Portion) => p.food.fat_per_g * p.grams
export const pFibre = (p: Portion) => p.food.fibre_per_g * p.grams
export const pKcal = (p: Portion) => p.food.kcal_per_g * p.grams

/** Kotlin's roundToInt: half up (toward positive infinity on .5). */
export const roundToInt = (x: number): number => Math.floor(x + 0.5)

function formatCount(n: number): string {
  const whole = Math.floor(n + 1e-6)
  const frac = n - whole
  let f: string
  if (Math.abs(frac) < 0.05) f = ''
  else if (Math.abs(frac - 0.25) < 0.05) f = '¼'
  else if (Math.abs(frac - 0.5) < 0.05) f = '½'
  else if (Math.abs(frac - 0.75) < 0.05) f = '¾'
  else return n.toFixed(1)
  return whole === 0 && f !== '' ? f : `${whole}${f}`
}

/** "1½ bananas (177 g)", "180 g cooked brown rice". */
export function portionLabel(p: Portion): string {
  const s = p.food.serving
  if (s) {
    const n = p.grams / s.grams
    const name = Math.abs(n - 1) < 1e-6 || n < 1 ? s.singular : s.plural
    return `${formatCount(n)} ${name} (${roundToInt(p.grams)} ${p.food.unit})`
  }
  return `${roundToInt(p.grams)} ${p.food.unit} ${p.food.name}`
}

export interface Plate { portions: Portion[]; gaps: string[] }
const sum = (ps: Portion[], f: (p: Portion) => number) => ps.reduce((a, p) => a + f(p), 0)
export const plateCarb = (p: Plate) => sum(p.portions, pCarb)
export const plateProtein = (p: Plate) => sum(p.portions, pProtein)
export const plateFat = (p: Plate) => sum(p.portions, pFat)
export const plateFibre = (p: Plate) => sum(p.portions, pFibre)
export const plateKcal = (p: Plate) => sum(p.portions, pKcal)
export const plateLabel = (p: Plate) => p.portions.map(portionLabel).join(' · ')

export interface PlateTarget { kind: MealSlotKind; carbLowG: number; carbHighG: number; proteinG: number; fatG: number | null; beforeKeySession: boolean }
export const carbMid = (t: PlateTarget) => (t.carbLowG + t.carbHighG) / 2

const RIDGE = 0.15
const FAT_WEIGHT = 0.6
const PRE_SESSION_FAT_CAP_G = 10
const PRE_SESSION_FIBRE_CAP_G = 5
const MAIN_BEFORE_SESSION_FIBRE_CAP_G = 8
const MEAL_FIBRE_SOFT_CAP_G = 15

const outside = (v: number, lo: number, hi: number) => (v < lo ? lo - v : v > hi ? v - hi : 0)
const proteinBand = (t: PlateTarget): [number, number] => [t.proteinG * 0.85, t.proteinG * 1.15]
const fatBand = (t: PlateTarget): [number, number] => [t.fatG! * 0.75 - 2, t.fatG! * 1.25 + 2]

function score(t: PlateTarget, portions: Portion[], recent: Set<string>): number {
  const c = sum(portions, pCarb), p = sum(portions, pProtein), f = sum(portions, pFat)
  let s = (outside(c, t.carbLowG, t.carbHighG) / Math.max(carbMid(t), 10)) ** 2
  const [pl, ph] = proteinBand(t)
  s += (outside(p, pl, ph) / Math.max(t.proteinG, 10)) ** 2
  if (t.fatG != null) {
    const [fl, fh] = fatBand(t)
    s += FAT_WEIGHT * (outside(f, fl, fh) / Math.max(t.fatG, 8)) ** 2
  }
  if (t.beforeKeySession || t.kind === 'PRE_SESSION') {
    if (f > Math.max(PRE_SESSION_FAT_CAP_G, (t.fatG ?? 0) * 1.25 + 2)) s += 1
    if (sum(portions, pFibre) > (MAIN_MEALS.has(t.kind) ? MAIN_BEFORE_SESSION_FIBRE_CAP_G : PRE_SESSION_FIBRE_CAP_G)) s += 1
  }
  const fibre = sum(portions, pFibre)
  if (fibre > MEAL_FIBRE_SOFT_CAP_G) s += ((fibre - MEAL_FIBRE_SOFT_CAP_G) / 30) ** 2
  if (portions.filter((x) => x.food.role === 'CARB').length > 1) s += 0.01
  s += 0.02 * portions.filter((x) => recent.has(x.food.id)).length
  s -= 0.01 * portions.filter((x) => isUsual(x.food)).length + 0.01 * portions.filter((x) => x.food.yours || usesIn(x.food, t.kind) > 0).length
  return s
}

export function gaps(t: PlateTarget, plate: Portion[]): string[] {
  const out: string[] = []
  const gap = (name: string, v: number, lo: number, hi: number) => {
    if (roundToInt(lo - v) >= 1) out.push(`${name} ${roundToInt(lo - v)} G SHORT`)
    else if (roundToInt(v - hi) >= 1) out.push(`${name} ${roundToInt(v - hi)} G OVER`)
  }
  gap('CARBS', sum(plate, pCarb), t.carbLowG, t.carbHighG)
  if (t.proteinG >= 10) { const [lo, hi] = proteinBand(t); gap('PROTEIN', sum(plate, pProtein), lo, hi) }
  if (t.fatG != null && t.fatG >= 8) { const [lo, hi] = fatBand(t); gap('FAT', sum(plate, pFat), lo, hi) }
  return out
}

function leastSquares(rows: number[][], rhs: number[], n: number): number[] | null {
  const m = Array.from({ length: n }, () => new Array<number>(n).fill(0))
  const v = new Array<number>(n).fill(0)
  rows.forEach((a, r) => {
    for (let i = 0; i < n; i++) {
      v[i] += a[i] * rhs[r]
      for (let j = 0; j < n; j++) m[i][j] += a[i] * a[j]
    }
  })
  for (let col = 0; col < n; col++) {
    let piv = col
    for (let r = col; r < n; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r
    if (Math.abs(m[piv][col]) < 1e-12) return null
    if (piv !== col) { [m[piv], m[col]] = [m[col], m[piv]];[v[piv], v[col]] = [v[col], v[piv]] }
    for (let r = 0; r < n; r++) {
      if (r === col) continue
      const k = m[r][col] / m[col][col]
      for (let c = col; c < n; c++) m[r][c] -= k * m[col][c]
      v[r] -= k * v[col]
    }
  }
  return Array.from({ length: n }, (_, i) => v[i] / m[i][i])
}

function solveGrams(t: PlateTarget, foods: FoodOption[], fixed: Portion[]): number[] {
  const cT = carbMid(t) - sum(fixed, pCarb)
  const pT = t.proteinG - sum(fixed, pProtein)
  const fT = t.fatG != null ? t.fatG - sum(fixed, pFat) : null
  const sc = Math.max(carbMid(t), 10), sp = Math.max(t.proteinG, 10), sf = Math.max(t.fatG ?? 8, 8)
  const x = foods.map((f) => f.typical_g)
  const free = foods.map((_, i) => i)
  for (let iter = 0; iter < foods.length + 1; iter++) {
    if (free.length === 0) return x
    const fixedPart = foods.map((_, i) => i).filter((i) => !free.includes(i))
    const rows: number[][] = []; const rhs: number[] = []
    const macroRow = (per: (f: FoodOption) => number, target: number, scale: number, w: number) => {
      const k = Math.sqrt(w) / scale
      rows.push(free.map((fi) => per(foods[fi]) * k))
      rhs.push((target - fixedPart.reduce((a, i) => a + per(foods[i]) * x[i], 0)) * k)
    }
    macroRow((f) => f.carb_per_g, cT, sc, 1)
    macroRow((f) => f.protein_per_g, pT, sp, 1)
    if (fT != null) macroRow((f) => f.fat_per_g, fT, sf, FAT_WEIGHT)
    free.forEach((fi, i) => { rows.push(free.map((_, j) => (j === i ? RIDGE / foods[fi].typical_g : 0))); rhs.push(RIDGE) })
    const sol = leastSquares(rows, rhs, free.length)
    if (!sol) return x
    free.forEach((fi, i) => { x[fi] = sol[i] })
    // maxByOrNull keeps the first of equal values
    let worst = free[0]
    let worstV = -Infinity
    for (const fi of free) {
      const v = Math.max(foods[fi].min_g - x[fi], x[fi] - foods[fi].max_g) / foods[fi].typical_g
      if (v > worstV) { worstV = v; worst = fi }
    }
    const over = Math.max(foods[worst].min_g - x[worst], x[worst] - foods[worst].max_g)
    if (over <= 1e-9) return x
    x[worst] = Math.min(foods[worst].max_g, Math.max(foods[worst].min_g, x[worst]))
    free.splice(free.indexOf(worst), 1)
  }
  return x
}

const stepOf = (f: FoodOption) => (f.serving ? f.serving.grams * f.serving.step : f.step_g)
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))

function rounded(t: PlateTarget, foods: FoodOption[], x: number[], fixed: Portion[], recent: Set<string>): Portion[] {
  const options = foods.map((f, i) => {
    const step = stepOf(f)
    const lo = clamp(Math.floor(x[i] / step) * step, f.min_g, f.max_g)
    const hi = clamp(Math.ceil(x[i] / step) * step, f.min_g, f.max_g)
    return lo === hi ? [lo] : [lo, hi]
  })
  let best: Portion[] | null = null
  let bestScore = Number.MAX_VALUE
  const walk = (i: number, acc: Portion[]) => {
    if (i === foods.length) {
      const all = [...acc, ...fixed]
      const s = score(t, all, recent)
      if (s < bestScore - 1e-12) { bestScore = s; best = all }
      return
    }
    for (const g of options[i]) walk(i + 1, [...acc, { food: foods[i], grams: g }])
  }
  walk(0, [])
  return best!
}

/** One plate for [t] from exactly [foods] (each within its bounds) plus the [fixed] portions. */
export function solvePortions(t: PlateTarget, foods: FoodOption[], fixed: Portion[] = []): Plate {
  if (foods.length === 0) return { portions: fixed, gaps: gaps(t, fixed) }
  const x = solveGrams(t, foods, fixed)
  const plate = rounded(t, foods, x, fixed, new Set())
  return { portions: plate, gaps: gaps(t, plate) }
}
