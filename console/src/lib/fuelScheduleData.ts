// The reads and writes behind a fuel schedule (fuel_schedules, the phone's table: one row per kind and ref, edited in place, blocks as the phone's FuelBlock) and the
// standards (fuel_standards, web only; the table may not exist until its migration is applied). The planner's plan as a starting schedule is the phone's seedSchedule.
import { supabase } from './supabase'
import { productsOf, sweatTestsOf } from './raceFuelPlan'
import {
  atTime, DEFAULT_RACE_SETTINGS, type FuelBlock, type FuelProductOption, instant, NO_WEATHER, planRaceFuel, type PlannedItem, seedBlocks, type Standard, type StandardRule, sweatProfile, zdtString,
} from '../../../supabase/functions/_shared/fuel-core/index.ts'

export interface ScheduleRow { duration_min: number; blocks: FuelBlock[]; seeded_from: string | null; updated_at: string }

/** A block as the phone reads it: every field present, numbers as numbers. */
export function cleanBlock(b: Partial<FuelBlock> & { id: string }): FuelBlock {
  // amounts to a tenth: a seeded schedule divides fluid over the steps (123.333…), which no one types
  const n = (x: unknown, d = 0) => (Number.isFinite(Number(x)) ? Math.round(Number(x) * 10) / 10 : d)
  return {
    id: b.id, label: String(b.label ?? ''), form: String(b.form ?? 'other'), startMin: Math.round(n(b.startMin)), intervalMin: Math.round(n(b.intervalMin)), count: Math.max(1, Math.round(n(b.count, 1))),
    carbsG: n(b.carbsG), fluidMl: n(b.fluidMl), sodiumMg: n(b.sodiumMg), caffeineMg: n(b.caffeineMg), fuelingProductId: b.fuelingProductId ?? null, supplementProductId: b.supplementProductId ?? null,
  }
}

export async function loadSchedule(kind: string, ref: string): Promise<ScheduleRow | null> {
  const r = await supabase.from('fuel_schedules').select('duration_min,blocks,seeded_from,updated_at').eq('kind', kind).eq('ref_key', ref).limit(1)
  if (r.error) throw new Error(`fuel_schedules: ${r.error.message}`)
  const row = (r.data ?? [])[0] as ScheduleRow | undefined
  return row ? { ...row, blocks: (Array.isArray(row.blocks) ? row.blocks : []).map((b) => cleanBlock(b)) } : null
}

/** Saves in place: the same (kind, ref) updates the one row. */
export async function saveSchedule(kind: string, ref: string, durationMin: number, blocks: FuelBlock[], seededFrom: string | null): Promise<void> {
  const r = await supabase.from('fuel_schedules').upsert(
    { kind, ref_key: ref, duration_min: Math.min(Math.max(Math.round(durationMin), 1), 10080), blocks: blocks.map(cleanBlock), seeded_from: seededFrom, updated_at: new Date().toISOString() },
    { onConflict: 'user_id,kind,ref_key' },
  )
  if (r.error) throw new Error(`fuel_schedules: ${r.error.message}`)
}

export async function deleteSchedule(kind: string, ref: string): Promise<void> {
  const r = await supabase.from('fuel_schedules').delete().eq('kind', kind).eq('ref_key', ref)
  if (r.error) throw new Error(`fuel_schedules: ${r.error.message}`)
}

/** The person's fuel catalog (products with carbohydrate on record), for filling a block's amounts. */
export async function loadCatalog(): Promise<FuelProductOption[]> {
  const [p, v] = await Promise.all([
    supabase.from('fueling_products').select('id,kind,name,brand').order('name', { ascending: true }),
    supabase.from('fueling_recipe_versions').select('product_id,version,carbs_g,fluid_ml,sodium_mg,caffeine_mg,fructose_g'),
  ])
  if (p.error) throw new Error(`fueling_products: ${p.error.message}`)
  return productsOf((p.data ?? []) as { id: number; kind: string; name: string; brand: string | null }[], (v.data ?? []) as Record<string, unknown>[])
}

/** The planner's plan for a long session as blocks to start from (the phone's seedSchedule: no weather unless the web knew a place, the defaults for the settings it cannot read). */
export async function seedScheduleFor(item: PlannedItem, weightKg: number, zone: string): Promise<FuelBlock[]> {
  const [tests, products] = await Promise.all([
    supabase.from('sweat_tests').select('tested_on,session_type,intensity,duration_min,weight_before_kg,weight_after_kg,fluid_in_ml,urine_ml,temp_c,humidity_pct').order('tested_on', { ascending: false }).limit(200),
    loadCatalog(),
  ])
  const start = item.start ?? zdtString(atTime(new Date().toLocaleDateString('sv-SE'), '09:00:00', zone), zone)
  void instant
  const plan = planRaceFuel({
    title: item.title, type: item.type, intensity: item.intensity, start, durationMin: item.durationMin, durationLowMin: item.durationMin * 0.9, durationHighMin: item.durationMin * 1.1,
    durationBasis: 'the planned session length', course: [], distanceKm: item.distanceKm, aidStations: [], weather: NO_WEATHER, sweat: sweatProfile(sweatTestsOf((tests.data ?? []) as Record<string, unknown>[]), weightKg),
    weightKg, settings: DEFAULT_RACE_SETTINGS, products, history: null, loadingDays: false,
  })
  return seedBlocks(plan)
}

// ---- standards ----

export interface StandardsResult { standards: Standard[]; missing: boolean }

/** The saved standards; missing is true while the fuel_standards table has not been created (its migration is not applied). */
export async function loadStandards(): Promise<StandardsResult> {
  const r = await supabase.from('fuel_standards').select('id,name,rules').order('name', { ascending: true })
  if (r.error) return { standards: [], missing: /fuel_standards|relation|schema cache|does not exist/i.test(r.error.message) }
  return { standards: ((r.data ?? []) as { id: number; name: string; rules: StandardRule[] }[]).map((s) => ({ id: s.id, name: s.name, rules: Array.isArray(s.rules) ? s.rules : [] })), missing: false }
}

export async function saveStandard(s: Standard): Promise<void> {
  const r = await supabase.from('fuel_standards').upsert({ name: s.name.trim(), rules: s.rules, updated_at: new Date().toISOString() }, { onConflict: 'user_id,name' })
  if (r.error) throw new Error(`fuel_standards: ${r.error.message}`)
}

export async function deleteStandard(id: number): Promise<void> {
  const r = await supabase.from('fuel_standards').delete().eq('id', id)
  if (r.error) throw new Error(`fuel_standards: ${r.error.message}`)
}
