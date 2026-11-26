// Pure numbers behind the per-meal view (mealView.ts): where a meal stands among the others, its macro split, its micronutrients.
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

