// Loading and saving for the web meal prep: the week the phone published (fuel_week_plans), the prep week
// (meal_prep_weeks, the same row shape as the Android data/MealPrepRepository.kt) and the fixed breakfast
// (breakfast_templates). The numbers are worked out by supabase/functions/_shared/fuel-core each time; only choices and
// cooked grams are stored, as on the phone.

import { supabase } from './supabase'
import type { BreakfastItem, PrepPicks, PrepSlot, Recipe, StoredContainer, WeekPlanSnapshot } from '../../../supabase/functions/_shared/fuel-core/index.ts'

export interface PublishedWeek { plan: WeekPlanSnapshot; computedAt: string; engineVersion: string }

export interface PrepWeek { weekStart: string; picks: PrepPicks; recipes: Recipe[]; skipped: string[]; containers: StoredContainer[] | null }

export const emptyWeek = (weekStart: string): PrepWeek => ({ weekStart, picks: { proteins: [], vegetables: [], carbs: [] }, recipes: [], skipped: [], containers: null })

interface Row {
  week_start: string; picks: Partial<PrepPicks> | null; recipes: { letter: string; slot: string; proteins?: string[]; carbs?: string[]; vegetables?: string[]; oil?: boolean }[] | null
  skipped: string[] | null; containers: { date: string; slot: string; letter: string; grams: Record<string, number> }[] | null; status: string
}

export async function loadPublished(weekStart: string): Promise<PublishedWeek | null> {
  const r = await supabase.from('fuel_week_plans').select('plan,computed_at,engine_version').eq('week_start', weekStart).maybeSingle()
  if (r.error) throw new Error(`fuel_week_plans: ${r.error.message}`)
  if (!r.data) return null
  const d = r.data as { plan: WeekPlanSnapshot; computed_at: string; engine_version: string }
  return { plan: d.plan, computedAt: d.computed_at, engineVersion: d.engine_version }
}

/** Every week the phone has sent, newest first: what the web can portion right now. */
export async function loadPublishedWeeks(): Promise<{ weekStart: string; computedAt: string }[]> {
  const r = await supabase.from('fuel_week_plans').select('week_start,computed_at').order('week_start', { ascending: false }).limit(8)
  if (r.error) throw new Error(`fuel_week_plans: ${r.error.message}`)
  return ((r.data ?? []) as { week_start: string; computed_at: string }[]).map((x) => ({ weekStart: x.week_start, computedAt: x.computed_at }))
}

export async function loadWeek(weekStart: string): Promise<PrepWeek> {
  const r = await supabase.from('meal_prep_weeks').select('week_start,picks,recipes,skipped,containers,status').eq('week_start', weekStart).maybeSingle()
  if (r.error) throw new Error(`meal_prep_weeks: ${r.error.message}`)
  const row = r.data as Row | null
  if (!row) return emptyWeek(weekStart)
  return {
    weekStart,
    picks: { proteins: row.picks?.proteins ?? [], vegetables: row.picks?.vegetables ?? [], carbs: row.picks?.carbs ?? [] },
    recipes: (row.recipes ?? []).filter((x) => x.slot === 'LUNCH' || x.slot === 'DINNER').map((x) => ({
      letter: x.letter.charAt(0), slot: x.slot as PrepSlot, proteins: x.proteins ?? [], carbs: x.carbs ?? [], vegetables: x.vegetables ?? [], oil: x.oil ?? true,
    })),
    skipped: row.skipped ?? [],
    containers: row.status === 'cooked' ? (row.containers ?? []).map((c) => ({ date: c.date, slot: c.slot as PrepSlot, letter: c.letter.charAt(0), grams: c.grams })) : null,
  }
}

export async function saveWeek(w: PrepWeek): Promise<void> {
  const r = await supabase.from('meal_prep_weeks').upsert({
    week_start: w.weekStart,
    picks: w.picks,
    recipes: w.recipes.map((x) => ({ letter: x.letter, slot: x.slot, proteins: x.proteins, carbs: x.carbs, vegetables: x.vegetables, oil: x.oil })),
    skipped: [...w.skipped].sort(),
    containers: w.containers,
    status: w.containers ? 'cooked' : 'planned',
    updated_at: new Date().toISOString(),
  }, { onConflict: 'user_id,week_start' })
  if (r.error) throw new Error(`meal_prep_weeks: ${r.error.message}`)
}

export async function loadBreakfast(): Promise<BreakfastItem[]> {
  const r = await supabase.from('breakfast_templates').select('items').maybeSingle()
  if (r.error) throw new Error(`breakfast_templates: ${r.error.message}`)
  return ((r.data as { items?: { food_id: string; grams: number }[] } | null)?.items ?? []).map((i) => ({ foodId: i.food_id, grams: i.grams }))
}

export async function saveBreakfast(items: BreakfastItem[]): Promise<void> {
  const r = await supabase.from('breakfast_templates').upsert({ items: items.map((i) => ({ food_id: i.foodId, grams: i.grams })), updated_at: new Date().toISOString() }, { onConflict: 'user_id' })
  if (r.error) throw new Error(`breakfast_templates: ${r.error.message}`)
}
