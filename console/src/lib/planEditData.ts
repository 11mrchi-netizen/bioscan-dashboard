// Reads and writes for editing the plan before it starts. Every write goes through the database functions of
// supabase/migrations/20261124063309_plan_edit.sql, which hold the rules (a session still planned, unlinked, dated today or later, in a
// block that has not ended; a prescription change is a new revision; removing sets a session aside; a block's start moves with its
// sessions). Before that migration is applied the functions do not exist: `MigrationMissing` says so in words.
import { supabase } from './supabase'
import { movementKey } from '../../../supabase/functions/_shared/training-core/maxes.ts'
import type { ResolveContext } from '../../../supabase/functions/_shared/training-core/index.ts'

export class MigrationMissing extends Error {
  constructor() { super('Editing the plan needs a one-time database update (supabase/migrations/20261124063309_plan_edit.sql) that has not been applied yet.'); this.name = 'MigrationMissing' }
}

/** A database-function error as words for the person: the function's own message without its table prefix. */
function fail(error: { code?: string; message: string }): never {
  if (error.code === 'PGRST202' || /could not find the function/i.test(error.message)) throw new MigrationMissing()
  throw new Error(error.message.replace(/^(planned_sessions|training_blocks): /, ''))
}

export interface PlannedFull {
  id: number; block_id: number; revision: number; sequence_no: number; week_index: number; domain: string; work_kind: string; module_ref: string; title: string
  prescription: unknown; original_date: string; scheduled_date: string; start_time: string | null; duration_min: number | null; status: string
  exercise_session_id: number | null; zepp_template_id: string | null; gcal_event_id: string | null; note?: string | null
}
export interface BlockLite { id: number; name: string; status: string; start_date: string; end_date: string; choices: unknown }

const COLS = 'id,block_id,revision,sequence_no,week_index,domain,work_kind,module_ref,title,prescription,original_date,scheduled_date,start_time,duration_min,status,exercise_session_id,zepp_template_id,gcal_event_id'

export async function loadPlannedFull(id: number): Promise<{ session: PlannedFull; block: BlockLite } | null> {
  let r = await supabase.from('planned_sessions').select(`${COLS},note`).eq('id', id).maybeSingle()
  if (r.error && /\bnote\b/.test(r.error.message)) r = await supabase.from('planned_sessions').select(COLS).eq('id', id).maybeSingle() // not migrated yet
  if (r.error) throw new Error(`planned_sessions: ${r.error.message}`)
  if (!r.data) return null
  const session = r.data as unknown as PlannedFull
  const b = await supabase.from('training_blocks').select('id,name,status,start_date,end_date,choices').eq('id', session.block_id).maybeSingle()
  if (b.error) throw new Error(`training_blocks: ${b.error.message}`)
  return { session, block: (b.data as BlockLite | null) ?? { id: session.block_id, name: 'block', status: 'scheduled', start_date: '', end_date: '', choices: null } }
}

/** The bar and plates the web resolves a load with (the phone's defaults when no settings are saved). */
export async function loadEquipment(): Promise<Omit<ResolveContext, 'oneRmKg'>> {
  const r = await supabase.from('training_settings').select('bars,plates_kg,weighted_percent_base').maybeSingle()
  const row = (r.data ?? {}) as { bars?: { kg?: number }[]; plates_kg?: number[]; weighted_percent_base?: string }
  const plates = Array.isArray(row.plates_kg) && row.plates_kg.length ? row.plates_kg : [1.25, 2.5, 5, 10, 15, 20]
  return { trainingMaxKg: null, maxReps: null, bodyweightKg: null, barKg: row.bars?.[0]?.kg ?? 20, platesKg: plates, addedStepKg: 2.5, weightedBase: row.weighted_percent_base === 'added' ? 'added' : 'total' }
}

/** The newest recorded 1RM (or estimate) of each exercise asked about (every exercise when none are named, so a swapped-in lift has its own), by movement key; absent when none is recorded. */
export async function loadOneRms(exercises?: string[]): Promise<Map<string, number>> {
  const keys = exercises ? [...new Set(exercises.map(movementKey))] : null
  const out = new Map<string, number>()
  if (keys && keys.length === 0) return out
  let q = supabase.from('athlete_maxes').select('exercise_key,kind,value,as_of').in('kind', ['1rm', 'e1rm'])
  if (keys) q = q.in('exercise_key', keys)
  const r = await q.order('as_of', { ascending: false })
  if (r.error) return out
  for (const m of (r.data ?? []) as { exercise_key: string; value: number }[]) if (!out.has(m.exercise_key)) out.set(m.exercise_key, Number(m.value))
  return out
}

export async function editPlannedSession(id: number, today: string, changes: Record<string, string | number | null>): Promise<PlannedFull> {
  const r = await supabase.rpc('edit_planned_session', { p_id: id, p_today: today, p_changes: changes })
  if (r.error) fail(r.error)
  return r.data as PlannedFull
}
/** A new prescription: a new revision of the session (the old row is set aside); returns the new row. */
export async function revisePlannedSession(id: number, today: string, prescription: unknown): Promise<PlannedFull> {
  const r = await supabase.rpc('revise_planned_session', { p_id: id, p_today: today, p_prescription: prescription })
  if (r.error) fail(r.error)
  return r.data as PlannedFull
}
/** Takes a session out of the plan (kept, never deleted; the phone takes its Zepp template off the watch), or brings it back. */
export async function setAsidePlannedSession(id: number, today: string, aside: boolean): Promise<PlannedFull> {
  const r = await supabase.rpc('set_aside_planned_session', { p_id: id, p_today: today, p_aside: aside })
  if (r.error) fail(r.error)
  return r.data as PlannedFull
}
/** Moves the start of a block that has not begun, with all its sessions. */
export async function shiftTrainingBlock(blockId: number, today: string, newStart: string): Promise<BlockLite> {
  const r = await supabase.rpc('shift_training_block', { p_block_id: blockId, p_today: today, p_new_start: newStart })
  if (r.error) fail(r.error)
  return r.data as BlockLite
}
