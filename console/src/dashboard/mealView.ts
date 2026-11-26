// The per-meal view behind a meal row of TODAY's plan: one meal's own numbers and where each sits among the other meals
// of the last 90 days (meal_profiles + nutrient_intake). Separate indexes, never one combined score (owner's rule).
// Micronutrients are the database matches' amounts (nutrient_intake, source_type 'meal_item'): meals without matches have none.
import { supabase } from '../lib/supabase'
import { plusDays } from '../lib/dates'
import { type Micro, rankMicros } from './mealStats'

export const COMPARE_DAYS = 90
/** The protein a main meal should carry to count as a full dose when no body weight is at hand (meal-profile.md). */
export const PROTEIN_DOSE_G = 25

export interface MealRowData { id: number; logged_at: string; meal_slot: string | null; description: string | null; source: string | null }
export interface ProfileData {
  meal_id: number; logged_at: string; calories: number | null; protein_g: number | null; carbs_g: number | null; fat_g: number | null; fiber_g: number | null
  energy_density_kcal_g: number | null; protein_per_100kcal: number | null; carbs_per_100kcal: number | null; fiber_per_1000kcal: number | null
  glycemic_load_est: number | null; fullness_factor_est: number | null; ultra_processed_pct: number | null
  food_groups: string[] | null; tags: string[] | null; macro_mismatch: boolean | null
}
export interface ItemData { description: string | null; calories: number | null; protein_g: number | null; carbs_g: number | null; fat_g: number | null; quantity: number | null; quantity_unit: string | null }
export interface MealDetail { meal: MealRowData; profile: ProfileData | null; items: ItemData[]; micros: Micro[]; others: ProfileData[]; dayTotals: { kcal: number } }

/** One meal with its profile, items, micronutrients and the last 90 days' meals to set it against. */
export async function loadMealDetail(mealId: number, day: string): Promise<MealDetail> {
  const lo = `${day}T00:00:00+00:00`, hi = `${plusDays(day, 1)}T00:00:00+00:00`
  const from = `${plusDays(day, -COMPARE_DAYS)}T00:00:00+00:00`
  const [meal, profile, items, others, dayItems] = await Promise.all([
    supabase.from('meals').select('id,logged_at,meal_slot,description,source').eq('id', mealId).single(),
    supabase.from('meal_profiles').select('*').eq('meal_id', mealId).maybeSingle(),
    supabase.from('meal_items').select('id,description,calories,protein_g,carbs_g,fat_g,quantity,quantity_unit').eq('meal_id', mealId).order('sort_order'),
    supabase.from('meal_profiles').select('meal_id,logged_at,calories,protein_g,carbs_g,fat_g,fiber_g,energy_density_kcal_g,protein_per_100kcal,carbs_per_100kcal,fiber_per_1000kcal,glycemic_load_est,fullness_factor_est,ultra_processed_pct')
      .gte('logged_at', from).lt('logged_at', hi).neq('meal_id', mealId).limit(2000),
    supabase.from('meal_items').select('id,meal_id,meals!inner(logged_at)').gte('meals.logged_at', lo).lt('meals.logged_at', hi),
  ])
  if (meal.error) throw new Error(`meals: ${meal.error.message}`)
  const itemRows = (items.error ? [] : items.data ?? []) as (ItemData & { id: number })[]
  // Micronutrients are an extra: a failed read leaves them out.
  const dayItemIds = ((dayItems.error ? [] : dayItems.data) ?? []) as { id: number; meal_id: number }[]
  const intake = dayItemIds.length ? await supabase.from('nutrient_intake').select('nutrient,amount,unit,source_id').eq('source_type', 'meal_item').in('source_id', dayItemIds.map((x) => x.id)) : null
  const rows = ((intake?.error ? [] : intake?.data) ?? []) as { nutrient: string; amount: number; unit: string; source_id: number }[]
  const mine = new Set(itemRows.map((x) => x.id))
  const dayTotals = new Map<string, number>()
  for (const r of rows) dayTotals.set(r.nutrient, (dayTotals.get(r.nutrient) ?? 0) + r.amount)
  const prof = (profile.error ? null : profile.data) as ProfileData | null
  return {
    meal: meal.data as MealRowData, profile: prof, items: itemRows,
    micros: rankMicros(rows.filter((r) => mine.has(r.source_id)), dayTotals),
    others: (others.error ? [] : others.data ?? []) as ProfileData[],
    dayTotals: { kcal: dayTotals.get('calories') ?? 0 },
  }
}
