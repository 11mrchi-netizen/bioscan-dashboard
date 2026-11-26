// Pure numbers behind the per-meal view (mealView.ts): where a meal stands among the others, its macro split, its micronutrients.
export interface ProfileData {
  meal_id: number; logged_at: string; food_grams: number | null; calories: number | null; protein_g: number | null; carbs_g: number | null; fat_g: number | null; fiber_g: number | null
  energy_density_kcal_g: number | null; protein_per_100kcal: number | null; carbs_per_100kcal: number | null; fiber_per_1000kcal: number | null
  glycemic_load_est: number | null; fullness_factor_est: number | null; ultra_processed_pct: number | null
  food_groups: string[] | null; tags: string[] | null; macro_mismatch: boolean | null
}
export interface Micro { nutrient: string; amount: number; unit: string; /** Share of the day's total of that nutrient. */ dayShare: number | null }

/** Where [value] stands among [others]: share of them at or below it (0-100), with their quartiles. Null under 5 values. */
export function standing(value: number | null | undefined, others: (number | null | undefined)[]): { pct: number; min: number; q1: number; median: number; q3: number; max: number; n: number } | null {
  const xs = others.filter((x): x is number => typeof x === 'number' && Number.isFinite(x)).sort((a, b) => a - b)
  if (value == null || !Number.isFinite(value) || xs.length < 5) return null
  const at = (q: number) => { const i = (xs.length - 1) * q, lo = Math.floor(i); return xs[lo] + (xs[Math.min(lo + 1, xs.length - 1)] - xs[lo]) * (i - lo) }
  return { pct: Math.round((100 * xs.filter((x) => x <= value).length) / xs.length), min: xs[0], q1: at(0.25), median: at(0.5), q3: at(0.75), max: xs[xs.length - 1], n: xs.length }
}

/** Share of the meal's energy from protein, carbs and fat (4/4/9 kcal per g); null with nothing logged. */
export function macroSplit(p: { protein_g: number | null; carbs_g: number | null; fat_g: number | null }): { protein: number; carbs: number; fat: number } | null {
  const k = { protein: 4 * (p.protein_g ?? 0), carbs: 4 * (p.carbs_g ?? 0), fat: 9 * (p.fat_g ?? 0) }
  const t = k.protein + k.carbs + k.fat
  return t > 0 ? { protein: k.protein / t, carbs: k.carbs / t, fat: k.fat / t } : null
}

/** Display names and order for the micronutrients (the ones with an everyday reading), the rest follow alphabetically. */
const MICRO_ORDER = ['fiber', 'sugar', 'sodium', 'potassium', 'calcium', 'magnesium', 'iron', 'zinc', 'phosphorus', 'copper', 'manganese', 'selenium', 'vitamin_a', 'vitamin_c', 'vitamin_d', 'vitamin_e', 'vitamin_k', 'thiamin_b1', 'riboflavin_b2', 'niacin_b3', 'pantothenic_acid_b5', 'vitamin_b6', 'folate_b9', 'vitamin_b12', 'choline', 'omega_3', 'saturated_fat', 'monounsaturated_fat', 'polyunsaturated_fat', 'water']
// Already shown as the meal's macros, and the amino acids: left out of the micronutrient list.
const SKIP = new Set(['calories', 'protein', 'carbs', 'fat'])
const AMINO = /^(alanine|arginine|aspartic_acid|cystine|glutamic_acid|glycine|histidine|isoleucine|leucine|lysine|methionine|phenylalanine|proline|serine|threonine|tryptophan|tyrosine|valine)$/
export const microName = (n: string) => n.replace(/_b\d+$/, (m) => ` (${m.slice(1).toUpperCase()})`).replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())

export function rankMicros(rows: { nutrient: string; amount: number; unit: string }[], dayTotals: Map<string, number>): Micro[] {
  const sum = new Map<string, { amount: number; unit: string }>()
  for (const r of rows) {
    if (SKIP.has(r.nutrient) || AMINO.test(r.nutrient) || !(r.amount > 0)) continue
    const c = sum.get(r.nutrient)
    if (c) c.amount += r.amount; else sum.set(r.nutrient, { amount: r.amount, unit: r.unit })
  }
  const rank = (n: string) => { const i = MICRO_ORDER.indexOf(n); return i < 0 ? MICRO_ORDER.length : i }
  return [...sum].map(([nutrient, v]) => ({ nutrient, ...v, dayShare: dayTotals.get(nutrient) ? v.amount / dayTotals.get(nutrient)! : null }))
    .sort((a, b) => rank(a.nutrient) - rank(b.nutrient) || a.nutrient.localeCompare(b.nutrient))
}


const all = (xs: (number | null)[]): xs is number[] => xs.length > 0 && xs.every((x) => x != null)

/**
 * One profile for the meals of one sitting. Amounts add up; the indexes are worked out again from the sums, or combined the way
 * they are defined (energy density from food kcal over food grams, glycemic load adds, the rest weighted by grams or kcal).
 * A total or index is null when any part of the meal lacks it: never guessed, never a partial sum shown as complete.
 */
export function combineProfiles(ps: ProfileData[]): ProfileData | null {
  if (ps.length === 0) return null
  // An entry with nothing recorded at all (a coffee logged without numbers) adds nothing and does not blank the meal.
  const empty = (p: ProfileData) => p.calories == null && p.protein_g == null && p.carbs_g == null && p.fat_g == null
  const everyone = ps
  const kept = ps.filter((p) => !empty(p))
  ps = kept.length ? kept : ps.slice(0, 1)
  if (ps.length === 1 && everyone.length === 1) return ps[0]
  const total = (pick: (p: ProfileData) => number | null) => { const v = ps.map(pick); return all(v) ? v.reduce((a, x) => a + x, 0) : null }
  const kcal = total((p) => p.calories), protein = total((p) => p.protein_g), carbs = total((p) => p.carbs_g)
  const grams = ps.map((p) => p.food_grams)
  const per = (x: number | null, k: number) => (x != null && kcal != null && kcal >= 50 ? (x / kcal) * k : null)
  const g = all(grams) ? grams : null
  const weighted = (idx: (p: ProfileData) => number | null, by: number[] | null) => {
    const v = ps.map(idx)
    if (!all(v) || !by) return null
    const w = by.reduce((a, x) => a + x, 0)
    return w > 0 ? v.reduce((a, x, i) => a + x * by[i], 0) / w : null
  }
  const kcals = ps.map((p) => p.calories)
  return {
    meal_id: ps[0].meal_id, logged_at: ps[0].logged_at, calories: kcal, protein_g: protein, carbs_g: carbs, fat_g: total((p) => p.fat_g),
    fiber_g: total((p) => p.fiber_g), food_grams: g ? g.reduce((a, x) => a + x, 0) : null,
    energy_density_kcal_g: weighted((p) => p.energy_density_kcal_g, g),
    protein_per_100kcal: per(protein, 100), carbs_per_100kcal: per(carbs, 100), fiber_per_1000kcal: per(total((p) => p.fiber_g), 1000),
    glycemic_load_est: all(ps.map((p) => p.glycemic_load_est)) ? ps.reduce((a, p) => a + p.glycemic_load_est!, 0) : null,
    fullness_factor_est: weighted((p) => p.fullness_factor_est, g),
    ultra_processed_pct: weighted((p) => p.ultra_processed_pct, all(kcals) && kcals.some((x) => x > 0) ? kcals : null),
    // Food groups, gut tags and the mismatch flag come from every entry: a coffee with no numbers still carries its caffeine tag.
    food_groups: [...new Set(everyone.flatMap((p) => p.food_groups ?? []))], tags: [...new Set(everyone.flatMap((p) => p.tags ?? []))],
    macro_mismatch: everyone.some((p) => p.macro_mismatch),
  }
}
