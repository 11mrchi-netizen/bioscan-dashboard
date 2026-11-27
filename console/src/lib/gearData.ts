// The person's gear rules ("straps on deadlifts from 140 kg"), table gear_rules (supabase/migrations/20261127004134_gear_rules.sql).
// One row per lift (movementKey) and gear tag; the phone reads and writes the same rows.
import { supabase } from './supabase'
import { cleanGear, movementKey, type GearRule } from '../../../supabase/functions/_shared/training-core/index.ts'

export async function loadGearRules(): Promise<GearRule[]> {
  const r = await supabase.from('gear_rules').select('movement_key,gear,from_kg').order('movement_key').order('from_kg', { nullsFirst: true })
  if (r.error) throw new Error(`gear_rules: ${r.error.message}`)
  return ((r.data ?? []) as Array<{ movement_key: string; gear: string; from_kg: number | string | null }>).map((x) => ({ movement_key: x.movement_key, gear: x.gear, from_kg: x.from_kg == null ? null : Number(x.from_kg) }))
}

/** Sets the rule for a lift and gear: new, or the threshold changed. `fromKg` null: at any load. */
export async function saveGearRule(exercise: string, gear: string, fromKg: number | null): Promise<void> {
  if (exercise.trim() === '') throw new Error('Name the lift.')
  if (cleanGear([gear]).length !== 1) throw new Error('Pick a piece of gear.')
  if (fromKg != null && !(fromKg >= 0)) throw new Error('The weight is a number of kg, 0 or more.')
  const r = await supabase.from('gear_rules').upsert({ movement_key: movementKey(exercise), gear, from_kg: fromKg }, { onConflict: 'user_id,movement_key,gear' })
  if (r.error) throw new Error(`gear_rules: ${r.error.message}`)
}

export async function deleteGearRule(movementKeyOf: string, gear: string): Promise<void> {
  const r = await supabase.from('gear_rules').delete().eq('movement_key', movementKeyOf).eq('gear', gear)
  if (r.error) throw new Error(`gear_rules: ${r.error.message}`)
}
