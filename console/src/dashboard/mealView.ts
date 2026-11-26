// The per-meal view behind a meal row of TODAY's plan: one meal's own numbers and where each sits among the other meals
// of the last 90 days (meal_profiles + nutrient_intake). Separate indexes, never one combined score (owner's rule).
// Micronutrients are the database matches' amounts (nutrient_intake, source_type 'meal_item'): meals without matches have none.
import { supabase } from '../lib/supabase'
import { plusDays } from '../lib/dates'
import { combineProfiles, type Micro, type ProfileData, rankMicros } from './mealStats'
import { groupMeals } from './mealGroups'

export const COMPARE_DAYS = 90
/** The protein a main meal should carry to count as a full dose when no body weight is at hand (meal-profile.md). */
export const PROTEIN_DOSE_G = 25

export interface MealRowData { ids: number[]; logged_at: string; meal_slot: string | null; description: string | null }
export interface ItemData { description: string | null; calories: number | null; protein_g: number | null; carbs_g: number | null; fat_g: number | null; quantity: number | null; quantity_unit: string | null }
export interface MealDetail { meal: MealRowData; profile: ProfileData | null; items: ItemData[]; micros: Micro[]; others: ProfileData[]; dayTotals: { kcal: number } }

const PROFILE_COLS = 'meal_id,logged_at,food_grams,calories,protein_g,carbs_g,fat_g,fiber_g,energy_density_kcal_g,protein_per_100kcal,carbs_per_100kcal,fiber_per_1000kcal,glycemic_load_est,fullness_factor_est,ultra_processed_pct,food_groups,tags,macro_mismatch'

/** One meal (everything logged for it: [ids], mealGroups.ts) with its profile, items, micronutrients and the last 90 days' meals to set it against. */
export async function loadMealDetail(ids: number[], day: string): Promise<MealDetail> {
  const lo = `${day}T00:00:00+00:00`, hi = `${plusDays(day, 1)}T00:00:00+00:00`
  const from = `${plusDays(day, -COMPARE_DAYS)}T00:00:00+00:00`
  const [meals, profiles, items, others, otherMeals, dayItems] = await Promise.all([
    supabase.from('meals').select('id,logged_at,meal_slot,description').in('id', ids).order('logged_at'),
    supabase.from('meal_profiles').select(PROFILE_COLS).in('meal_id', ids).order('logged_at'),
    supabase.from('meal_items').select('id,description,calories,protein_g,carbs_g,fat_g,quantity,quantity_unit').in('meal_id', ids).order('meal_id').order('sort_order'),
    supabase.from('meal_profiles').select(PROFILE_COLS).gte('logged_at', from).lt('logged_at', hi).limit(3000),
    supabase.from('meals').select('id,logged_at,meal_slot').gte('logged_at', from).lt('logged_at', hi).limit(3000),
    supabase.from('meal_items').select('id,meal_id,meals!inner(logged_at)').gte('meals.logged_at', lo).lt('meals.logged_at', hi),
  ])
  if (meals.error) throw new Error(`meals: ${meals.error.message}`)
  const mealRows = (meals.data ?? []) as { id: number; logged_at: string; meal_slot: string | null; description: string | null }[]
  if (mealRows.length === 0) throw new Error('meal not found')
  const itemRows = (items.error ? [] : items.data ?? []) as (ItemData & { id: number })[]
  // Micronutrients are an extra: a failed read leaves them out.
  const dayItemIds = ((dayItems.error ? [] : dayItems.data) ?? []) as { id: number; meal_id: number }[]
  const intake = dayItemIds.length ? await supabase.from('nutrient_intake').select('nutrient,amount,unit,source_id').eq('source_type', 'meal_item').in('source_id', dayItemIds.map((x) => x.id)) : null
  const rows = ((intake?.error ? [] : intake?.data) ?? []) as { nutrient: string; amount: number; unit: string; source_id: number }[]
  const mine = new Set(itemRows.map((x) => x.id))
  const dayTotals = new Map<string, number>()
  for (const r of rows) dayTotals.set(r.nutrient, (dayTotals.get(r.nutrient) ?? 0) + r.amount)
  // The other meals are compared as meals: the entries of one sitting count once, combined.
  const mine_ids = new Set(ids)
  const byId = new Map(((others.error ? [] : others.data ?? []) as ProfileData[]).map((p) => [p.meal_id, p]))
  const sittings = groupMeals(((otherMeals.error ? [] : otherMeals.data ?? []) as { id: number; logged_at: string; meal_slot: string | null }[]).filter((m) => byId.has(m.id)))
    .filter((g) => !g.some((m) => mine_ids.has(m.id)))
  return {
    meal: { ids, logged_at: mealRows[0].logged_at, meal_slot: mealRows.find((m) => m.meal_slot)?.meal_slot ?? null, description: mealRows.map((m) => m.description).filter(Boolean).join(' + ') || null },
    profile: combineProfiles(((profiles.error ? [] : profiles.data) ?? []) as ProfileData[]),
    items: itemRows,
    micros: rankMicros(rows.filter((r) => mine.has(r.source_id)), dayTotals),
    others: sittings.map((g) => combineProfiles(g.map((m) => byId.get(m.id)!))).filter((p): p is ProfileData => p != null),
    dayTotals: { kcal: dayTotals.get('calories') ?? 0 },
  }
}
